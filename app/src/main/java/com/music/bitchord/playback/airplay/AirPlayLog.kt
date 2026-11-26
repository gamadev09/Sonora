/*
 * Copyright (C) 2026 BitChord Project
 * Ported from AriaCast (GPL-3.0, https://github.com/AriaCast/AriaCast-app)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.music.bitchord.playback.airplay

import android.util.Log
import com.music.bitchord.BuildConfig

internal object AirPlayLog {
    private const val PREFIX = "BitChordAirPlay/"

    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) runCatching { Log.d(PREFIX + tag, msg) }
    }

    fun i(tag: String, msg: String) {
        if (BuildConfig.DEBUG) runCatching { Log.i(PREFIX + tag, msg) }
    }

    fun w(tag: String, msg: String, throwable: Throwable? = null) {
        runCatching {
            if (throwable != null) Log.w(PREFIX + tag, msg, throwable)
            else Log.w(PREFIX + tag, msg)
        }
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        runCatching {
            if (throwable != null) Log.e(PREFIX + tag, msg, throwable)
            else Log.e(PREFIX + tag, msg)
        }
    }
}
