package com.aplusconnect.companion.net

import android.util.Log
import com.aplusconnect.companion.protocol.ClipboardPayloads
import com.aplusconnect.companion.protocol.FrameCodec
import com.aplusconnect.companion.protocol.Handshake
import com.aplusconnect.companion.protocol.HandshakeResult
import com.aplusconnect.companion.protocol.Pairing
import com.aplusconnect.companion.protocol.Protocol
import com.aplusconnect.companion.protocol.ProtocolMessage
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
    private val onPaired: (pcId: String, fingerprint: String) -> Unit,
    private val onReady: () -> Unit,
    private val onClosed: (String) -> Unit,
    private val onClipboardReceived: (String) -> Unit = {},
) {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var reader: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var ready = false

    val isReady: Boolean
        get() = ready

    fun connect() {
        if (running) return
        running = true

        reader = Thread({ readLoop() }, "companion-client").apply {
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
            Log.w(TAG, "closing the socket failed: ${e.message}")
        }
        socket = null
        input = null
        output = null
    }

    /** Sends the clipboard content, if the session is up. Returns true if sent. */
    fun sendClipboard(text: String): Boolean {
        if (!ready) return false
        val stream = output ?: return false
        val payload = ClipboardPayloads.build(text)
        val message = ProtocolMessage(
            type = Protocol.TYPE_CLIPBOARD_SET,
            id = UUID.randomUUID().toString(),
            ts = System.currentTimeMillis(),
            payload = ClipboardPayloads.toJson(payload),
        )

        return try {
            synchronized(stream) {
                stream.write(FrameCodec.encode(message))
                stream.flush()
            }
            Log.i(TAG, "sent ${payload.text.length} characters")
            true
        } catch (e: Exception) {
            Log.w(TAG, "sending failed: ${e.message}")
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
            synchronized(stream) {
                stream.write(FrameCodec.encode(message))
                stream.flush()
            }
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
            synchronized(stream) {
                stream.write(FrameCodec.encode(message))
                stream.flush()
            }
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
            synchronized(stream) {
                stream.write(FrameCodec.encode(message))
                stream.flush()
            }
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
            val hello = Handshake.buildHello(deviceId, deviceName, code, nonce)
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
                if (message.type == Protocol.TYPE_BYE) {
                    Log.i(TAG, "the computer closed the session")
                    reason = "the computer closed the session"
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
            }
        } catch (e: Exception) {
            if (running) {
                Log.w(TAG, "connection failed: ${e.message}")
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
    }
}
