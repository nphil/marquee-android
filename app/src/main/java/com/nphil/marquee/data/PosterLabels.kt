package com.nphil.marquee.data

import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

@Immutable
internal data class PosterLabels(
	val showsTitle: Boolean = true,
	val details: Details = Details.None,
	val showsSortValue: Boolean = true,
) {
	enum class Details(val title: String) {
		None("Nothing"),
		YearAndLength("Year · Length"),
		Genre("Genre"),
		Video("Video"),
		RatingAndScore("Age Rating · Score"),
	}

	val summary: String
		get() = listOfNotNull("Title".takeIf { showsTitle }, details.title.takeUnless { details == Details.None })
			.joinToString(", ").ifEmpty { Details.None.title }
}

internal fun Preferences.posterLabels(profile: String): PosterLabels {
	val details = this[detailsKey(profile)]
	return PosterLabels(
		showsTitle = this[titleKey(profile)] ?: true,
		details = PosterLabels.Details.entries.firstOrNull { it.name == details } ?: PosterLabels.Details.None,
		showsSortValue = this[sortValueKey(profile)] ?: true,
	)
}

internal fun MutablePreferences.setPosterLabels(profile: String, labels: PosterLabels) {
	this[titleKey(profile)] = labels.showsTitle
	this[detailsKey(profile)] = labels.details.name
	this[sortValueKey(profile)] = labels.showsSortValue
}

private fun titleKey(profile: String) = booleanPreferencesKey("posters.title.$profile")
private fun detailsKey(profile: String) = stringPreferencesKey("posters.details.$profile")
private fun sortValueKey(profile: String) = booleanPreferencesKey("posters.sortValue.$profile")
