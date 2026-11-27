package com.music.bitchord.data.recommendations

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local-only initial taste preferences for new users without listening history.
 * Stored in SharedPreferences; never requires authentication or external services.
 */
object TastePreferences {
    private const val PREFS_NAME = "sonora_taste_prefs"
    private const val KEY_GENRES = "selected_genres"
    private const val KEY_DISMISSED = "prompt_dismissed"

    val SUGGESTED_GENRES: List<String> = listOf(
        "Pop", "Rock", "Hip-Hop", "R&B", "Electronic", "Indie",
        "Latin", "Jazz", "Lo-Fi", "Metal", "Classical", "K-Pop",
    )

    private var prefs: SharedPreferences? = null

    private val _selectedGenres = MutableStateFlow<Set<String>>(emptySet())
    val selectedGenres: StateFlow<Set<String>> = _selectedGenres.asStateFlow()

    private val _promptDismissed = MutableStateFlow(false)
    val promptDismissed: StateFlow<Boolean> = _promptDismissed.asStateFlow()

    fun init(context: Context) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        _selectedGenres.value = sp.getStringSet(KEY_GENRES, emptySet())?.toSet().orEmpty()
        _promptDismissed.value = sp.getBoolean(KEY_DISMISSED, false)
    }

    fun toggleGenre(genre: String) {
        val current = _selectedGenres.value
        val next = if (genre in current) current - genre else current + genre
        _selectedGenres.value = next
        prefs?.edit()?.putStringSet(KEY_GENRES, next)?.apply()
    }

    fun dismissPrompt() {
        _promptDismissed.value = true
        prefs?.edit()?.putBoolean(KEY_DISMISSED, true)?.apply()
    }
}
