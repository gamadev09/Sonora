package com.music.bitchord.playback

import com.music.bitchord.data.sources.SourceKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Contextual warning emitted when a built-in music provider suffers three
 * consecutive genuine playback or stream-resolution failures.
 */
data class PlaybackFailureWarning(
    val provider: SourceKind,
    val providerName: String,
    val failureCount: Int,
)

/**
 * Tracks consecutive genuine playback failures per built-in music provider.
 *
 * - Counts only genuine stream-resolution or playback failures for built-in providers.
 * - Ignores general connectivity faults, local-file errors, and intentional user actions
 *   (skips, pauses, seeks, cancellations).
 * - Resets on successful playback.
 * - Emits at most one active warning at a time (no duplicate/overlapping dialogs),
 *   and re-arms cleanly for a new sequence of [threshold] failures after dismissal.
 */
class PlaybackFailureTracker(
    private val threshold: Int = FAILURE_THRESHOLD,
) {
    private val lock = Any()
    private val consecutiveFailures = HashMap<SourceKind, Int>()
    private val _activeWarning = MutableStateFlow<PlaybackFailureWarning?>(null)
    val activeWarning: StateFlow<PlaybackFailureWarning?> = _activeWarning.asStateFlow()

    fun recordFailure(
        provider: SourceKind,
        isNetworkFault: Boolean = false,
        isLocalFile: Boolean = false,
        isUserAction: Boolean = false,
    ): PlaybackFailureWarning? = synchronized(lock) {
        if (isNetworkFault || isLocalFile || isUserAction || provider !in BUILT_IN_PROVIDERS) {
            return@synchronized null
        }
        val next = (consecutiveFailures[provider] ?: 0) + 1
        if (next < threshold) {
            consecutiveFailures[provider] = next
            return@synchronized null
        }
        // Prevent duplicate or overlapping warnings while one is already displayed.
        if (_activeWarning.value != null) {
            consecutiveFailures[provider] = threshold
            return@synchronized null
        }
        // Reset counter upon emitting so a fresh sequence of [threshold] failures is
        // required before warning again after dismissal.
        consecutiveFailures[provider] = 0
        val warning = PlaybackFailureWarning(
            provider = provider,
            providerName = provider.label,
            failureCount = next,
        )
        _activeWarning.value = warning
        warning
    }

    fun recordSuccess(provider: SourceKind? = null) = synchronized(lock) {
        if (provider == null) {
            consecutiveFailures.clear()
            _activeWarning.value = null
        } else {
            consecutiveFailures.remove(provider)
            if (_activeWarning.value?.provider == provider) {
                _activeWarning.value = null
            }
        }
    }

    fun dismissWarning() = synchronized(lock) {
        _activeWarning.value = null
    }

    fun failureCountFor(provider: SourceKind): Int = synchronized(lock) {
        consecutiveFailures[provider] ?: 0
    }

    fun reset() = synchronized(lock) {
        consecutiveFailures.clear()
        _activeWarning.value = null
    }

    companion object {
        const val FAILURE_THRESHOLD = 3
        val BUILT_IN_PROVIDERS: Set<SourceKind> = setOf(
            SourceKind.YOUTUBE,
            SourceKind.JIOSAAVN,
        )
    }
}

/** App-wide singleton connecting [PlaybackService] error recovery to the UI layer. */
object PlaybackFailureMonitor {
    private val tracker = PlaybackFailureTracker()

    val activeWarning: StateFlow<PlaybackFailureWarning?> = tracker.activeWarning

    fun recordFailure(
        provider: SourceKind,
        isNetworkFault: Boolean = false,
        isLocalFile: Boolean = false,
        isUserAction: Boolean = false,
    ): PlaybackFailureWarning? = tracker.recordFailure(
        provider = provider,
        isNetworkFault = isNetworkFault,
        isLocalFile = isLocalFile,
        isUserAction = isUserAction,
    )

    fun recordSuccess(provider: SourceKind? = null) = tracker.recordSuccess(provider)

    fun dismissWarning() = tracker.dismissWarning()

    fun reset() = tracker.reset()
}
