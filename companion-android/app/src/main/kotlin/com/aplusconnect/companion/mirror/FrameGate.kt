package com.aplusconnect.companion.mirror

/**
 * Decides what to do with a frame the encoder just produced.
 *
 * The link is TCP, so it delivers reliably - which is the wrong promise for
 * live video. If a frame cannot go out right now, queueing it is how the delay
 * grows: the viewer ends up watching a smooth, perfectly ordered recording of
 * what happened ten seconds ago. Dropping is how the picture stays live.
 *
 * Two rules, and both matter:
 *
 *  - At most one frame waits. A newer frame replaces it, because the older one
 *    is stale news by definition.
 *  - A keyframe is never displaced by a delta frame, and never dropped while
 *    one could be sent: without a keyframe the decoder cannot resynchronise at
 *    all. A keyframe *does* displace a waiting delta frame.
 *
 * Pure, so this is all testable without a socket.
 */
class FrameGate {

    enum class Action { SEND, HOLD, DROP }

    private var inFlight = false
    private var hasWaiting = false
    private var waitingIsKeyframe = false

    /** Frames thrown away because a newer one took their place. */
    var dropped: Int = 0
        private set

    /** Whether a frame is out on the wire right now. */
    val busy: Boolean
        get() = inFlight

    fun offer(keyframe: Boolean): Action = when {
        !inFlight -> {
            inFlight = true
            Action.SEND
        }

        !hasWaiting -> {
            hasWaiting = true
            waitingIsKeyframe = keyframe
            Action.HOLD
        }

        // The waiting frame is older, so anything newer takes its place -
        // except that a delta frame may not push out a waiting keyframe,
        // because the decoder cannot resynchronise without one.
        keyframe || !waitingIsKeyframe -> {
            dropped += 1
            waitingIsKeyframe = keyframe
            Action.HOLD
        }

        else -> {
            dropped += 1
            Action.DROP
        }
    }

    /**
     * The frame that was sent has finished writing.
     *
     * True means there is a waiting frame to send now - the caller keeps the
     * bytes, so it knows which one. The gate stays busy in that case, because
     * the link is not idle until the waiting frame is gone too.
     */
    fun onWritten(): Boolean {
        if (hasWaiting) {
            hasWaiting = false
            waitingIsKeyframe = false
            return true
        }
        inFlight = false
        return false
    }

    /** The session ended: forget any half-finished state. */
    fun reset() {
        inFlight = false
        hasWaiting = false
        waitingIsKeyframe = false
    }
}
