/*
 * Copyright (C) 2026 BitChord Project
 * Ported from AriaCast (GPL-3.0, https://github.com/AriaCast/AriaCast-app)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.music.bitchord.playback.dlna

/** Discovered UPnP / DLNA MediaRenderer capable of AVTransport playback. */
data class DlnaDevice(
    val udn: String,
    val name: String,
    val host: String,
    val avControlUrl: String,
    val rcControlUrl: String?,
    val location: String,
)
