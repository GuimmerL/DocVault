package com.docvault.app.repository

import org.junit.Assert.assertTrue
import org.junit.Test
import com.docvault.app.utils.VaultPathPolicy

/** Regression cases for paths accepted from metadata or folder-picker inputs. */
class DocumentRepositorySecurityTest {
    @Test fun selectedFolderRelativePathsRejectTraversalAndAbsolutePaths() {
        listOf("../escape.pdf", "Manuals/../../escape.pdf", "/etc/passwd", "C:\\temp\\x.pdf", "Manuals\\..\\escape.pdf").forEach { path ->
            assertTrue("unsafe path should be rejected: $path", VaultPathPolicy.resolve(java.io.File("/tmp/docvault"), path) == null)
        }
    }
}
