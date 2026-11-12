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
    private val send: (JSONObject) -> Unit,
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

    @Volatile
    private var running = false
    private var ticker: Thread? = null

    private val sessionsChanged =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            adopt(controllers?.firstOrNull { isInteresting(it) })
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
            manager?.removeOnActiveSessionsChangedListener(sessionsChanged)
        } catch (e: Exception) {
            Log.w(TAG, "could not stop watching the media sessions: ${e.message}")
        }
        manager = null
        controller = null
    }

    /** A session worth showing: it is playing, or it is paused mid-track. */
    private fun isInteresting(c: MediaController): Boolean {
        val state = c.playbackState?.state
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED
    }

    private fun adopt(next: MediaController?) {
        controller = next
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
        send(
            FeaturePayloads.mediaState(
                app = state.app,
                title = state.title,
                artist = state.artist,
                album = state.album,
                playing = state.playing,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
            )
        )
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