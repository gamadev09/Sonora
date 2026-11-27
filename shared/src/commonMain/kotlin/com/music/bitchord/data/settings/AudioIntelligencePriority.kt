package com.music.bitchord.data.settings

/**
 * Processing priority for Sonora's Audio Intelligence background vocal separation.
 *
 * Each mode adjusts real execution parameters:
 * - [inferenceThreads]: ONNX Runtime intra-op CPU thread count.
 * - [overlapRatio]: fractional overlap between adjacent 22.3 s separation chunks
 *   (higher overlap reduces boundary artifacts at the cost of extra inference work).
 * - [maskPower]: Wiener soft-mask exponent (values > 1.0 sharpen separation slightly).
 * - [maxThermalStatus]: Android PowerManager thermal status threshold at which
 *   background processing throttles down to protect playback and thermals.
 * - [interChunkYieldMs]: cooperative pause between chunks to keep UI VSYNC and
 *   audio playback threads uncontended.
 */
enum class AudioIntelligencePriority(
    val inferenceThreads: Int,
    val overlapRatio: Float,
    val maskPower: Float,
    val maxThermalStatus: Int,
    val interChunkYieldMs: Long,
    val cacheProfileTag: String,
) {
    BALANCED(
        inferenceThreads = 2,
        overlapRatio = 0.25f,
        maskPower = 1.0f,
        maxThermalStatus = 3, // PowerManager.THERMAL_STATUS_SEVERE
        interChunkYieldMs = 12L,
        cacheProfileTag = "balanced",
    ),
    HIGH_QUALITY(
        inferenceThreads = 3,
        overlapRatio = 0.50f,
        maskPower = 1.15f,
        maxThermalStatus = 4, // PowerManager.THERMAL_STATUS_CRITICAL
        interChunkYieldMs = 0L,
        cacheProfileTag = "hq",
    ),
    EFFICIENCY(
        inferenceThreads = 1,
        overlapRatio = 0.125f,
        maskPower = 1.0f,
        maxThermalStatus = 2, // PowerManager.THERMAL_STATUS_MODERATE
        interChunkYieldMs = 35L,
        cacheProfileTag = "eff",
    ),
}
