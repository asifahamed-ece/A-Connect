package com.aplusconnect.companion.net

import android.util.Log
import com.aplusconnect.companion.protocol.ClipboardPayloads
import com.aplusconnect.companion.protocol.FrameCodec
import com.aplusconnect.companion.protocol.Handshake
import com.aplusconnect.companion.protocol.HandshakeResult
import com.aplusconnect.companion.protocol.Pairing
import com.aplusconnect.companion.protocol.Protocol
import com.aplusconnect.companion.protocol.ProtocolMessage
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The connection to the computer.
 *
 * The phone dials out and the computer never connects to the phone, so the
 * phone exposes nothing on the network. The socket carries the length
 * prefixed JSON frames of the companion protocol.
 */
class CompanionClient(
    private val host: String,
    private val port: Int,
    private val deviceId: String,
    private val deviceName: String,
    private val code: String,
    private val pairing: Pairing?,
    private val sharedSecret: String,
    private val clipboardAccess: Boolean = true,
    private val onPaired: (pcId: String, fingerprint: String) -> Unit,
    private val onReady: () -> Unit,
    private val onClosed: (String) -> Unit,
    private val onClipboardReceived: (String) -> Unit = {},
    private val onSecretCreated: (String) -> Unit = {},
    private val onFileStart: (id: String, name: String, size: Long, mime: String) -> Unit = { _, _, _, _ -> },
    private val onFileChunk: (id: String, index: Int, bytes: ByteArray) -> Unit = { _, _, _ -> },
    private val onFileDone: (id: String) -> Unit = { },
    private val onNotificationAction: (id: String, action: String) -> Unit = { _, _ -> },
    private val onMediaCommand: (command: String, positionMs: Long?) -> Unit = { _, _ -> },
    private val onFileListRequest: (path: String) -> Unit = { },
    private val onFileSendRequest: (path: String) -> Unit = { },
    private val onFileSearchRequest: (query: String, fresh: Boolean) -> Unit = { _, _ -> },
) {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var reader: Thread? = null
    private var watchdog: Thread? = null

    @Volatile
    private var lastHeardAt = 0L

    @Volatile
    private var running = false

    @Volatile
    private var ready = false

    val isReady: Boolean
        get() = ready

    fun connect() {
        if (running) return
        running = true
        lastHeardAt = System.currentTimeMillis()

        reader = Thread({ readLoop() }, "companion-client").apply {
            isDaemon = true
            start()
        }

        // A link that carries no traffic is dropped by the network. The
        // computer pings every 30 seconds; if nothing comes back for 75
        // seconds the link is gone, and the service reconnects at once
        // instead of waiting for the next copy to fail.
        watchdog = Thread({
            while (running) {
                try {
                    Thread.sleep(WATCHDOG_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                if (!running) return@Thread
                if (System.currentTimeMillis() - lastHeardAt > PEER_TIMEOUT_MS) {
                    Log.w(TAG, "the computer stopped answering, dropping the link")
                    close()
                    return@Thread
                }
            }
        }, "companion-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    fun close() {
        running = false
        ready = false
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "closing the socket failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        socket = null
        input = null
        output = null
    }

    /**
     * The one lock every write to the session goes through.
     *
     * The clipboard, the file chunks and the ping answers can all be written
     * from different threads. Two writers that take different locks can
     * interleave inside a frame, and the computer then reads a corrupt stream
     * and drops the connection.
     */
    private val writeLock = Any()

    /** Writes one frame, under the session's single write lock. */
    private fun writeFrame(frame: ByteArray) {
        val stream = output ?: throw IllegalStateException("the session is closed")
        synchronized(writeLock) {
            stream.write(frame)
            stream.flush()
        }
    }

    /**
     * Sends one message of any type on the session. Returns true if it was
     * written. The notification and media messages go through here.
     */
    fun sendMessage(type: String, payload: JSONObject): Boolean {
        if (!ready) return false
        val message = ProtocolMessage(
            type = type,
            id = UUID.randomUUID().toString(),
            ts = System.currentTimeMillis(),
            payload = payload,
        )
        return try {
            writeFrame(FrameCodec.encode(message))
            true
        } catch (e: Exception) {
            Log.w(TAG, "sending $type failed: ${e.javaClass.simpleName}: ${e.message}")
            close()
            false
        }
    }

    /** Sends the clipboard content, if the session is up. Returns true if sent. */
    fun sendClipboard(text: String): Boolean {
        if (!ready) return false
        val payload = ClipboardPayloads.build(text)
        val message = ProtocolMessage(
            type = Protocol.TYPE_CLIPBOARD_SET,
            id = UUID.randomUUID().toString(),
            ts = System.currentTimeMillis(),
            payload = ClipboardPayloads.toJson(payload),
        )

        return try {
            writeFrame(FrameCodec.encode(message))
            Log.i(TAG, "sent ${payload.text.length} characters")
            true
        } catch (e: Exception) {
            Log.w(TAG, "sending failed: ${e.javaClass.simpleName}: ${e.message}")
            close()
            false
        }
    }

    fun sendFileStart(id: String, name: String, size: Long, mime: String): Boolean {
        if (!ready) return false
        val stream = output ?: return false
        val payload = org.json.JSONObject()
            .put("id", id)
            .put("name", name)
            .put("size", size)
            .put("mime", mime)
        val message = ProtocolMessage(
            type = Protocol.TYPE_FILE_START,
            id = UUID.randomUUID().toString(),
            ts = System.currentTimeMillis(),
            payload = payload
        )
        return try {
            writeFrame(FrameCodec.encode(message))
            true
        } catch (e: Exception) {
            Log.w(TAG, "failed to send file start: ${e.message}")
            false
        }
    }

    fun sendFileChunk(id: String, chunkIndex: Int, dataBase64: String): Boolean {
        if (!ready) return false
        val stream = output ?: return false
        val payload = org.json.JSONObject()
            .put("id", id)
            .put("chunkIndex", chunkIndex)
            .put("data", dataBase64)
        val message = ProtocolMessage(
            type = Protocol.TYPE_FILE_CHUNK,
            id = UUID.randomUUID().toString(),
            ts = System.currentTimeMillis(),
            payload = payload
        )
        return try {
            writeFrame(FrameCodec.encode(message))
            true
        } catch (e: Exception) {
            Log.w(TAG, "failed to send file chunk: ${e.message}")
            false
        }
    }

    fun sendFileDone(id: String): Boolean {
        if (!ready) return false
        val stream = output ?: return false
        val payload = org.json.JSONObject().put("id", id)
        val message = ProtocolMessage(
            type = Protocol.TYPE_FILE_DONE,
            id = UUID.randomUUID().toString(),
            ts = System.currentTimeMillis(),
            payload = payload
        )
        return try {
            writeFrame(FrameCodec.encode(message))
            true
        } catch (e: Exception) {
            Log.w(TAG, "failed to send file done: ${e.message}")
            false
        }
    }

    private fun readLoop() {
        var reason: String? = null
        try {
            val trust = PeerTrust(pairing?.fingerprint) { fingerprint ->
                onPaired("", fingerprint)
            }
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf(trust), SecureRandom())

            val factory: SSLSocketFactory = context.socketFactory
            val ssl = factory.createSocket() as SSLSocket
            ssl.connect(java.net.InetSocketAddress(host, port), 8000)
            ssl.startHandshake()

            socket = ssl
            input = BufferedInputStream(ssl.inputStream)
            output = BufferedOutputStream(ssl.outputStream)

            val nonce = UUID.randomUUID().toString()

            // The first contact carries the secret, so the computer can
            // remember it. Every later contact carries a proof that only the
            // holder of the secret can produce.
            // The computer is told whether the clipboard can actually sync:
            // only the default keyboard may read the clipboard in the
            // background, so the desktop can say so instead of looking broken.
            val hello = if (sharedSecret.isEmpty()) {
                val secret = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                onSecretCreated(java.util.Base64.getEncoder().encodeToString(secret))
                Handshake.buildHello(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    code = code,
                    nonce = nonce,
                    secret = secret,
                    clipboardAccess = clipboardAccess,
                )
            } else {
                val secretBytes = java.util.Base64.getDecoder().decode(sharedSecret)
                Handshake.buildHello(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    code = code,
                    nonce = nonce,
                    proof = Handshake.proofFor(secretBytes, deviceId, nonce),
                    clipboardAccess = clipboardAccess,
                )
            }
            val stream = output!!
            stream.write(FrameCodec.encode(hello))
            stream.flush()

            val inStream = input!!
            val reply = FrameCodec.readFrom(inStream)
            when (val result = Handshake.verifyHelloAck(reply, pairing, expectedNonce = nonce)) {
                is HandshakeResult.Failed -> {
                    Log.w(TAG, "pairing refused: ${result.reason}")
                    reason = "pairing refused: ${result.reason}"
                    return
                }
                is HandshakeResult.Success -> {
                    Log.i(TAG, "paired with ${result.pcId}")
                    onPaired(result.pcId, result.fingerprint)
                    ready = true
                    onReady()
                }
            }

            while (running) {
                val inStreamLoop = input ?: break
                val message = FrameCodec.readFrom(inStreamLoop)
                lastHeardAt = System.currentTimeMillis()

                if (message.type == Protocol.TYPE_PING) {
                    Log.i(TAG, "ping received")
                    val pong = ProtocolMessage(
                        type = Protocol.TYPE_PONG,
                        id = message.id,
                        ts = System.currentTimeMillis(),
                        payload = org.json.JSONObject(),
                    )
                    try {
                        writeFrame(FrameCodec.encode(pong))
                    } catch (e: Exception) {
                        Log.w(TAG, "could not answer the ping: ${e.message}")
                        close()
                        break
                    }
                    continue
                }

                if (message.type == Protocol.TYPE_FILE_START) {
                    onFileStart(
                        message.payload.optString("id"),
                        message.payload.optString("name"),
                        message.payload.optLong("size"),
                        message.payload.optString("mime"),
                    )
                    continue
                }

                if (message.type == Protocol.TYPE_FILE_CHUNK) {
                    val bytes = java.util.Base64.getDecoder().decode(
                        message.payload.optString("data")
                    )
                    onFileChunk(
                        message.payload.optString("id"),
                        message.payload.optInt("chunkIndex"),
                        bytes,
                    )
                    continue
                }

                if (message.type == Protocol.TYPE_FILE_DONE) {
                    onFileDone(message.payload.optString("id"))
                    continue
                }

                if (message.type == Protocol.TYPE_BYE) {
                    // Not a network drop: the computer hung up on purpose.
                    // The service reports it differently and stops retrying,
                    // so the user's Disconnect on the PC actually sticks.
                    Log.i(TAG, "the computer closed the session")
                    reason = REASON_BYE
                    break
                }
                if (message.type == Protocol.TYPE_CLIPBOARD_SET) {
                    val text = message.payload.optString("text")
                    if (text.isNotEmpty()) {
                        Log.i(TAG, "received ${text.length} characters from computer")
                        onClipboardReceived(text)
                    }
                    continue
                }

                if (message.type == Protocol.TYPE_NOTIFICATION_ACTION) {
                    try {
                        onNotificationAction(
                            message.payload.optString("id"),
                            message.payload.optString("action"),
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "the notification action failed: ${e.message}")
                    }
                    continue
                }

                if (message.type == Protocol.TYPE_FILE_LIST_REQUEST) {
                    try {
                        onFileListRequest(message.payload.optString("path"))
                    } catch (e: Exception) {
                        // Reading the phone's files must never cost the
                        // connection: this runs on the reader thread.
                        Log.w(TAG, "the file list request failed: ${e.message}")
                    }
                    continue
                }

                if (message.type == Protocol.TYPE_FILE_SEND_REQUEST) {
                    try {
                        onFileSendRequest(message.payload.optString("path"))
                    } catch (e: Exception) {
                        Log.w(TAG, "the file send request failed: ${e.message}")
                    }
                    continue
                }

                if (message.type == Protocol.TYPE_FILE_SEARCH_REQUEST) {
                    try {
                        onFileSearchRequest(
                            message.payload.optString("query"),
                            message.payload.optBoolean("fresh", false),
                        )
                    } catch (e: Exception) {
                        // A search over the whole storage is the slowest thing
                        // this channel does; it must never cost the connection.
                        Log.w(TAG, "the search request failed: ${e.message}")
                    }
                    continue
                }

                if (message.type == Protocol.TYPE_MEDIA_COMMAND) {
                    try {
                        onMediaCommand(
                            message.payload.optString("command"),
                            if (message.payload.has("positionMs")) {
                                message.payload.optLong("positionMs")
                            } else {
                                null
                            },
                        )
                    } catch (e: Exception) {
                        // A player that refuses a command must not cost us the
                        // connection: this runs on the reader thread.
                        Log.w(TAG, "the media command failed: ${e.message}")
                    }
                    continue
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.w(TAG, "connection failed: ${e.javaClass.simpleName}: ${e.message}")
                reason = e.message ?: "connection failed"
            } else {
                reason = "closed"
            }
        } finally {
            close()
            onClosed(reason ?: "disconnected")
        }
    }

    companion object {
        const val TAG = "AplusConnect"

        /**
         * Why the link closed when the computer said `bye`.
         *
         * The service treats this reason differently from a dropped network:
         * a drop is retried, a deliberate hang-up is not.
         */
        const val REASON_BYE = "the computer disconnected this phone"

        private const val WATCHDOG_INTERVAL_MS = 10_000L
        private const val PEER_TIMEOUT_MS = 75_000L
    }
}
