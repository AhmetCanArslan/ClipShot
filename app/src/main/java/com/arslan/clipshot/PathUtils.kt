package com.arslan.clipshot

import android.net.Uri
import android.os.Environment
import java.io.File

/**
 * Helpers for turning a SAF tree [Uri] (from ACTION_OPEN_DOCUMENT_TREE) into a
 * raw filesystem path that [android.os.FileObserver] and [File] can use.
 *
 * Only the "primary" external volume is resolvable to a stable raw path; other
 * volumes (SD cards / USB) return null and the caller should keep the old path.
 */
object PathUtils {

    /** @return the absolute path for [uri], or null if it can't be resolved. */
    fun treeUriToPath(uri: Uri): String? {
        val docId = treeDocumentId(uri) ?: return null
        val parts = docId.split(":", limit = 2)
        val type = parts[0]
        val relative = parts.getOrElse(1) { "" }
        if (!type.equals("primary", ignoreCase = true)) return null
        val root = Environment.getExternalStorageDirectory().absolutePath
        return if (relative.isEmpty()) root else File(root, relative).absolutePath
    }

    /** Extracts the tree documentId segment, e.g. "primary:Pictures/Screenshots". */
    private fun treeDocumentId(uri: Uri): String? {
        // Path looks like /tree/primary:Pictures/Screenshots
        val segments = uri.pathSegments
        val treeIndex = segments.indexOf("tree")
        return if (treeIndex >= 0 && treeIndex + 1 < segments.size) {
            segments[treeIndex + 1]
        } else {
            null
        }
    }
}
