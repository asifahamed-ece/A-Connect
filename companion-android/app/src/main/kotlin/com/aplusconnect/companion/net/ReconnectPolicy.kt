package com.aplusconnect.companion.net

/**
 * How often the phone dials the computer, and when it is allowed to hurry.
 *
 * The interesting part is telling two things apart that look identical from
 * the phone's side:
 *
 *  - The computer hung up on purpose (the Disconnect button). The phone must
 *    not undo that within seconds, so it stops until the user acts.
 *  - The computer is switched off (the kill switch). The phone should keep
 *    looking, because the user will switch it back on and expect the link to
 *    come back on its own - but slowly, so a computer that stays off overnight
 *    is not dialled every five seconds until the battery is gone.
 *
 * A third case is the phone that never heard the computer go off: it was
 * asleep, or the network was down. All it sees is a refused connection, which
 * it cannot tell from the second case. After a few refusals in a row it
 * assumes the same thing - the computer is not there - and slows down. The
 * first success clears the assumption.
 *
 * Pure, so the policy can be tested without a phone or a network.
 */
class ReconnectPolicy(
    private val fastDelayMs: Long = FAST_DELAY_MS,
    private val slowDelayMs: Long = SLOW_DELAY_MS,
    private val failuresBeforeSlow: Int = FAILURES_BEFORE_SLOW,
) {

    /** True while the computer is believed to be switched off. */
    var dormant: Boolean = false
        private set

    /** Failed attempts in a row, since the last success. */
    var failures: Int = 0
        private set

    /** The computer said it is going dormant. */
    fun onDormant() {
        dormant = true
        failures = 0
    }

    /** A dial attempt failed. The computer may simply not be there. */
    fun onFailure() {
        failures += 1
        if (failures >= failuresBeforeSlow) dormant = true
    }

    /** The link is up: the computer is there, whatever we assumed before. */
    fun onConnected() {
        dormant = false
        failures = 0
    }

    /** How long to wait before the next attempt. */
    fun delayMs(): Long = if (dormant) slowDelayMs else fastDelayMs

    companion object {
        /** The ordinary pace: a dropped link comes back in five seconds. */
        const val FAST_DELAY_MS = 5_000L

        /** The dormant pace: the computer may stay off for hours. */
        const val SLOW_DELAY_MS = 30_000L

        /** How many refusals in a row before the phone assumes dormancy. */
        const val FAILURES_BEFORE_SLOW = 3
    }
}
