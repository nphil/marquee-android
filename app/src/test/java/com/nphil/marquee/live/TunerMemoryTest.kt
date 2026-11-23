package com.nphil.marquee.live

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TunerMemoryTest {
	private var clock = 0L
	private val memory = TunerMemory<String, Any>({ clock })
	private val tuner = Any()

	@Test
	fun anAnswerIsRememberedForAMinuteAndOnlyWhereItWasFound() {
		memory.keep("home", tuner)
		clock = 59_999
		assertSame(tuner, memory.recall("home")?.tuner)
		assertNull(memory.recall("away"))
		clock = 60_000
		assertNull(memory.recall("home"))
	}

	@Test
	fun silenceIsRememberedToo() {
		memory.keep("home", null)
		val heard = checkNotNull(memory.recall("home"))
		assertNull(heard.tuner)
	}

	@Test
	fun aLostTunerIsUnreachableForAFreshMinute() {
		memory.keep("home", tuner)
		clock = 50_000
		memory.lost(tuner)
		clock = 109_999
		assertNull(checkNotNull(memory.recall("home")).tuner)
		clock = 110_000
		assertNull(memory.recall("home"))
	}

	@Test
	fun losingAnotherTunerChangesNothing() {
		memory.keep("home", tuner)
		memory.lost(Any())
		assertSame(tuner, memory.recall("home")?.tuner)
	}
}
