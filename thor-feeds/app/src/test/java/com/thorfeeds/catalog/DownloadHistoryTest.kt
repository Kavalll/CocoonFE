package com.thorfeeds.catalog

import com.thorfeeds.catalog.download.DownloadHistoryRules
import com.thorfeeds.catalog.download.DownloadPhase
import com.thorfeeds.catalog.download.DownloadRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadHistoryTest {
    @Test
    fun activeDownloadBecomesInterrupted() {
        val running = DownloadRecord(releaseId = "1", canonicalTitle = "Mega Bonk", phase = DownloadPhase.Running.name)
        val resumed = DownloadHistoryRules.interrupt(running, acceptsResume = true)
        assertEquals(DownloadPhase.Interrupted, resumed.toPhase())
        assertEquals(true, resumed.acceptsResume)
        val fresh = DownloadHistoryRules.interrupt(running, acceptsResume = false)
        assertEquals(false, fresh.acceptsResume)
        val done = DownloadHistoryRules.interrupt(
            running.copy(phase = DownloadPhase.Completed.name),
            acceptsResume = true,
        )
        assertEquals(DownloadPhase.Completed, done.toPhase())
    }
}
