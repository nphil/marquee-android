package com.nphil.marquee.session

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkChangesTest {
	private var changes = 0

	@Test
	fun theNetworkInUseWhenWatchingStartsIsNotAChange() {
		val watch = NetworkChanges("wifi") { changes++ }
		watch.available("wifi")
		assertEquals(0, changes)
	}

	@Test
	fun eachOtherNetworkIsAChangeOnceIncludingTheFirstAfterBeingOffline() {
		val watch = NetworkChanges<String>(null) { changes++ }
		watch.available("cellular")
		watch.available("cellular")
		watch.available("wifi")
		assertEquals(2, changes)
	}
}
