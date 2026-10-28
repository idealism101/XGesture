package com.slideindex.app.overlay.filebrowser

import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FileBrowserEntryTest {
    private lateinit var previousTimeZone: TimeZone

    @Before
    fun setUp() {
        previousTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(previousTimeZone)
    }

    private fun entry(
        name: String,
        kind: FileEntryKind = FileEntryKind.FILE,
        size: Long = 1L,
        modified: Long = 0L,
    ) = FileBrowserEntry(
        name = name,
        path = "/root/$name",
        kind = kind,
        sizeBytes = size,
        modifiedEpochSeconds = modified,
    )

    @Test
    fun sort_keepsDirectoriesFirstInBothDirections() {
        val entries = listOf(
            entry("b.txt"),
            entry("A", FileEntryKind.DIRECTORY),
            entry("a.txt"),
        )
        val ascending = FileBrowserSort.apply(entries, FileSortOrder.NAME_ASC, true, "")
        assertEquals(listOf("A", "a.txt", "b.txt"), ascending.map { it.name })

        val descending = FileBrowserSort.apply(entries, FileSortOrder.NAME_DESC, true, "")
        assertEquals(listOf("A", "b.txt", "a.txt"), descending.map { it.name })
    }

    @Test
    fun sort_byTimeAndSize() {
        val entries = listOf(
            entry("old", modified = 10L, size = 100L),
            entry("new", modified = 30L, size = 10L),
            entry("middle", modified = 20L, size = 50L),
        )
        assertEquals(
            listOf("new", "middle", "old"),
            FileBrowserSort.apply(entries, FileSortOrder.TIME_DESC, true, "").map { it.name },
        )
        assertEquals(
            listOf("old", "middle", "new"),
            FileBrowserSort.apply(entries, FileSortOrder.SIZE_DESC, true, "").map { it.name },
        )
    }

    @Test
    fun sort_filtersHiddenAndQuery() {
        val entries = listOf(entry(".secret"), entry("photo.jpg"), entry("document.pdf"))
        assertEquals(
            listOf("document.pdf", "photo.jpg"),
            FileBrowserSort.apply(entries, FileSortOrder.NAME_ASC, false, "").map { it.name },
        )
        assertTrue(
            FileBrowserSort.apply(entries, FileSortOrder.NAME_ASC, true, "")
                .any { it.name == ".secret" },
        )
        assertEquals(
            listOf("photo.jpg"),
            FileBrowserSort.apply(entries, FileSortOrder.NAME_ASC, true, "PHOTO").map { it.name },
        )
    }

    @Test
    fun formatSize_usesBinaryUnits() {
        assertEquals("0 B", FileBrowserFormat.size(0))
        assertEquals("1023 B", FileBrowserFormat.size(1023))
        assertEquals("1.0 KB", FileBrowserFormat.size(1024))
        assertEquals("1.5 KB", FileBrowserFormat.size(1536))
        assertEquals("1.0 MB", FileBrowserFormat.size(1024L * 1024L))
        assertEquals("—", FileBrowserFormat.size(-1))
    }

    @Test
    fun formatDate_formatsEpochSecondsAndHandlesMissingValue() {
        assertEquals("—", FileBrowserFormat.date(0))
        assertTrue(
            Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""")
                .matches(FileBrowserFormat.date(1_700_000_000L)),
        )
    }

    @Test
    fun fileKind_mapsCommonExtensions() {
        assertEquals(FileKind.FOLDER, FileKind.from("Android", FileEntryKind.DIRECTORY))
        assertEquals(FileKind.SYMLINK, FileKind.from("link", FileEntryKind.SYMLINK))
        assertEquals(FileKind.APK, FileKind.from("app.APK", FileEntryKind.FILE))
        assertEquals(FileKind.IMAGE, FileKind.from("a.jpg", FileEntryKind.FILE))
        assertEquals(FileKind.VIDEO, FileKind.from("a.mp4", FileEntryKind.FILE))
        assertEquals(FileKind.AUDIO, FileKind.from("a.flac", FileEntryKind.FILE))
        assertEquals(FileKind.ARCHIVE, FileKind.from("a.zip", FileEntryKind.FILE))
        assertEquals(FileKind.PDF, FileKind.from("a.pdf", FileEntryKind.FILE))
        assertEquals(FileKind.TEXT, FileKind.from("a.md", FileEntryKind.FILE))
        assertEquals(FileKind.OTHER, FileKind.from("a.bin", FileEntryKind.FILE))
    }
}
