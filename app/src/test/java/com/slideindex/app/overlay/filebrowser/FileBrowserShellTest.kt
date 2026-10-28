package com.slideindex.app.overlay.filebrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileBrowserShellTest {
    private val parent = "/storage/emulated/0"

    @Test
    fun parseListing_readsStatLinesAndKeepsPipesInNames() {
        val output = """
            XG_COUNT:4
            drwxr-xr-x|4096|1700000000|./Android
            -rw-r--r--|1234|1700000001|./a file.txt
            -rw-r--r--|7|1700000002|./name|with|pipes
            lrwxrwxrwx|10|1700000003|./link
        """.trimIndent()

        val result = FileBrowserShell.parseListing(output, parent)
        val success = result as FileShellResult.Success
        assertEquals(4, success.entries.size)
        assertTrue(!success.truncated)

        val android = success.entries[0]
        assertEquals(FileEntryKind.DIRECTORY, android.kind)
        assertEquals("/storage/emulated/0/Android", android.path)
        assertEquals("drwxr-xr-x", android.permissions)

        val spaced = success.entries[1]
        assertEquals("a file.txt", spaced.name)
        assertEquals(FileEntryKind.FILE, spaced.kind)

        val pipes = success.entries[2]
        assertEquals("name|with|pipes", pipes.name)

        assertEquals(FileEntryKind.SYMLINK, success.entries[3].kind)
    }

    @Test
    fun parseListing_emptyDirectoryIsSuccess() {
        val result = FileBrowserShell.parseListing("XG_COUNT:0", parent)
        val success = result as FileShellResult.Success
        assertTrue(success.entries.isEmpty())
        assertTrue(!success.truncated)
    }

    @Test
    fun parseListing_mapsMarkersToErrors() {
        assertEquals(
            FileBrowserError.NotFound,
            (FileBrowserShell.parseListing("XG_NOT_FOUND", parent) as FileShellResult.Failure).error,
        )
        assertEquals(
            FileBrowserError.NotDirectory,
            (FileBrowserShell.parseListing("XG_NOT_DIR", parent) as FileShellResult.Failure).error,
        )
        assertEquals(
            FileBrowserError.PermissionDenied,
            (FileBrowserShell.parseListing("XG_CD_DENIED", parent) as FileShellResult.Failure).error,
        )
        assertEquals(
            FileBrowserError.PermissionDenied,
            (FileBrowserShell.parseListing("XG_LIST_DENIED", parent) as FileShellResult.Failure).error,
        )
    }

    @Test
    fun parseListing_countWithoutEntriesIsUnknown() {
        val result = FileBrowserShell.parseListing("XG_COUNT:5", parent)
        assertTrue(result is FileShellResult.Failure)
        assertTrue((result as FileShellResult.Failure).error is FileBrowserError.Unknown)
    }

    @Test
    fun parseListing_blankOutputIsUnknown() {
        val result = FileBrowserShell.parseListing("", parent)
        assertTrue(result is FileShellResult.Failure)
        assertTrue((result as FileShellResult.Failure).error is FileBrowserError.Unknown)
    }

    @Test
    fun parseListing_detectsTimeoutText() {
        val result = FileBrowserShell.parseListing("Command timed out after 2s", parent)
        assertEquals(
            FileBrowserError.Timeout,
            (result as FileShellResult.Failure).error,
        )
    }

    @Test
    fun parseListing_truncatesToMaxEntries() {
        val lines = buildString {
            appendLine("XG_COUNT:2001")
            repeat(2001) { index ->
                appendLine("-rw-r--r--|1|1700000000|./file$index")
            }
        }
        val result = FileBrowserShell.parseListing(lines, parent) as FileShellResult.Success
        assertEquals(FileBrowserShell.MAX_ENTRIES, result.entries.size)
        assertTrue(result.truncated)
    }

    @Test
    fun parseListing_skipsMalformedLines() {
        val output = """
            XG_COUNT:2
            garbage
            -rw-r--r--|1|1700000000|./ok.txt
            drwx------|not-a-number|1700000000|./bad
        """.trimIndent()
        val result = FileBrowserShell.parseListing(output, parent) as FileShellResult.Success
        assertEquals(1, result.entries.size)
        assertEquals("ok.txt", result.entries.single().name)
    }

    @Test
    fun buildListingCommand_quotesPathAndCapsOutput() {
        val command = FileBrowserShell.buildListingCommand("/sdcard/a b'c")
        assertTrue(command.contains("cd '/storage/emulated/0/a b'\\''c'"))
        assertTrue(command.contains("stat -c '%A|%s|%Y|%n'"))
        assertTrue(command.contains("head -n 2001"))
        assertTrue(command.contains("XG_COUNT"))
    }
}
