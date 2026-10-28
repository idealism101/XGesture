package com.slideindex.app.overlay.filebrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileBrowserPathsTest {
    @Test
    fun normalize_mapsSdcardAliasToInternalStorage() {
        assertEquals(
            "/storage/emulated/0/Download",
            FileBrowserPaths.normalize("/sdcard/Download"),
        )
        assertEquals(
            "/storage/emulated/0/Android/data",
            FileBrowserPaths.normalize("/storage/self/primary/Android/data"),
        )
    }

    @Test
    fun normalize_handlesTrailingSlashesAndDotSegments() {
        assertEquals("/", FileBrowserPaths.normalize("/"))
        assertEquals("/a/c/d", FileBrowserPaths.normalize("//a//b/../c/./d/"))
        assertEquals("/x", FileBrowserPaths.normalize("/../../x"))
    }

    @Test
    fun normalize_usesCustomInternalRoot() {
        assertEquals(
            "/storage/emulated/10/Download",
            FileBrowserPaths.normalize(
                path = "/sdcard/Download",
                internalRoot = "/storage/emulated/10",
            ),
        )
    }

    @Test
    fun parent_walksUpToRootAndStops() {
        assertEquals("/a", FileBrowserPaths.parent("/a/b"))
        assertEquals("/", FileBrowserPaths.parent("/a"))
        assertEquals("/", FileBrowserPaths.parent("/storage/emulated/0"))
        assertNull(FileBrowserPaths.parent("/"))
    }

    @Test
    fun child_joinsWithoutDoubleSlash() {
        assertEquals("/a/b", FileBrowserPaths.child("/a", "b"))
        assertEquals("/b", FileBrowserPaths.child("/", "b"))
        assertEquals("/b", FileBrowserPaths.child("", "b"))
    }

    @Test
    fun crumbs_foldsInternalStorageIntoOneSegment() {
        val crumbs = FileBrowserPaths.crumbs("/storage/emulated/0/Android/data")
        assertEquals(
            listOf("/", "/storage/emulated/0", "/storage/emulated/0/Android", "/storage/emulated/0/Android/data"),
            crumbs.map { it.path },
        )
        assertEquals("Android", crumbs[2].label)
        assertEquals("data", crumbs[3].label)
    }

    @Test
    fun crumbs_rootHasOnlyRoot() {
        val crumbs = FileBrowserPaths.crumbs("/")
        assertEquals(1, crumbs.size)
        assertEquals("/", crumbs.single().path)
    }

    @Test
    fun classifiesExternalAndroidDataAndRootOnlyPaths() {
        assertTrue(FileBrowserPaths.isExternal("/storage/emulated/0/Download"))
        assertTrue(FileBrowserPaths.isExternal("/sdcard/Download"))
        assertFalse(FileBrowserPaths.isExternal("/system/app"))
        assertTrue(FileBrowserPaths.isAndroidDataOrObb("/storage/emulated/0/Android/data"))
        assertTrue(FileBrowserPaths.isAndroidDataOrObb("/storage/emulated/0/Android/obb"))
        assertFalse(FileBrowserPaths.isAndroidDataOrObb("/storage/emulated/0/Android/database"))
        assertTrue(FileBrowserPaths.isRootOnly("/data"))
        assertTrue(FileBrowserPaths.isRootOnly("/data/data"))
        assertTrue(FileBrowserPaths.isRootOnly("/root"))
        assertFalse(FileBrowserPaths.isRootOnly("/system"))
    }
}
