package com.nphil.marquee.bigscreen

import com.nphil.marquee.core.PlexDirectory
import com.nphil.marquee.core.PlexHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvPeopleTest {
	private fun person(name: String, id: String) = PlexDirectory(tag = name, id = id)
	private fun hub(role: String, vararg people: PlexDirectory) = PlexHub(type = role, directories = people.toList())

	@Test fun nameOnlyCreditMatchesRoleAndNotPartialNames() {
		val data = listOf(hub("actor", person("Alex Reed", "10")), hub("director", person("Alex Reed Jr", "11"), person("Alex Reed", "12"), person("Alex Reed", "12")))
		assertEquals("12", data.uniqueCredit(" alex reed ", "director"))
		assertNull(data.uniqueCredit("Alex Reed", "writer"))
	}

	@Test fun namesakesAndNonPersonDirectoriesCannotOpenAnotherPersonsTitles() {
		val data = listOf(hub("actor", person("Lee", "10"), person("Lee", "11"), person("Jay", "0")), hub("collection", person("Jay", "12")))
		assertNull(data.uniqueCredit("Lee", "actor"))
		assertNull(data.uniqueCredit("Jay", "actor"))
		assertEquals(listOf("10", "11"), data.peopleIn().map { it.id }.toList())
	}
}
