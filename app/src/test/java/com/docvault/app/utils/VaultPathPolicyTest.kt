package com.docvault.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VaultPathPolicyTest {
    @Test fun safeSegmentsRejectTraversalAndSeparators() {
        listOf("..", ".", "../escape", "a/b", "a\\b", "/tmp/file", "C:\\temp").forEach {
            assertTrue("expected unsafe: $it", !VaultPathPolicy.isSafeSegment(it))
        }
        assertTrue(VaultPathPolicy.isSafeSegment("manuals"))
    }

    @Test fun resolveStaysInsideCanonicalRoot() {
        val root = Files.createTempDirectory("docvault-path-test").toFile()
        try {
            assertEquals(File(root, "Manuals/book.pdf").canonicalFile, VaultPathPolicy.resolve(root, "Manuals/book.pdf"))
            assertNull(VaultPathPolicy.resolve(root, "../outside.pdf"))
        } finally { root.deleteRecursively() }
    }

    @Test fun safeSubfolderAllowsEmptyOrSingleFolderOnly() {
        assertEquals("", VaultPathPolicy.safeSubfolder(" "))
        assertEquals("Reference", VaultPathPolicy.safeSubfolder(" Reference "))
        assertNull(VaultPathPolicy.safeSubfolder("../escape"))
        assertNull(VaultPathPolicy.safeSubfolder("one/two"))
    }
}
