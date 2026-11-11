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
) {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var reader: Thread? = null

    @Volatile
    private var running = false

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
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "closing the socket failed: ${e.message}")
        }
        socket = null
    }

    /** Sends the clipboard content, if the session is up. */
    fun sendClipboard(text: String) {
        val stream = output ?: return
        val payload = ClipboardPayloads.build(text)
        val message = ProtocolMessage(
            type = Protocol.TYPE_CLIPBOARD_SET,
            id = UUID.randomUUID().toString(),
            ts = System.currentTimeMillis(),
            payload = ClipboardPayloads.toJson(payload),
        )

        try {
            synchronized(stream) {
                stream.write(FrameCodec.encode(message))
                stream.flush()
            }
            Log.i(TAG, "sent ${payload.text.length} characters")
        } catch (e: Exception) {
            Log.w(TAG, "sending failed: ${e.message}")
            close()
        }
    }

    private fun readLoop() {
        var established = false
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

            val reply = FrameCodec.readFrom(input!!)
            when (val result = Handshake.verifyHelloAck(reply, pairing, expectedNonce = nonce)) {
                is HandshakeResult.Failed -> {
                    Log.w(TAG, "pairing refused: ${result.reason}")
                    onClosed("pairing refused: ${result.reason}")
                    close()
                    return
                }
                is HandshakeResult.Success -> {
                    Log.i(TAG, "paired with ${result.pcId}")
                    onPaired(result.pcId, result.fingerprint)
                    established = true
                    onReady()
                }
            }

            while (running) {
                val message = FrameCodec.readFrom(input!!)
                if (message.type == Protocol.TYPE_BYE) {
                    Log.i(TAG, "the computer closed the session")
                    break
                }
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "connection failed: ${e.message}")
        } finally {
            close()
            if (!established) onClosed("not connected")
        }
    }

    companion object {
        const val TAG = "AplusConnect"
    }
}
