package com.docvault.app.utils

import java.io.File

/** Path validation shared by metadata loading and filesystem mutation boundaries. */
object VaultPathPolicy {
    fun isSafeSegment(value: String): Boolean =
        value.isNotBlank() && value != "." && value != ".." &&
            !value.contains('/') && !value.contains('\\') &&
            !File(value).isAbsolute && !Regex("^[A-Za-z]:").containsMatchIn(value)

    fun resolve(root: File, relativePath: String): File? {
        if (relativePath.isBlank() || File(relativePath).isAbsolute || Regex("^[A-Za-z]:").containsMatchIn(relativePath)) return null
        val pieces = relativePath.split('/', '\\')
        if (pieces.any { !isSafeSegment(it) }) return null
        return try {
            val canonicalRoot = root.canonicalFile.toPath()
            val resolved = File(root, relativePath).canonicalFile
            resolved.takeIf { it.toPath().startsWith(canonicalRoot) }
        } catch (_: Exception) { null }
    }

    fun safeSubfolder(value: String): String? {
        val cleaned = value.trim()
        return if (cleaned.isEmpty()) "" else cleaned.takeIf(::isSafeSegment)
    }
}
