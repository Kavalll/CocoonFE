package com.thorfeeds.catalog

import com.thorfeeds.catalog.core.ArtWindow
import com.thorfeeds.catalog.core.CatalogOrder
import com.thorfeeds.catalog.core.CatalogRow
import com.thorfeeds.catalog.core.CatalogSort
import com.thorfeeds.catalog.core.OnDeviceMatcher
import com.thorfeeds.catalog.core.ParsedRelease
import com.thorfeeds.catalog.core.SteamArt
import com.thorfeeds.catalog.core.SteamHit
import com.thorfeeds.catalog.core.StoredEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogPresentationTest {
    @Test
    fun steamArtUsesOnlyAnExactAppMatch() {
        val hits = listOf(
            SteamHit(3017860, "app", "DOOM: The Dark Ages"),
            SteamHit(379720, "app", "DOOM"),
            SteamHit(782330, "app", "DOOM Eternal"),
            SteamHit(1, "dlc", "DOOM"),
        )
        assertEquals(379720, SteamArt.choose("Doom", hits))
        assertEquals(782330, SteamArt.choose("DOOM Eternal", hits))
        assertNull(SteamArt.choose("DOOM II", hits))
        assertNull(
            SteamArt.choose(
                "DOOM",
                listOf(SteamHit(1, "app", "DOOM"), SteamHit(2, "app", "Doom")),
            ),
        )
        assertEquals(
            "https://cdn.akamai.steamstatic.com/steam/apps/379720/library_600x900.jpg",
            SteamArt.capsuleUrl(379720),
        )
    }

    @Test
    fun catalogSortsByNameSizeNewestAndPopularity() {
        val small = row("Northwind", epoch = 1, bytes = 5)
        val big = row("Example Quest", epoch = 3, bytes = 50)
        val unknown = row("Mystery", epoch = 2, bytes = null)
        val rows = listOf(unknown, small, big)
        assertEquals(listOf("Example Quest", "Mystery", "Northwind"), CatalogOrder.sort(rows, CatalogSort.NAME, emptyMap()).map { it.canonicalTitle })
        assertEquals(listOf("Example Quest", "Northwind", "Mystery"), CatalogOrder.sort(rows, CatalogSort.SIZE, emptyMap()).map { it.canonicalTitle })
        assertEquals(listOf("Example Quest", "Mystery", "Northwind"), CatalogOrder.sort(rows, CatalogSort.NEWEST, emptyMap()).map { it.canonicalTitle })
        val popularity = mapOf("example quest" to 10L, "northwind" to 100L)
        assertEquals(
            listOf("Northwind", "Example Quest", "Mystery"),
            CatalogOrder.sort(rows, CatalogSort.POPULARITY, popularity).map { it.canonicalTitle },
        )
    }

    @Test
    fun artAdvancesOnePageWhenTheVisibleListReachesTheLoadedWindow() {
        val onScreen = ArtWindow.Viewport(lastVisibleIndex = 45, visibleCount = 8, heightPx = 1800)
        assertEquals(100, ArtWindow.nextPage(window = 50, total = 500, viewport = onScreen))
        assertEquals(100, ArtWindow.nextPage(window = 100, total = 500, viewport = onScreen))
        val flung = ArtWindow.Viewport(lastVisibleIndex = 180, visibleCount = 8, heightPx = 1800)
        assertEquals(200, ArtWindow.nextPage(window = 50, total = 500, viewport = flung))
        assertEquals(500, ArtWindow.nextPage(window = 480, total = 500, viewport = flung.copy(lastVisibleIndex = 490)))
        assertFalse(ArtWindow.shouldAdvance(50, 500, onScreen.copy(lastVisibleIndex = 10)))
    }

    @Test
    fun artDoesNotRequestTheWholeCatalogFromOneLayoutPass() {
        val entireList = ArtWindow.Viewport(lastVisibleIndex = 499, visibleCount = 500, heightPx = 20_000)
        assertEquals(50, ArtWindow.nextPage(window = 50, total = 500, viewport = entireList))
        val stretched = entireList.copy(visibleCount = 12, heightPx = 20_000)
        assertEquals(50, ArtWindow.nextPage(window = 50, total = 500, viewport = stretched))
    }

    @Test
    fun dpadNearTheEndOfThePageRequestsTheNextPage() {
        assertFalse(ArtWindow.focusedIndexRequestsNext(index = 0, window = 50, total = 500))
        assertTrue(ArtWindow.focusedIndexRequestsNext(index = 42, window = 50, total = 500))
        assertFalse(ArtWindow.focusedIndexRequestsNext(index = 42, window = 100, total = 500))
        assertFalse(ArtWindow.focusedIndexRequestsNext(index = 90, window = 500, total = 500))
    }

    @Test
    fun onDeviceKeysMatchWithoutRescanningEveryTitlePerEntry() {
        val entries = listOf(
            StoredEntry("Example Quest", isDirectory = true, hasPayload = true),
            StoredEntry("notes.txt", isDirectory = false, hasPayload = true),
        )
        assertEquals(setOf("example quest"), OnDeviceMatcher.presentKeys(listOf("Example Quest", "Northwind"), entries))
    }

    private fun row(title: String, epoch: Long, bytes: Long?): CatalogRow {
        return CatalogRow(
            latest = ParsedRelease(
                rawTitle = title,
                canonicalTitle = title,
                uploadDate = "",
                uploadEpochMs = epoch,
                fileSizeLabel = "",
                fileSizeBytes = bytes,
                uris = emptyList(),
            ),
            older = emptyList(),
        )
    }
}
