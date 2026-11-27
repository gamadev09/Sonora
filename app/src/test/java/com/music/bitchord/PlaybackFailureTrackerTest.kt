package com.music.bitchord

import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.playback.PlaybackFailureTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackFailureTrackerTest {

    @Test
    fun `three consecutive failures trigger exactly one warning`() {
        val tracker = PlaybackFailureTracker(threshold = 3)

        assertNull(tracker.recordFailure(SourceKind.YOUTUBE))
        assertNull(tracker.recordFailure(SourceKind.YOUTUBE))
        val warning = tracker.recordFailure(SourceKind.YOUTUBE)
        assertNotNull(warning)
        assertEquals(SourceKind.YOUTUBE, warning!!.provider)
        assertEquals("YouTube Music", warning.providerName)
        assertEquals(3, warning.failureCount)
        assertEquals(warning, tracker.activeWarning.value)

        // Additional failures while warning is active do not create duplicate/overlapping warnings.
        assertNull(tracker.recordFailure(SourceKind.YOUTUBE))
        assertNull(tracker.recordFailure(SourceKind.YOUTUBE))
        assertNull(tracker.recordFailure(SourceKind.YOUTUBE))
        assertEquals(warning, tracker.activeWarning.value)
    }

    @Test
    fun `successful playback resets consecutive failure counter`() {
        val tracker = PlaybackFailureTracker(threshold = 3)

        assertNull(tracker.recordFailure(SourceKind.YOUTUBE))
        assertNull(tracker.recordFailure(SourceKind.YOUTUBE))
        assertEquals(2, tracker.failureCountFor(SourceKind.YOUTUBE))

        tracker.recordSuccess(SourceKind.YOUTUBE)
        assertEquals(0, tracker.failureCountFor(SourceKind.YOUTUBE))

        // Must require 3 new consecutive failures after a success.
        assertNull(tracker.recordFailure(SourceKind.YOUTUBE))
        assertNull(tracker.recordFailure(SourceKind.YOUTUBE))
        assertNotNull(tracker.recordFailure(SourceKind.YOUTUBE))
    }

    @Test
    fun `intentional actions local files and network faults do not count as failures`() {
        val tracker = PlaybackFailureTracker(threshold = 3)

        repeat(5) {
            assertNull(tracker.recordFailure(SourceKind.YOUTUBE, isUserAction = true))
            assertNull(tracker.recordFailure(SourceKind.YOUTUBE, isLocalFile = true))
            assertNull(tracker.recordFailure(SourceKind.YOUTUBE, isNetworkFault = true))
            assertNull(tracker.recordFailure(SourceKind.ADDON))
        }
        assertEquals(0, tracker.failureCountFor(SourceKind.YOUTUBE))
        assertNull(tracker.activeWarning.value)
    }

    @Test
    fun `built-in providers are tracked independently and re-arm after dismissal`() {
        val tracker = PlaybackFailureTracker(threshold = 3)

        assertNull(tracker.recordFailure(SourceKind.YOUTUBE))
        assertNull(tracker.recordFailure(SourceKind.YOUTUBE))
        assertNull(tracker.recordFailure(SourceKind.JIOSAAVN))
        assertNull(tracker.recordFailure(SourceKind.JIOSAAVN))

        // Neither has reached 3 yet.
        assertNull(tracker.activeWarning.value)

        // Third JioSaavn failure triggers JioSaavn warning.
        val jioWarning = tracker.recordFailure(SourceKind.JIOSAAVN)
        assertNotNull(jioWarning)
        assertEquals(SourceKind.JIOSAAVN, jioWarning!!.provider)

        // Dismissing clears the warning and requires a fresh sequence of 3 failures for JioSaavn.
        tracker.dismissWarning()
        assertNull(tracker.activeWarning.value)
        assertNull(tracker.recordFailure(SourceKind.JIOSAAVN))
        assertNull(tracker.recordFailure(SourceKind.JIOSAAVN))
        assertNotNull(tracker.recordFailure(SourceKind.JIOSAAVN))
    }
}
