package com.nphil.marquee.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexOriginPolicyTest {
	@Test
	fun credentialsRequireSameOriginWithoutAnExplicitRequestFlag() {
		val url = "https://plex.invalid/library/metadata/10".toHttpUrl()
		assertTrue(hasPlexCredential(mapOf("x-plex-token" to "fixture"), url))
		assertTrue(hasPlexCredential(emptyMap(), url.newBuilder().addQueryParameter("X-Plex-Token", "fixture").build()))
		assertFalse(hasPlexCredential(mapOf("X-Plex-Client-Identifier" to "fixture"), url))
	}
}
