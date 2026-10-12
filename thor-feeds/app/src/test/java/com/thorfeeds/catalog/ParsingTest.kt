package com.thorfeeds.catalog

import com.thorfeeds.catalog.core.CatalogDeduper
import com.thorfeeds.catalog.core.DisplayChoice
import com.thorfeeds.catalog.core.FeedFormatException
import com.thorfeeds.catalog.core.FeedParser
import com.thorfeeds.catalog.core.FeedUrls
import com.thorfeeds.catalog.core.FileSizeParser
import com.thorfeeds.catalog.core.HostKind
import com.thorfeeds.catalog.core.MirrorFallback
import com.thorfeeds.catalog.core.Names
import com.thorfeeds.catalog.core.OnDeviceMatcher
import com.thorfeeds.catalog.core.ParsedRelease
import com.thorfeeds.catalog.core.StoredEntry
import com.thorfeeds.catalog.core.TitleCanonicalizer
import com.thorfeeds.catalog.core.UploadDates
import com.thorfeeds.catalog.core.UriHosts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.roundToLong

class ParsingTest {
    @Test
    fun canonicalNamesDropBoilerplateAndVersions() {
        assertEquals(
            "Valheim",
            TitleCanonicalizer.canonical("Valheim Free Download (v1.0.17 + Co-op)"),
        )
        assertEquals(
            "Tomb Raider I-III Remastered Starring Lara Croft",
            TitleCanonicalizer.canonical(
                "Tomb Raider I-III Remastered Starring Lara Croft Free Download (Build 25209630)",
            ),
        )
        assertEquals("Example Quest", TitleCanonicalizer.canonical("Example Quest Free Download"))
        assertEquals("example quest", TitleCanonicalizer.canonical("example quest free download"))
        assertEquals("Harbor Lights", TitleCanonicalizer.canonical("Harbor Lights (v1.2.3)"))
        assertEquals("Harbor Lights", TitleCanonicalizer.canonical("Harbor Lights (Build 25209630)"))
        assertEquals("Harbor Lights", TitleCanonicalizer.canonical("Harbor Lights Build 44 Free Download"))
        assertEquals(
            "Studio vs. Studio: Infinite",
            TitleCanonicalizer.canonical("Studio vs. Studio: Infinite Free Download"),
        )
        assertEquals("Plain Name", TitleCanonicalizer.canonical("Plain Name"))
        assertEquals("Game (2023)", TitleCanonicalizer.canonical("Game (2023)"))
    }

    @Test
    fun fileSizeUsesTheFirstNumberAndUnit() {
        assertEquals(bytes(5.9, "GB"), FileSizeParser.parseBytes("5.9 GB"))
        assertEquals(bytes(58.2, "GB"), FileSizeParser.parseBytes("58.2 GB"))
        assertEquals(bytes(31.0, "GB"), FileSizeParser.parseBytes("31 GB"))
        assertEquals(bytes(665.0, "MB"), FileSizeParser.parseBytes("665 MB"))
        assertEquals(bytes(32.0, "GB"), FileSizeParser.parseBytes("32.0 ~ 245.1 GB"))
        assertEquals(bytes(37.0, "GB"), FileSizeParser.parseBytes("37 GB (RAR)"))
        assertEquals(bytes(130.0, "GB"), FileSizeParser.parseBytes("130 GB (Torrent)"))
        assertEquals(bytes(28.8, "GB"), FileSizeParser.parseBytes("28.8GB"))
        assertEquals(bytes(6.5, "GB"), FileSizeParser.parseBytes("6.5w GB"))
        assertEquals(bytes(1024.0, "MB"), FileSizeParser.parseBytes("1,024 MB"))
        assertNull(FileSizeParser.parseBytes("unknown"))
        assertNull(FileSizeParser.parseBytes(""))
        assertNull(FileSizeParser.parseBytes("12"))
        assertNull(FileSizeParser.parseBytes(null))
        assertNull(FileSizeParser.parseBytes("@@@"))
    }

    @Test
    fun catalogKeepsLatestReleaseAndHistory() {
        val older = release("Example Quest Free Download (v1.0.1)", "2026-09-01T12:00:00Z", "31 GB")
        val newer = release("Example Quest Free Download", "2026-10-11T19:02:09+00:00", "58.2 GB")
        val other = release("Northwind Tale Free Download", "2026-08-02T00:00:00+00:00", "5.9 GB")
        val rows = CatalogDeduper.rows(listOf(older, other, newer))
        assertEquals(2, rows.size)
        val quest = rows.first { it.canonicalTitle == "Example Quest" }
        assertEquals(newer.uploadDate, quest.latest.uploadDate)
        assertEquals(listOf(older.uploadDate), quest.older.map { it.uploadDate })
        assertEquals("Northwind Tale", rows.last().canonicalTitle)
        assertTrue(CatalogDeduper.matchesQuery(quest, "v1.0.1"))
        assertFalse(CatalogDeduper.matchesQuery(quest, "missing"))
    }

    @Test
    fun feedParserPreservesMirrorOrderAndRejectsHtml() {
        val feed = FeedParser.parse(
            """
            {
              "name": "Sample Shelf",
              "downloads": [
                {
                  "title": "Example Quest Free Download",
                  "uploadDate": "2026-10-11T19:02:09+00:00",
                  "fileSize": "58.2 GB",
                  "uris": ["https://cdn.example/a", "https://cdn.example/b"]
                },
                {"title": " ", "uploadDate": "2026-01-01T00:00:00Z", "fileSize": "1 MB", "uris": ["https://cdn.example/skip"]}
              ]
            }
            """.trimIndent(),
        )
        assertEquals("Sample Shelf", feed.name)
        assertEquals(1, feed.releases.size)
        assertEquals(1, feed.skippedEntries)
        assertEquals(listOf("https://cdn.example/a", "https://cdn.example/b"), feed.releases.single().uris)
        assertEquals(bytes(58.2, "GB"), feed.releases.single().fileSizeBytes)
        assertEquals("11 Oct 2026", UploadDates.format(feed.releases.single().uploadDate))

        val singleUri = FeedParser.parse(
            """{"name":"Shelf","downloads":[{"title":"Solo Free Download","uploadDate":"2026-01-02T00:00:00Z","fileSize":"1 MB","uris":"https://cdn.example/only"}]}""",
        )
        assertEquals(listOf("https://cdn.example/only"), singleUri.releases.single().uris)

        assertTrue(FeedParser.looksLikeHtml("<!DOCTYPE html><html><title>Just a moment...</title></html>"))
        try {
            FeedParser.parse("<html><body>Just a moment...</body></html>")
            throw AssertionError("A Cloudflare check page should not parse as a feed")
        } catch (error: FeedFormatException) {
            assertTrue(error.message.orEmpty().contains("Cloudflare"))
            assertFalse(error.message.orEmpty().contains("Couldn't read this feed"))
        }

        val withBomAndSpace = "\uFEFF" + """
            {"name":"Shelf","downloads":[{"title":"Example Quest Free Download","uploadDate":"2026-10-11T19:02:09+00:00","fileSize":"31 GB","uris":["https://cdn.example/only"]}]}
        """.trim() + "\n\n"
        assertEquals("Shelf", FeedParser.parse(withBomAndSpace).name)
        assertEquals(
            "https://feeds.example/shelf",
            FeedUrls.normalize("https://feeds.example/shelf"),
        )
    }

    @Test
    fun mirrorFallbackWalksTheListInOrder() {
        val uris = listOf("https://cdn.example/1", "https://cdn.example/2", "https://cdn.example/3")
        assertEquals(0, MirrorFallback.firstIndex(uris))
        assertEquals(1, MirrorFallback.nextIndex(uris.size, 0))
        assertEquals(2, MirrorFallback.nextIndex(uris.size, 1))
        assertNull(MirrorFallback.nextIndex(uris.size, 2))
        assertNull(MirrorFallback.firstIndex(emptyList()))
        assertEquals(HostKind.GOFILE_SHARE, UriHosts.kind("https://gofile.io/d/example"))
        assertEquals(HostKind.GOFILE_DIRECT, UriHosts.kind("https://store-9.gofile.io/download/web/example/file.zip"))
        assertEquals(HostKind.ONE_FICHIER_SHARE, UriHosts.kind("https://1fichier.com/?example"))
        assertEquals(HostKind.VIKING_FILE, UriHosts.kind("https://vikingfile.com/f/example"))
        assertEquals(HostKind.DATANODES_SHARE, UriHosts.kind("https://datanodes.to/example"))
        assertEquals(HostKind.DATANODES_DIRECT, UriHosts.kind("https://n1.datanodes.to/d/not-a-real-file.bin"))
        assertEquals(HostKind.DIRECT, UriHosts.kind("https://cdn.example/file.zip"))
        assertEquals("Gofile", UriHosts.label("https://gofile.io/d/example"))
        assertEquals("https://feeds.example/list.json", FeedUrls.normalize("feeds.example/list.json"))
    }

    @Test
    fun onDeviceMatchUsesCanonicalFolderOrFileNames() {
        val present = listOf(
            StoredEntry("Example Quest", isDirectory = true, hasPayload = true),
            StoredEntry("quest.exe", isDirectory = false, hasPayload = true),
        )
        assertTrue(OnDeviceMatcher.isPresent("Example Quest", present))
        assertTrue(
            OnDeviceMatcher.isPresent(
                "Example Quest",
                listOf(StoredEntry("Example Quest Free Download (v1.2)", isDirectory = true, hasPayload = true)),
            ),
        )
        assertTrue(
            OnDeviceMatcher.isPresent(
                "Example Quest",
                listOf(StoredEntry("Example Quest.7z", isDirectory = false, hasPayload = true)),
            ),
        )
        assertFalse(
            OnDeviceMatcher.isPresent(
                "Example Quest",
                listOf(StoredEntry("Example Quest", isDirectory = true, hasPayload = false)),
            ),
        )
        assertFalse(
            OnDeviceMatcher.isPresent(
                "Example Quest",
                listOf(StoredEntry("Example", isDirectory = true, hasPayload = true)),
            ),
        )
        assertFalse(
            OnDeviceMatcher.isPresent(
                "Example Quest",
                listOf(StoredEntry("Example Quest.zip.partial", isDirectory = false, hasPayload = true)),
            ),
        )
        assertEquals("Example Quest", Names.sanitizeFolder("Example: Quest"))
    }

    @Test
    fun secondaryDisplayPrefersAnotherPoweredScreen() {
        val displays = listOf(
            DisplayChoice.Snapshot(id = 0, isOn = true, supportsPresentation = false),
            DisplayChoice.Snapshot(id = 2, isOn = false, supportsPresentation = true),
            DisplayChoice.Snapshot(id = 3, isOn = true, supportsPresentation = false),
            DisplayChoice.Snapshot(id = 4, isOn = true, supportsPresentation = true),
        )
        assertEquals(4, DisplayChoice.chooseSecondary(0, displays)?.id)
        assertEquals(0, DisplayChoice.chooseSecondary(4, displays)?.id)
        assertNull(DisplayChoice.chooseSecondary(0, listOf(displays.first())))
    }

    @Test
    fun optionalLocalFeedValidatesWithoutPrintingLinks() {
        val path = System.getenv("THOR_FEEDS_SAMPLE")
        assumeTrue("No local feed was provided", !path.isNullOrBlank())
        val file = File(path!!)
        assumeTrue(file.isFile)
        val text = file.readText()
        assumeFalse(FeedParser.looksLikeHtml(text))
        val feed = FeedParser.parse(text)
        assertTrue(feed.releases.isNotEmpty())
        feed.releases.forEach { release ->
            assertTrue(release.canonicalTitle.isNotBlank())
            FileSizeParser.parseBytes(release.fileSizeLabel)
            MirrorFallback.firstIndex(release.uris)
        }
        val rows = CatalogDeduper.rows(feed.releases)
        assertTrue(rows.size <= feed.releases.size)
        rows.forEach { row ->
            assertTrue(row.older.all { it.uploadEpochMs <= row.latest.uploadEpochMs })
        }
    }

    private fun release(title: String, date: String, size: String): ParsedRelease {
        return ParsedRelease(
            rawTitle = title,
            canonicalTitle = TitleCanonicalizer.canonical(title),
            uploadDate = date,
            uploadEpochMs = UploadDates.parseEpochMs(date),
            fileSizeLabel = size,
            fileSizeBytes = FileSizeParser.parseBytes(size),
            uris = listOf("https://cdn.example/file"),
        )
    }

    private fun bytes(value: Double, unit: String): Long {
        val multiplier = when (unit) {
            "MB" -> 1024.0 * 1024.0
            "GB" -> 1024.0 * 1024.0 * 1024.0
            else -> error(unit)
        }
        return (value * multiplier).roundToLong()
    }
}
