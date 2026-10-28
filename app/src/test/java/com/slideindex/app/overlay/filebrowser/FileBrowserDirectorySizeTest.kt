package com.slideindex.app.overlay.filebrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileBrowserDirectorySizeTest {
    @Test
    fun parseDuKb_readsFirstColumn() {
        assertEquals(12_345L * 1024L, FileBrowserDirectorySize.parseDuKb("12345\t/data/data"))
        assertEquals(42L * 1024L, FileBrowserDirectorySize.parseDuKb("42\t/storage/emulated/0\n"))
        assertEquals(7L * 1024L, FileBrowserDirectorySize.parseDuKb("7 /tmp"))
    }

    @Test
    fun parseDuKb_rejectsGarbageOrEmpty() {
        assertNull(FileBrowserDirectorySize.parseDuKb(""))
        assertNull(FileBrowserDirectorySize.parseDuKb("Permission denied"))
        assertNull(FileBrowserDirectorySize.parseDuKb("size\t/path"))
    }
}
