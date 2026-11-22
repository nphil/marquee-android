package com.nphil.marquee.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Test

class PosterLabelsTest {
	@Test
	fun profilesKeepTheirChoicesAndResetIndependently() {
		val prefs = mutablePreferencesOf()
		val chosen = PosterLabels(showsTitle = false, details = PosterLabels.Details.Genre, showsSortValue = false)
		assertEquals(PosterLabels(true, PosterLabels.Details.None, true), prefs.posterLabels("guest"))
		prefs.setPosterLabels("", chosen)
		prefs.setPosterLabels("guest", chosen.copy(details = PosterLabels.Details.Video))
		assertEquals(chosen, prefs.posterLabels(""))
		assertEquals(PosterLabels.Details.Video, prefs.posterLabels("guest").details)
		prefs.setPosterLabels("guest", PosterLabels())
		assertEquals(PosterLabels(), prefs.posterLabels("guest"))
		assertEquals(chosen, prefs.posterLabels(""))
	}

	@Test
	fun unknownDetailKeepsTheOtherSavedChoices() {
		val prefs = mutablePreferencesOf()
		prefs.setPosterLabels("a", PosterLabels(showsTitle = false, showsSortValue = false))
		prefs[stringPreferencesKey("posters.details.a")] = "FutureOption"
		assertEquals(PosterLabels(false, PosterLabels.Details.None, false), prefs.posterLabels("a"))
	}
}
