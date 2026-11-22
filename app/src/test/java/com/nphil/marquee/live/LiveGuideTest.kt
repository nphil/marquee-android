package com.nphil.marquee.live

import com.nphil.marquee.core.GuideChannel
import com.nphil.marquee.core.GuideProgramme
import com.nphil.marquee.core.GuideWindow
import com.nphil.marquee.data.LiveLists
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveGuideTest {
	@Test
	fun nowAndNextAdvanceAtBoundariesAndDoNotFillGapsWithEndedShows() {
		val first = programme("First", 0.0, 60.0)
		val second = programme("Second", 60.0, 120.0)
		val later = programme("Later", 180.0, 240.0)
		val guide = guide(airings = mapOf("one" to listOf(first, second, later)))

		assertEquals(first to second, guide.programmes("one", 59.0))
		assertEquals(second to later, guide.programmes("one", 60.0))
		assertEquals(null to later, guide.programmes("one", 120.0))
		assertEquals(null to null, guide.programmes("one", 240.0))
	}

	@Test
	fun recentChannelsKeepWatchOrderAndSkipMissingLineupEntries() {
		val one = channel("one")
		val two = channel("two")
		val guide = guide(channels = listOf(one, two))
		val lists = LiveLists(recents = recentChannels(listOf("gone", "one", "two"), "two"))

		assertEquals(listOf("two", "gone", "one"), lists.recents)
		assertEquals(listOf(two, one), liveChannels(guide, "recent", lists, keys = null))
	}

	private fun programme(title: String, start: Double, end: Double) =
		GuideProgramme(title, title, start = start, end = end, isNew = false, isHD = true)

	private fun channel(id: String) = GuideChannel(id, "dvr", id, id, id, isHD = true, device = JsonObject(emptyMap()))

	private fun guide(channels: List<GuideChannel> = emptyList(), airings: Map<String, List<GuideProgramme>> = emptyMap()) =
		GuideWindow(channels, airings, from = 0.0, to = 240.0, filters = emptyList(), tunerOffline = false)
}
