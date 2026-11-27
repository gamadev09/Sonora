package com.music.bitchord.ui.player

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.StateFlow

/**
 * Real-time UI state for Sonora's background Audio Intelligence vocal separation.
 */
sealed interface VocalSeparationUiState {
    data object Idle : VocalSeparationUiState

    /** Downloading the verified Open-Unmix vocal model on demand. */
    data class DownloadingModel(
        val trackId: String,
        val progress: Float,
    ) : VocalSeparationUiState

    /** Separating vocals and instrumental accompaniment in the background. */
    data class Processing(
        val trackId: String,
        val progress: Float,
    ) : VocalSeparationUiState

    /** Stems are ready and actively mixed into the live playback engine. */
    data class Active(
        val trackId: String,
        val vocalGain: Float,
    ) : VocalSeparationUiState

    /** Separation failed or source was unavailable; normal playback continues uninterrupted. */
    data class Error(
        val trackId: String,
        val message: String,
    ) : VocalSeparationUiState
}

/**
 * Host contract bridging the shared player UI's mic toggle to the platform
 * vocal-separation subsystem.
 */
interface VocalSeparationController {
    val state: StateFlow<VocalSeparationUiState>

    /**
     * Toggles vocal separation for [song].
     * - If idle/error: loads cached stems immediately or starts background separation.
     * - If downloading/processing: cancels the in-flight job and returns to idle.
     * - If active: smoothly crossfades back to original playback.
     */
    fun toggle(song: Song)

    /** Adjusts the live vocal stem gain in [0f, 1f] while separated playback is active. */
    fun setVocalGain(gain: Float)

    /** Cancels any active processing job and deactivates stem mixing. */
    fun cancel()
}
