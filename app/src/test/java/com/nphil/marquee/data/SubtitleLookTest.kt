package com.nphil.marquee.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleLookTest {
	@Test
	fun blendIsTheDefaultAndHoldsWhiteJustUnderFullBrightness() {
		assertEquals(SubtitleLook(), SubtitleLook.of(SubtitlePreset.Blend))
		// White lifted to 0.94 (240) at 90 % (229), against Apple TV's plain white at full strength.
		assertEquals(0xE5F0F0F0.toInt(), SubtitleLook().textArgb)
		assertEquals(0xFFFFFFFF.toInt(), SubtitleLook.of(SubtitlePreset.AppleTV).textArgb)
	}

	@Test
	fun aPresetIsOnlyTickedWhileNothingIsChangedFromIt() {
		SubtitlePreset.entries.forEach { assertTrue(SubtitleLook.of(it).isPreset) }
		assertFalse(SubtitleLook.of(SubtitlePreset.Netflix).copy(size = 130).isPreset)
	}

	@Test
	fun aLookIsKeptPerProfileAndAnUnreadableOneFallsBackToBlend() {
		val prefs = mutablePreferencesOf()
		val chosen = SubtitleLook.of(SubtitlePreset.LargePrint).copy(colour = SubtitleColour.Cyan)
		prefs.setSubtitleLook("a", chosen)
		assertEquals(chosen, prefs.subtitleLook("a"))
		assertEquals(SubtitleLook(), prefs.subtitleLook("b"))
		assertEquals(SubtitleLook(), prefs.subtitleLook(null))
		prefs[stringPreferencesKey("subtitles.c.look")] = """{"preset":"Gone"}"""
		assertEquals(SubtitleLook(), prefs.subtitleLook("c"))
	}
}
