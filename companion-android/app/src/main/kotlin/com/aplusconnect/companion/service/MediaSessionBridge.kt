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

        /**
     * Where publishing and the commands run.
     *
     * The session listener and the session callbacks are delivered on the
     * main thread, and a socket write there throws NetworkOnMainThreadException
     * and drops the connection - which is exactly what a seek from the
     * computer used to do. Everything that touches the socket goes through
     * this thread instead.
     */
    private val work = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "companion-media-work").apply { isDaemon = true }
    }

    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null

    /** The track whose cover was already delivered; the cover goes once per track. */
    private var lastCoverKey: String? = null

    /** The track whose cover is in hand, waiting to be delivered. */
    private var pendingCoverKey: String? = null

    /**
     * The track whose placeholder (the playing app's own icon) was sent.
     *
     * Kept apart from [lastCoverKey] on purpose: a placeholder must not count
     * as the track's cover, or the real artwork would never replace it.
     */
    private var lastPlaceholderKey: String? = null

    /** When nothing worth showing started, and whether we already said so. */
    private var quietSince = 0L
    private var idlePublished = false
    private var lastRescanAt = 0L

    /** Where we last told the session to seek, until the session agrees. */
    private var seekTargetMs: Long? = null
    private var seekTargetAt = 0L

    /** When the last cover attempt was made, so a miss is not retried every second. */
    private var lastCoverAttemptAt = 0L

    @Volatile
    private var running = false
    private var ticker: Thread? = null

    private val sessionsChanged =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            adopt(choose(controllers))
        }

    /**
     * Publish as soon as the session itself changes, and re-choose what to
     * follow: a player that pauses hands over to whatever else is playing.
     */
    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            rescan()
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
            adopt(choose(sessions.getActiveSessions(listener)))
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
        work.shutdown()
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
    private fun choose(controllers: List<MediaController>?): MediaController? {
        if (controllers.isNullOrEmpty()) return null
        val index = chooseIndex(controllers.map { it.playbackState?.state })
        return index?.let { controllers[it] }
    }

    /**
     * Looks at the active sessions again and follows the right one.
     *
     * The system only announces a *change to the set* of sessions. A player
     * that was already active and merely starts playing - which is exactly what
     * an in-app YouTube video does - changes its own state and nothing else, so
     * without this the phone would only notice at the next app restart.
     */
    private fun rescan() {
        val sessions = manager ?: return
        lastRescanAt = System.currentTimeMillis()
        val active = try {
            sessions.getActiveSessions(listener)
        } catch (e: Exception) {
            Log.w(TAG, "could not list the media sessions: ${e.message}")
            return
        }
        adopt(choose(active))
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
            position = currentPositionMs(playback),
            duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
        )
    }

    /**
     * Where the track actually is.
     *
     * A session refreshes its position only now and then, so it is advanced by
     * the time since the session last published its state. And after a seek a
     * session can go on reporting a stale zero for a while, so the target we
     * seeked to is used until the session catches up with it.
     */
    private fun currentPositionMs(playback: PlaybackState?): Long {
        if (playback == null) return 0
        val now = android.os.SystemClock.elapsedRealtime()
        val sessionPosition = playback.position

        var position = sessionPosition
        val updatedAt = playback.lastPositionUpdateTime
        if (playback.state == PlaybackState.STATE_PLAYING && updatedAt in 1..now) {
            position = sessionPosition + (now - updatedAt)
        }

        val target = seekTargetMs
        if (target != null) {
            val ours = target + (now - seekTargetAt)
            if (now - seekTargetAt < SEEK_WINDOW_MS && position < ours) {
                // The session has not caught up with the seek yet: a session
                // often reports a reset position for a moment after one, and
                // publishing that would send the shell's clock back to zero.
                position = ours
            } else {
                seekTargetMs = null
            }
        }
        return position
    }

    /** Sends the current state, when there is one worth sending. */
    fun publish() {
        // Never on the caller's thread: the callers are the main thread.
        work.execute { publishNow() }
    }

    private fun publishNow() {
        val state = currentState() ?: return
        val art = encodeArt()
        val payload = FeaturePayloads.mediaState(
            app = state.app,
            title = state.title,
            artist = state.artist,
            album = state.album,
            playing = state.playing,
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            art = art?.base64,
        )

        val sent = send(payload)
        // Only a cover that actually reached the computer counts as delivered.
        // Marking it sent while the computer was still connecting would leave
        // that track without art for its whole length. A placeholder does not
        // count: real art must still be able to replace it.
        if (sent && art != null && art.real) {
            lastCoverKey = pendingCoverKey
            Log.i(TAG, "sent the cover for $pendingCoverKey")
        }
    }

    /**
     * Tells the computer that nothing is playing any more.
     *
     * The phone used to send only states that had something in them, so after
     * the user stopped a video the shell's media panel kept the last track,
     * its cover and its clock for ever.
     */
    private fun publishIdleNow() {
        val payload = FeaturePayloads.mediaState(
            app = "",
            title = "",
            artist = "",
            album = "",
            playing = false,
            positionMs = 0,
            durationMs = 0,
            idle = true,
        )
        if (send(payload)) {
            // Whatever plays next is a new panel, cover included.
            lastCoverKey = null
            pendingCoverKey = null
            lastPlaceholderKey = null
        }
    }

    /** Artwork ready to send, and whether it is the track's own. */
    private class Art(val base64: String, val real: Boolean)

    /**
     * The cover as base64 JPEG, or null when there is nothing new to send.
     *
     * Three cases, in this order:
     *  - the session has artwork: send it, once per track;
     *  - it has none yet: send the playing app's own icon as a placeholder, so
     *    the shell shows the player instead of an empty box. The placeholder is
     *    not marked as the track's cover, so real artwork still replaces it;
     *  - nothing new: send nothing.
     */
    private fun encodeArt(): Art? {
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

        val artwork = coverBitmap(metadata)
        if (artwork != null) {
            pendingCoverKey = key
            return encodeJpeg(artwork)?.let { Art(it, real = true) }
        }

        if (key != lastPlaceholderKey) {
            val icon = appIcon(controller?.packageName.orEmpty())
            if (icon != null) {
                Log.i(TAG, "no cover for this track; using the app's icon")
                lastPlaceholderKey = key
                return encodeJpeg(icon)?.let { Art(it, real = false) }
            }
        }
        return null
    }

    /** Scales a bitmap down to a frame-friendly JPEG, as base64. */
    private fun encodeJpeg(bitmap: android.graphics.Bitmap): String? = try {
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
        android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    } catch (e: Exception) {
        Log.w(TAG, "could not encode the cover: ${e.message}")
        null
    }

    /**
     * The artwork of the session, however the player offers it.
     *
     * There is no single key every player uses: YouTube Music hands over a
     * content URI and no bitmap, some players fill ALBUM_ART, and others only
     * describe the item through a MediaDescription. All of them are tried
     * before giving up and falling back to the app's icon.
     */
    private fun coverBitmap(metadata: MediaMetadata): android.graphics.Bitmap? {
        val bitmapKeys = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON,
        )
        for (key in bitmapKeys) {
            metadata.getBitmap(key)?.let { return it }
        }

        val uriKeys = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        )
        for (key in uriKeys) {
            val uri = metadata.getString(key) ?: continue
            decodeUri(uri)?.let { return it }
        }

        // A session that describes its item as a MediaDescription may carry the
        // artwork only there.
        metadata.description?.let { description ->
            description.iconBitmap?.let { return it }
            description.iconUri?.let { uri -> decodeUri(uri.toString())?.let { return it } }
        }
        return null
    }

    private fun decodeUri(uri: String): android.graphics.Bitmap? = try {
        context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { stream ->
            android.graphics.BitmapFactory.decodeStream(stream)
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not read the cover from $uri: ${e.message}")
        null
    }

    /**
     * The playing app's icon, drawn into a bitmap.
     *
     * The last resort for artwork: a player with no cover at all still has an
     * icon, and the shell's panel looks broken with an empty square.
     */
    private fun appIcon(pkg: String): android.graphics.Bitmap? {
        if (pkg.isEmpty()) return null
        return try {
            val drawable = context.packageManager.getApplicationIcon(pkg)
            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: FALLBACK_ICON_PX
            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: FALLBACK_ICON_PX
            val bitmap = android.graphics.Bitmap.createBitmap(
                width,
                height,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "could not read the icon of $pkg: ${e.message}")
            null
        }
    }

    /**
     * Runs one command against the session the state was produced for. An
     * unknown command, or a session that has gone away, is ignored.
     */
    fun command(command: String, positionMs: Long?) {
        // A transport control call is a binder call into the player, which can
        // take a while. Doing it on the reader thread would stop the phone
        // answering the computer's pings, and the computer would drop the link.
        work.execute { commandNow(command, positionMs) }
    }

    private fun commandNow(command: String, positionMs: Long?) {
        val controls = try {
            controller?.transportControls
        } catch (e: Exception) {
            Log.w(TAG, "could not reach the session: ${e.message}")
            return
        } ?: return
        try {
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
                "seek" -> if (positionMs != null) {
                    // The session may report a stale position for a moment
                    // after a seek, so remember where we sent it.
                    seekTargetMs = positionMs
                    seekTargetAt = android.os.SystemClock.elapsedRealtime()
                    controls.seekTo(positionMs)
                } else {
                    Log.i(TAG, "ignoring a seek without a position")
                }
            }
        } catch (e: Exception) {
            // A player that refuses a seek must not cost us the connection.
            Log.w(TAG, "the media command $command failed: ${e.message}")
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

                if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    // The position only moves while playing, so only then is a
                    // per-second update worth the traffic.
                    quietSince = 0L
                    idlePublished = false
                    publish()
                } else {
                    // Nothing of ours is playing. Another app may have just
                    // started one, and the system does not announce that when
                    // the session was already active - which is exactly how an
                    // in-app YouTube video used to be missed.
                    if (System.currentTimeMillis() - lastRescanAt >= IDLE_RESCAN_MS) rescan()
                    settleQuietly()
                }
            }
        }, "companion-media").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * After a moment of nothing playing, tells the computer so.
     *
     * The grace period is what stops a track change, or a pause followed at
     * once by another app, from making the shell's panel flicker away and back.
     */
    private fun settleQuietly() {
        if (worthShowing()) {
            quietSince = 0L
            idlePublished = false
            return
        }
        val now = System.currentTimeMillis()
        if (quietSince == 0L) {
            quietSince = now
            return
        }
        if (idlePublished || now - quietSince < IDLE_GRACE_MS) return
        idlePublished = true
        work.execute { publishIdleNow() }
    }

    /** Whether there is a track worth drawing: playing or paused, with text. */
    private fun worthShowing(): Boolean {
        val current = controller ?: return false
        return isWorthShowing(current.playbackState?.state, currentState() != null)
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

        /** How long after a seek the position we asked for is trusted. */
        private const val SEEK_WINDOW_MS = 10_000L

        /** How often the active sessions are looked at again while none plays. */
        private const val IDLE_RESCAN_MS = 2_000L

        /** How long nothing playing must last before the computer is told. */
        private const val IDLE_GRACE_MS = 3_000L

        /** Size used when a package reports no icon size of its own. */
        private const val FALLBACK_ICON_PX = 192

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

        /**
         * Which of the active sessions to follow, by position in the list.
         *
         * A playing session always beats a paused one, whatever order the
         * system hands them over in. The old rule took the first session that
         * was playing *or* paused, so a paused player listed first kept the
         * panel while a video was actually playing.
         */
        fun chooseIndex(states: List<Int?>): Int? {
            states.indexOfFirst { it == PlaybackState.STATE_PLAYING }
                .takeIf { it >= 0 }
                ?.let { return it }
            states.indexOfFirst { it == PlaybackState.STATE_PAUSED }
                .takeIf { it >= 0 }
                ?.let { return it }
            return null
        }

        /** Whether a session has a track worth drawing. */
        fun isWorthShowing(playbackState: Int?, hasText: Boolean): Boolean =
            hasText &&
                (playbackState == PlaybackState.STATE_PLAYING ||
                    playbackState == PlaybackState.STATE_PAUSED)
    }
}