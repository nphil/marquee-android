package com.nphil.marquee.backup

import com.nphil.marquee.backup.BackupFolderRule.Existing
import com.nphil.marquee.backup.BackupFolderRule.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupFolderRuleTest {
	@Test
	fun anEmptyFolderIsWrittenTo() {
		assertEquals(Verdict.Write, BackupFolderRule.verdict(null, 100, false))
	}

	@Test
	fun anotherAccountsFileIsNeverReplaced() {
		assertEquals(Verdict.OtherAccount, BackupFolderRule.verdict(Existing(50, belongs = false), 100, true))
	}

	@Test
	fun aNewerFileWaitsUntilTheOwnerChoosesButAnOlderOrKnownOneIsReplaced() {
		assertEquals(Verdict.Newer(200), BackupFolderRule.verdict(Existing(200, true), 100, false))
		assertEquals(Verdict.Write, BackupFolderRule.verdict(Existing(200, true), 100, replacingNewer = true))
		assertEquals(Verdict.Write, BackupFolderRule.verdict(Existing(100, true), 100, false))
		assertEquals(Verdict.Write, BackupFolderRule.verdict(Existing(50, true), 100, false))
	}
}
