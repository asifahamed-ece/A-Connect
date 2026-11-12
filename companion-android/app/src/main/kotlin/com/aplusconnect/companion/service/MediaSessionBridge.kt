package com.aplusconnect.companion.service

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import com.aplusconnect.companion.protocol.FeaturePayloads
import org.json.JSONObject

/**
 * What the phone is playing, and the controls for it.
 *
 * Reading the active sessions needs notification access, because Android hands
 * the session list only to a NotificationListenerService component. Without
 * that grant `start()` does nothing and the feature stays absent; the rest of
 * the app is unaffected.
 *
 * Every command goes to the session token captured when the state was produced.
 * The token changes when the user switches apps, so resolving "the current
 * session" at command time would pause whatever is playing now - not what the
 * user clicked. A token that has gone away means the command is dropped.
 */
class MediaSessionBridge(
    private val context: Context,
    private val listener: ComponentName,
    private val send: (JSONObject) -> Boolean,
) {
    data class MediaState(
        val app: String,
        val title: String,
        val artist: String,
        val album: String,
        val playing: Boolean,
        val positionMs: Long,
        val durationMs: Long,
    )

    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null

    /** The track whose cover was already delivered; the cover goes once per track. */
    private var lastCoverKey: String? = null

    /** The track whose cover is in hand, waiting to be delivered. */
    private var pendingCoverKey: String? = null

    /** When the last cover attempt was made, so a miss is not retried every second. */
    private var lastCoverAttemptAt = 0L

    @Volatile
    private var running = false
    private var ticker: Thread? = null

    private val sessionsChanged =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            adopt(controllers?.firstOrNull { isInteresting(it) })
        }

    /**
     * Publish as soon as the session itself changes.
     *
     * OnActiveSessionsChanged only fires when the *set* of sessions changes, so
     * a pause or a skip within the same session would otherwise be invisible
     * until the next tick - and the tick only runs while playing. A shell that
     * paused the phone would keep saying "Playing".
     */
    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            publish()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            publish()
        }
    }

    fun start() {
        if (running) return
        val sessions = context.getSystemService(MediaSessionManager::class.java) ?: return

        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener)
            manager = sessions
            running = true
            adopt(sessions.getActiveSessions(listener).firstOrNull { isInteresting(it) })
        } catch (e: SecurityException) {
            // No notification access: this feature simply is not available.
            Log.i(TAG, "no notification access; playback control stays off")
            return
        } catch (e: Exception) {
            Log.w(TAG, "could not read the media sessions: ${e.message}")
            return
        }

        startTicker()
    }

    fun stop() {
        running = false
        ticker?.interrupt()
        ticker = null
        try {
            controller?.unregisterCallback(controllerCallback)
        } catch (e: Exception) {
            Log.w(TAG, "could not stop watching the session: ${e.message}")
        }
        try {
            manager?.removeOnActiveSessionsChangedListener(sessionsChanged)
        } catch (e: Exception) {
            Log.w(TAG, "could not stop watching the media sessions: ${e.message}")
        }
        manager = null
        controller = null
        lastCoverKey = null
    }

    /** A session worth showing: it is playing, or it is paused mid-track. */
    private fun isInteresting(c: MediaController): Boolean {
        val state = c.playbackState?.state
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED
    }

    private fun adopt(next: MediaController?) {
        if (next !== controller) {
            try {
                controller?.unregisterCallback(controllerCallback)
            } catch (e: Exception) {
                Log.w(TAG, "could not stop watching the old session: ${e.message}")
            }
            controller = next
            try {
                next?.registerCallback(controllerCallback)
            } catch (e: Exception) {
                Log.w(TAG, "could not watch the new session: ${e.message}")
            }
        }
        publish()
    }

    /** The state right now, or null when nothing worth showing is playing. */
    fun currentState(): MediaState? {
        val c = controller ?: return null
        val metadata = c.metadata ?: return null
        val playback = c.playbackState

        return toMediaState(
            app = label(c.packageName),
            title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            playing = playback?.state == PlaybackState.STATE_PLAYING,
            position = playback?.position ?: 0L,
            duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
        )
    }

    /** Sends the current state, when there is one worth sending. */
    fun publish() {
        val state = currentState() ?: return
        val art = encodeCover()
        val payload = FeaturePayloads.mediaState(
            app = state.app,
            title = state.title,
            artist = state.artist,
            album = state.album,
            playing = state.playing,
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            art = art,
        )

        val sent = send(payload)
        // Only a cover that actually reached the computer counts as delivered.
        // Marking it sent while the computer was still connecting would leave
        // that track without art for its whole length.
        if (sent && art != null) {
            lastCoverKey = pendingCoverKey
            Log.i(TAG, "sent the cover for $pendingCoverKey")
        }
    }

    /**
     * The cover as base64 JPEG, or null when there is none, it is unchanged,
     * or it was tried recently and is not there yet.
     *
     * The shell asks for `mpris:artUrl`, which the computer serves from this.
     * It goes once per track, not once per position update.
     */
    private fun encodeCover(): String? {
        val metadata = controller?.metadata ?: return null
        val key = "${metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()}|" +
            "${metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()}|" +
            "${metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()}"

        if (key == lastCoverKey) return null

        // A player often attaches the cover a moment after the track starts,
        // so a miss is not final. Try again - but not every second.
        val now = System.currentTimeMillis()
        if (now - lastCoverAttemptAt < COVER_RETRY_MS) return null
        lastCoverAttemptAt = now

        val bitmap = coverBitmap(metadata)
        if (bitmap == null) {
            Log.i(TAG, "no cover for this track yet")
            return null
        }

        return try {
            val largest = maxOf(bitmap.width, bitmap.height)
            val scaled = if (largest > COVER_MAX_PX) {
                val ratio = COVER_MAX_PX.toFloat() / largest
                android.graphics.Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, COVER_QUALITY, out)
            pendingCoverKey = key
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "could not encode the cover: ${e.message}")
            null
        }
    }

    /**
     * The cover bitmap, however the session offers it.
     *
     * Some players hand over the decoded bitmap, others only a content URI -
     * YouTube Music does the latter - so both are tried before giving up.
     */
    private fun coverBitmap(metadata: MediaMetadata): android.graphics.Bitmap? {
        metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { return it }
        metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let { return it }

        val uri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: return null

        return try {
            context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not read the cover from $uri: ${e.message}")
            null
        }
    }

    /**
     * Runs one command against the session the state was produced for. An
     * unknown command, or a session that has gone away, is ignored.
     */
    fun command(command: String, positionMs: Long?) {
        val controls = controller?.transportControls ?: return
        when (command) {
            "play" -> controls.play()
            "pause" -> controls.pause()
            "toggle" -> if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                controls.pause()
            } else {
                controls.play()
            }
            "next" -> controls.skipToNext()
            "previous" -> controls.skipToPrevious()
            "stop" -> controls.stop()
            "seek" -> if (positionMs != null) controls.seekTo(positionMs)
            else -> Log.i(TAG, "ignoring unknown media command: $command")
        }
    }

    private fun startTicker() {
        if (ticker != null) return
        ticker = Thread({
            while (running) {
                try {
                    Thread.sleep(TICK_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                if (!running) return@Thread
                // The position only moves while playing, so only then is a
                // per-second update worth the traffic.
                if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) publish()
            }
        }, "companion-media").apply {
            isDaemon = true
            start()
        }
    }

    private fun label(pkg: String): String = try {
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (e: Exception) {
        pkg
    }

    companion object {
        private const val TAG = "AplusConnect"
        private const val TICK_MS = 1000L

        /** Covers are sent at most this wide/tall, to keep the frame small. */
        private const val COVER_MAX_PX = 512
        private const val COVER_QUALITY = 85

        /** How often a cover that is not there yet is looked for again. */
        private const val COVER_RETRY_MS = 10_000L

        /**
         * The pure mapping, so it can be tested without a device. A session
         * that describes no track maps to null rather than to an empty card.
         */
        fun toMediaState(
            app: String,
            title: String,
            artist: String,
            album: String,
            playing: Boolean,
            position: Long,
            duration: Long,
        ): MediaState? {
            if (title.isBlank() && artist.isBlank() && album.isBlank()) return null
            return MediaState(
                app = app,
                title = title,
                artist = artist,
                album = album,
                playing = playing,
                positionMs = position,
                durationMs = duration,
            )
        }
    }
}