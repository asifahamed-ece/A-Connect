package com.aplusconnect.companion.net

import android.util.Log
import com.aplusconnect.companion.mirror.FrameGate
import com.aplusconnect.companion.protocol.FrameCodec
import com.aplusconnect.companion.protocol.Handshake
import com.aplusconnect.companion.protocol.HandshakeResult
import com.aplusconnect.companion.protocol.Pairing
import com.aplusconnect.companion.protocol.Protocol
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * The second socket: video only.
 *
 * It exists so a two megabyte keyframe cannot sit in front of a clipboard copy
 * on the control channel, and so the frame policy can be "drop, don't queue"
 * without that policy applying to anything the user would notice losing.
 *
 * It does not get its own trust story. The certificate is the same one the
 * phone already pinned, and the same [Handshake] proves possession - the
 * desktop refuses this socket to anything it has not verified, and refuses to
 * pair over it at all.
 *
 * Writes happen on this class's own thread, through a single slot, so the
 * encoder's drain loop is never blocked by a slow link: a frame that cannot go
 * out is dropped by [FrameGate] instead of growing a queue.
 */
class VideoClient(
    private val host: String,
    private val port: Int,
    private val deviceId: String,
    private val deviceName: String,
    private val pairing: Pairing?,
    private val sharedSecret: String,
    private val onClosed: (String) -> Unit,
) {
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null

    @Volatile
    private var running = false

    @Volatile
    private var ready = false

    private val gate = FrameGate()

    /**
     * The one frame waiting for the link, if any.
     *
     * A one-slot queue rather than a list: the point of the slot is that a
     * newer frame replaces an older one, so there is never a backlog to grow.
     */
    private val slot = java.util.concurrent.ArrayBlockingQueue<ByteArray>(1)

    private var writer: Thread? = null
    private var reader: Thread? = null
    private var watchdog: Thread? = null

    /**
     * When the write in progress started, or 0 when none is.
     *
     * A TCP write has no timeout, so a phone that walks out of Wi-Fi range sits
     * in `flush()` with the frames piling into a buffer nobody is draining - and
     * the encoder keeps running. This timestamp is what lets the watchdog tell
     * "a write is stuck" from "nothing to send".
     */
    @Volatile
    private var writeStartedAt = 0L

    /** So a session reports its end at most once, from whichever thread sees it. */
    @Volatile
    private var notified = false

    /**
     * Counted down when the handshake succeeds, or when the socket closes.
     *
     * The capture waits on it so the first second of video is not spent
     * encoding frames that are dropped because there is nowhere to send them.
     */
    private val readyLatch = java.util.concurrent.CountDownLatch(1)

    val isReady: Boolean
        get() = ready

    /** How many frames were thrown away because the link was busy. */
    val dropped: Int
        get() = gate.dropped

    /** Waits for the handshake, bounded. False means it is not usable. */
    fun awaitReady(timeoutMs: Long): Boolean = try {
        readyLatch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) && ready
    } catch (e: InterruptedException) {
        false
    }

    fun connect() {
        if (running) return
        running = true
        writer = Thread({ writeLoop() }, "companion-video-write").apply {
            isDaemon = true
            start()
        }
        reader = Thread({ readLoop() }, "companion-video-read").apply {
            isDaemon = true
            start()
        }
        watchdog = Thread({ watchLoop() }, "companion-video-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Ends a session whose frames have stopped moving.
     *
     * Closing the socket is what unblocks the writer: it is stuck inside
     * `write()`, and there is no other way to interrupt it. The service is told
     * once, and the encoder is released.
     */
    private fun watchLoop() {
        while (running) {
            try {
                Thread.sleep(WATCHDOG_TICK_MS)
            } catch (e: InterruptedException) {
                return
            }
            val started = writeStartedAt
            if (started != 0L && System.currentTimeMillis() - started > WRITE_STALL_MS) {
                Log.w(TAG, "the video link has not taken a frame for over ${WRITE_STALL_MS / 1000}s")
                close()
                notifyClosed("the video link stalled")
                return
            }
        }
    }

    /** Reports a closed session once, whichever thread got there first. */
    private fun notifyClosed(reason: String) {
        if (notified) return
        notified = true
        onClosed(reason)
    }

    fun close() {
        running = false
        ready = false
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "closing the video socket failed: ${e.message}")
        }
        socket = null
        output = null
        input = null
        synchronized(slot) { slot.clear() }
        gate.reset()
        writeStartedAt = 0L
        if (watchdog !== Thread.currentThread()) watchdog?.interrupt()
        watchdog = null
        // Awaited by the start path; releasing it here turns a failed connection
        // into an honest "not usable" instead of a timeout.
        readyLatch.countDown()
    }

    /**
     * Offers one encoded frame. Never blocks: a frame that cannot go out now is
     * dropped (or takes the place of an older waiting one), which is what keeps
     * the picture live instead of late.
     */
    fun sendFrame(frame: ByteArray, keyframe: Boolean): Boolean {
        if (!ready) return false
        return when (gate.offer(keyframe)) {
            FrameGate.Action.SEND, FrameGate.Action.HOLD -> {
                // Replace whatever is waiting: the older frame is stale news.
                synchronized(slot) {
                    slot.clear()
                    slot.offer(frame)
                }
                true
            }
            FrameGate.Action.DROP -> false
        }
    }

    private fun writeLoop() {
        while (running) {
            val frame = take() ?: return
            if (!write(frame)) return
            gate.onWritten()
        }
    }

    private fun take(): ByteArray? {
        while (running) {
            try {
                slot.poll(POLL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)?.let { return it }
            } catch (e: InterruptedException) {
                return null
            }
        }
        return null
    }

    private fun write(frame: ByteArray): Boolean {
        val stream = output ?: return false
        writeStartedAt = System.currentTimeMillis()
        return try {
            stream.write(FrameCodec.encodeBinary(frame))
            stream.flush()
            true
        } catch (e: Exception) {
            Log.w(TAG, "sending a video frame failed: ${e.javaClass.simpleName}: ${e.message}")
            // The session is gone; the read loop will notice too, but ending it
            // here stops the encoder from producing frames nobody can take.
            close()
            notifyClosed("the video connection failed")
            false
        } finally {
            writeStartedAt = 0L
        }
    }

    private fun readLoop() {
        var reason: String? = null
        try {
            val trust = PeerTrust(pairing?.fingerprint) { }
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf(trust), SecureRandom())

            val ssl = context.socketFactory.createSocket() as SSLSocket
            ssl.connect(InetSocketAddress(host, port), VIDEO_CONNECT_TIMEOUT_MS)
            ssl.startHandshake()

            socket = ssl
            input = BufferedInputStream(ssl.inputStream)
            output = BufferedOutputStream(ssl.outputStream)

            val nonce = UUID.randomUUID().toString()
            val secretBytes = java.util.Base64.getDecoder().decode(sharedSecret)
            val hello = Handshake.buildHello(
                deviceId = deviceId,
                deviceName = deviceName,
                // No code: this socket cannot pair. An unpaired computer is
                // refused above, on the control channel, where the user is.
                code = "",
                nonce = nonce,
                proof = Handshake.proofFor(secretBytes, deviceId, nonce),
                clipboardAccess = false,
            )

            val out = output!!
            out.write(FrameCodec.encode(hello))
            out.flush()

            when (val result = Handshake.verifyHelloAck(FrameCodec.readFrom(input!!), pairing, expectedNonce = nonce)) {
                is HandshakeResult.Failed -> {
                    Log.w(TAG, "the video socket was refused: ${result.reason}")
                    reason = "the video socket was refused"
                    return
                }
                is HandshakeResult.Success -> {
                    Log.i(TAG, "video socket open to ${result.pcId} on port $port")
                    ready = true
                    readyLatch.countDown()
                }
            }

            // Nothing else is expected on this socket. Reading keeps the close
            // detection honest: EOF here is how the desktop saying "stop" (or
            // dying) reaches this app.
            while (running) {
                val stream = input ?: break
                FrameCodec.readFrom(stream)
            }
        } catch (e: Exception) {
            reason = if (running) "the video connection was lost" else "closed"
            if (running) {
                Log.w(TAG, "video socket failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        } finally {
            val wasReady = ready
            close()
            readyLatch.countDown()
            if (wasReady || running) notifyClosed(reason ?: "the video connection closed")
        }
    }

    companion object {
        private const val TAG = "AplusConnect"

        /** Long enough for a Wi-Fi hiccup, short enough to fail visibly. */
        private const val VIDEO_CONNECT_TIMEOUT_MS = 6000

        /** How often the writer looks up from an empty slot to check for a close. */
        private const val POLL_MS = 200L

        /** How often the watchdog checks whether a write has been stuck. */
        private const val WATCHDOG_TICK_MS = 2000L

        /** A write that has not completed in this long means the link is gone. */
        private const val WRITE_STALL_MS = 6000L
    }
}