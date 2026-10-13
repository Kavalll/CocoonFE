package com.thorfeeds.catalog

import com.thorfeeds.catalog.core.GameArchiveIndex
import com.thorfeeds.catalog.core.LocatedArchive
import com.thorfeeds.catalog.core.ArchiveFormat
import com.thorfeeds.catalog.core.ArchiveItem
import com.thorfeeds.catalog.core.ArchiveLayout
import com.thorfeeds.catalog.core.ArchiveUnpacker
import com.thorfeeds.catalog.core.DirectoryUnpackOutput
import com.thorfeeds.catalog.core.UnpackException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveTest {
    @Test
    fun existingArchiveIsFoundWithoutANewDownload() {
        val archives = listOf(
            LocatedArchive(folderName = "Mega Bonk", fileName = "Mega.Bonk.rar"),
            LocatedArchive(folderName = "Mega Bonk", fileName = "play.exe"),
            LocatedArchive(folderName = "Other Game", fileName = "other.rar"),
            LocatedArchive(folderName = null, fileName = "Mega Bonk.zip"),
        )
        val found = GameArchiveIndex.forTitle("Mega Bonk", archives).map { it.fileName }
        assertEquals(listOf("Mega.Bonk.rar", "Mega Bonk.zip"), found)
    }

    @Test
    fun singleWrapperDirectoryIsStripped() {
        val plan = ArchiveLayout.plan(
            listOf(
                ArchiveItem("Game/", directory = true, size = 0),
                ArchiveItem("Game/play.exe", directory = false, size = 5),
                ArchiveItem("Game/data/a.bin", directory = false, size = 3),
            ),
        )
        assertEquals(listOf("play.exe", "data/a.bin"), plan.map { it.destination })
    }

    @Test
    fun twoTopLevelEntriesStayNested() {
        val plan = ArchiveLayout.plan(
            listOf(
                ArchiveItem("notes.txt", directory = false, size = 1),
                ArchiveItem("Game/play.exe", directory = false, size = 5),
            ),
        )
        assertEquals(listOf("notes.txt", "Game/play.exe"), plan.map { it.destination })
    }

    @Test
    fun aSingleFileIsNotRenamed() {
        val plan = ArchiveLayout.plan(listOf(ArchiveItem("notes.txt", directory = false, size = 4)))
        assertEquals("notes.txt", plan.single().destination)
        assertEquals(ArchiveFormat.RAR, ArchiveLayout.formatOf("Mega Bonk.rar"))
        assertEquals(ArchiveFormat.ZIP, ArchiveLayout.formatOf("pack.ZIP"))
        assertEquals(null, ArchiveLayout.formatOf("play.exe"))
    }

    @Test
    fun zipWrapperIsExtractedIntoTheFolder() {
        val root = File.createTempFile("archive", "").apply { delete(); mkdirs() }
        val zip = File(root, "game.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("Game/"))
            out.closeEntry()
            out.putNextEntry(ZipEntry("Game/play.exe"))
            out.write("hello".toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("Game/data/a.bin"))
            out.write("xyz".toByteArray())
            out.closeEntry()
        }
        val dest = File(root, "out").apply { mkdirs() }
        val created = ArchiveUnpacker.unpack(zip, ArchiveFormat.ZIP, DirectoryUnpackOutput(dest), cancelled = { false })
        assertEquals(listOf("play.exe", "data/a.bin"), created)
        assertEquals("hello", File(dest, "play.exe").readText())
        assertEquals("xyz", File(dest, "data/a.bin").readText())
        assertFalse(File(dest, "Game").exists())
        root.deleteRecursively()
    }

    @Test
    fun unsafeZipPathIsRejected() {
        val root = File.createTempFile("archive", "").apply { delete(); mkdirs() }
        val zip = File(root, "game.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("../outside.txt"))
            out.write("nope".toByteArray())
            out.closeEntry()
        }
        val dest = File(root, "out").apply { mkdirs() }
        try {
            ArchiveUnpacker.unpack(zip, ArchiveFormat.ZIP, DirectoryUnpackOutput(dest), cancelled = { false })
            throw AssertionError("An unsafe path should keep the archive unextracted")
        } catch (error: UnpackException) {
            assertEquals("zip", error.format)
            assertFalse(error.offerZArchiver)
            assertTrue(error.message.orEmpty().contains("unsafe"))
        }
        assertFalse(File(root, "outside.txt").exists())
        assertFalse(File(dest, "outside.txt").exists())
        root.deleteRecursively()
    }

    @Test
    fun cancelStopsBeforeWriting() {
        val root = File.createTempFile("archive", "").apply { delete(); mkdirs() }
        val zip = File(root, "game.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("play.exe"))
            out.write("hello".toByteArray())
            out.closeEntry()
        }
        val dest = File(root, "out").apply { mkdirs() }
        try {
            ArchiveUnpacker.unpack(zip, ArchiveFormat.ZIP, DirectoryUnpackOutput(dest), cancelled = { true })
            throw AssertionError("Cancel should stop extraction")
        } catch (error: CancellationException) {
            assertFalse(File(dest, "play.exe").exists())
        }
        root.deleteRecursively()
    }

    @Test
    fun rar5ExtractsBothFiles() {
        val root = File.createTempFile("archive", "").apply { delete(); mkdirs() }
        val rar = File(root, "game.rar")
        val bytes = ArchiveTest::class.java.classLoader!!.getResourceAsStream("rar5-two-files.rar")?.use { it.readBytes() }
            ?: throw AssertionError("Missing RAR5 fixture")
        rar.writeBytes(bytes)
        val dest = File(root, "out").apply { mkdirs() }
        val created = ArchiveUnpacker.unpack(rar, ArchiveFormat.RAR, DirectoryUnpackOutput(dest), cancelled = { false })
        assertEquals(listOf("FILE1.TXT", "FILE2.TXT"), created)
        assertEquals("file1\r\n", File(dest, "FILE1.TXT").readText())
        assertEquals("file2\r\n", File(dest, "FILE2.TXT").readText())
        root.deleteRecursively()
    }

    @Test
    fun unreadableRarOffersZArchiver() {
        val root = File.createTempFile("archive", "").apply { delete(); mkdirs() }
        val rar = File(root, "game.rar")
        rar.writeBytes(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00))
        val dest = File(root, "out").apply { mkdirs() }
        try {
            ArchiveUnpacker.unpack(rar, ArchiveFormat.RAR, DirectoryUnpackOutput(dest), cancelled = { false })
            throw AssertionError("A broken rar should stay in the folder")
        } catch (error: UnpackException) {
            assertEquals("rar", error.format)
            assertTrue(error.offerZArchiver)
            assertTrue(error.message.orEmpty().contains("left in the game folder"))
        }
        assertTrue(dest.list().isNullOrEmpty())
        root.deleteRecursively()
    }
}
