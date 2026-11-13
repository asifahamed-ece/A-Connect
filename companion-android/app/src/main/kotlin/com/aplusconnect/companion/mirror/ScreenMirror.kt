package com.aplusconnect.companion.mirror

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import android.view.WindowManager

/**
 * The phone's screen, captured and encoded.
 *
 * `MediaProjection` renders the real display into a virtual one, whose surface
 * is the encoder's input. The phone is not taken over: its own display keeps
 * working, and this is a duplicate of it.
 *
 * H.264 rather than the HEVC encoder `scrcpy.js` uses, because the desktop
 * cannot decode HEVC - verified by probing the running Electron build, not
 * assumed.
 *
 * Every way this can end - the user's Stop, the system status-bar chip, the
 * screen locking, the app dying - arrives at [stop], which is idempotent.
 */
class ScreenMirror(
    private val context: Context,
    private val onFrame: (payload: ByteArray, keyframe: Boolean, ptsMicros: Long) -> Unit,
    private val onStopped: (reason: String) -> Unit,
) {

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var drain: Thread? = null

    @Volatile
    private var running = false

    private var size = MirrorConfig.Size(2, 2)

    /** The encoder's own name, for the log and for the desktop's status line. */
    var encoderName: String = ""
        private set

    val width: Int
        get() = size.width

    val height: Int
        get() = size.height

    /**
     * The projection callback has to be registered before the virtual display
     * exists, or `createVirtualDisplay` throws (Android 14 and later). It is
     * also how the status-bar chip and a locked screen reach us.
     */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "the system stopped the projection")
            stop("projection-stopped")
        }
    }

    /**
     * Starts capturing. Returns false when this phone has no usable encoder,
     * which is a real possibility and not worth pretending about.
     */
    fun start(
        granted: MediaProjection,
        fps: Int = MirrorConfig.DEFAULT_FPS,
        bitrate: Int = MirrorConfig.DEFAULT_BITRATE,
        keyframeSeconds: Int = MirrorConfig.DEFAULT_KEYFRAME_SECONDS,
        maxLongSide: Int = MirrorConfig.DEFAULT_MAX_LONG_SIDE,
    ): Boolean {
        if (running) return false

        val screen = screenSize()
        size = MirrorConfig.fit(screen.x, screen.y, maxLongSide)

        val encoder = chooseEncoder()
        if (encoder == null) {
            Log.w(TAG, "this phone has no H.264 encoder")
            return false
        }

        return try {
            val format = MediaFormat.createVideoFormat(MIME, size.width, size.height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeSeconds)
                // Every keyframe carries its own SPS/PPS, so the desktop needs
                // no out-of-band extradata and a late joiner starts cleanly.
                setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LATENCY, 0)
                }
                // VBR only where the encoder admits to supporting it.
                val supportsVbr = try {
                    encoderInfo(encoder.name)?.let { capabilitiesFor(it, MIME) }
                        ?.let { caps ->
                            caps.encoderCapabilities?.isBitrateModeSupported(
                                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                            ) == true
                        } ?: false
                } catch (e: Exception) {
                    false
                }
                if (supportsVbr) {
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                    )
                }
            }

            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            encoder.start()

            codec = encoder
            encoderName = encoder.name
            projection = granted

            // Registered before the display exists: required on Android 14+.
            granted.registerCallback(projectionCallback, null)

            display = granted.createVirtualDisplay(
                "aplus-connect-mirror",
                size.width,
                size.height,
                context.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null,
            )

            running = true
            startDraining()
            Log.i(
                TAG,
                "mirroring ${size.width}x${size.height} with ${encoder.name} at $bitrate bit/s",
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not start mirroring: ${e.javaClass.simpleName}: ${e.message}")
            release()
            false
        }
    }

    /**
     * Ends the session. Safe to call from any thread, any number of times, and
     * from inside the projection callback.
     */
    fun stop(reason: String) {
        if (!running && codec == null) return
        running = false
        release()
        Log.i(TAG, "capture released: $reason")
        onStopped(reason)
    }

    private fun release() {
        drain?.interrupt()
        drain = null

        try {
            codec?.stop()
        } catch (e: Exception) {
            // A codec that was never started, or already stopped.
        }
        try {
            codec?.release()
        } catch (e: Exception) {
        }
        codec = null

        try {
            display?.release()
        } catch (e: Exception) {
        }
        display = null

        try {
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        } catch (e: Exception) {
        }
        projection = null
    }

    /** Pulls encoded frames out, as fast as the encoder produces them. */
    private fun startDraining() {
        drain = Thread({
            val info = MediaCodec.BufferInfo()
            while (running) {
                val active = codec ?: break
                val index = try {
                    active.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US)
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "the encoder stopped: ${e.message}")
                    break
                }

                if (index < 0) continue
                if (info.size <= 0) {
                    active.releaseOutputBuffer(index, false)
                    continue
                }

                try {
                    val buffer = active.getOutputBuffer(index)
                    if (buffer != null) {
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.get(bytes)
                        val keyframe =
                            (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 ||
                                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        onFrame(bytes, keyframe, info.presentationTimeUs)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "could not read an encoded frame: ${e.message}")
                } finally {
                    try {
                        active.releaseOutputBuffer(index, false)
                    } catch (e: Exception) {
                    }
                }
            }
        }, "companion-mirror-drain").apply {
            isDaemon = true
            start()
        }
    }

    /** The real display's size, which the virtual display is fitted to. */
    private fun screenSize(): Point {
        val manager = context.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && manager != null) {
            val bounds = manager.maximumWindowMetrics.bounds
            return Point(bounds.width(), bounds.height())
        }
        @Suppress("DEPRECATION")
        val metrics = android.util.DisplayMetrics().also {
            manager?.defaultDisplay?.getRealMetrics(it)
        }
        return Point(metrics.widthPixels, metrics.heightPixels)
    }

    /**
     * A hardware H.264 encoder, else any H.264 encoder.
     *
     * Chosen from the codec list rather than by name: `scrcpy.js` hardcodes a
     * MediaTek HEVC encoder, which is exactly the kind of thing that works on
     * one phone and fails on the next.
     */
    private fun chooseEncoder(): MediaCodec? {
        return try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            var software: MediaCodecInfo? = null
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                if (!info.supportedTypes.any { it.equals(MIME, ignoreCase = true) }) continue
                val hardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isHardwareAccelerated
                if (hardware) return MediaCodec.createByCodecName(info.name)
                if (software == null) software = info
            }
            software?.let { MediaCodec.createByCodecName(it.name) }
        } catch (e: Exception) {
            Log.w(TAG, "could not pick an encoder: ${e.message}")
            try {
                MediaCodec.createEncoderByType(MIME)
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun encoderInfo(name: String): MediaCodecInfo? =
        try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { it.name == name }
        } catch (e: Exception) {
            null
        }

    private fun capabilitiesFor(info: MediaCodecInfo, mime: String): MediaCodecInfo.CodecCapabilities? =
        try {
            info.getCapabilitiesForType(mime)
        } catch (e: Exception) {
            null
        }

    companion object {
        private const val TAG = "AplusConnect"

        /** The one codec the desktop can decode. */
        private const val MIME = "video/avc"

        private const val DRAIN_TIMEOUT_US = 10_000L
    }
}