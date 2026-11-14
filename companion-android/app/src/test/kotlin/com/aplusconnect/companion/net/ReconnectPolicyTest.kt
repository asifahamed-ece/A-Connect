package com.aplusconnect.companion.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dial policy.
 *
 * The whole point of the policy is telling two things apart that look the
 * same from the phone's side: a computer that hung up on purpose (stop until
 * the user acts) and a computer that is switched off (keep looking, slowly).
 * Getting it wrong means either the Disconnect button does nothing, or a
 * switched-off computer is dialled every five seconds until the battery is
 * gone.
 */
class ReconnectPolicyTest {

    @Test
    fun `a dropped link is retried quickly`() {
        val policy = ReconnectPolicy()
        assertFalse(policy.dormant)
        assertEquals(ReconnectPolicy.FAST_DELAY_MS, policy.delayMs())
    }

    @Test
    fun `a computer that says it is off is looked for slowly`() {
        val policy = ReconnectPolicy()
        policy.onDormant()

        assertTrue(policy.dormant)
        assertEquals(ReconnectPolicy.SLOW_DELAY_MS, policy.delayMs())
    }

    @Test
    fun `a success clears the assumption`() {
        val policy = ReconnectPolicy()
        policy.onDormant()
        policy.onConnected()

        assertFalse(policy.dormant)
        assertEquals(ReconnectPolicy.FAST_DELAY_MS, policy.delayMs())
    }

    @Test
    fun `refusals in a row slow the pace down`() {
        val policy = ReconnectPolicy()

        // The phone never heard the computer go off: all it sees is a
        // refused connection. A couple of refusals are an ordinary hiccup.
        policy.onFailure()
        policy.onFailure()
        assertFalse("two refusals should not change the pace", policy.dormant)
        assertEquals(ReconnectPolicy.FAST_DELAY_MS, policy.delayMs())

        // The third one is a pattern: the computer is probably switched off.
        policy.onFailure()
        assertTrue(policy.dormant)
        assertEquals(ReconnectPolicy.SLOW_DELAY_MS, policy.delayMs())
    }

    @Test
    fun `a success resets the count of failures`() {
        val policy = ReconnectPolicy()
        policy.onFailure()
        policy.onFailure()
        policy.onConnected()
        assertEquals(0, policy.failures)

        policy.onFailure()
        assertFalse("the count started again", policy.dormant)
    }

    @Test
    fun `the slow pace is much slower than the fast one`() {
        // If these two were close, the whole policy would be pointless.
        assertTrue(
            ReconnectPolicy.SLOW_DELAY_MS > ReconnectPolicy.FAST_DELAY_MS * 3
        )
    }
}
