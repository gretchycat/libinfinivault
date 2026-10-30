package com.infinifolder.archive

import android.net.Uri

data class ArchiveEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
    val mimeType: String,
    val isSymbolicLink: Boolean = false,
    val linkTarget: String? = null,
    val permissionsMode: Int? = null,
    val owner: String? = null,
    val group: String? = null,
    val readError: String? = null,
    val recoveryNote: String? = null,
    val partialRecoveryAvailable: Boolean = false
)

class ArchiveTree(
    val sourceUri: Uri?,
    val entriesByParent: Map<String, List<ArchiveEntry>>,
    private val entriesByPath: Map<String, ArchiveEntry>,
    val warnings: List<String> = emptyList()
) {
    fun list(parentPath: String): List<ArchiveEntry> {
        val cleanParent = parentPath.replace('\\', '/').trim().removePrefix("/").removeSuffix("/")
        return entriesByParent[cleanParent]
            ?: entriesByParent.entries.firstOrNull { it.key.equals(cleanParent, ignoreCase = true) }?.value
            ?: emptyList()
    }

    fun getEntry(path: String): ArchiveEntry? {
        val cleanPath = path.replace('\\', '/').trim().removePrefix("/").removeSuffix("/")
        return entriesByPath[cleanPath]
            ?: entriesByPath.entries.firstOrNull { it.key.equals(cleanPath, ignoreCase = true) }?.value
    }
}

