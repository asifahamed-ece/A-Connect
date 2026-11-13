package com.aplusconnect.companion.file

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.aplusconnect.companion.data.CompanionSettings
import com.aplusconnect.companion.protocol.FeaturePayloads.FileEntry
import java.io.InputStream

/**
 * The phone's files, listed for the computer.
 *
 * The user picks one folder with the system folder picker, which hands back a
 * document tree the app keeps a permission for; the choice is remembered. This
 * is the only storage access the app has, and it needs no storage permission,
 * because the picker is the permission.
 *
 * Browsing is read-only: the computer may look at the folder and may ask for a
 * copy of a file, and nothing here can delete, rename, move or create anything.
 * [com.aplusconnect.companion.file.PhoneFileBrowserTest] guards that.
 */
class PhoneFileBrowser(
    private val context: Context,
    private val settings: CompanionSettings,
) {

    /** Remembers the folder the user shared. The caller takes the permission. */
    fun setRoot(treeUri: Uri) {
        settings.browseRootUri = treeUri.toString()
        Log.i(TAG, "shared a folder with the computer")
    }

    /** The shared folder, or null while the user has not picked one. */
    fun root(): Uri? {
        val stored = settings.browseRootUri
        if (stored.isEmpty()) return null
        return try {
            Uri.parse(stored)
        } catch (e: Exception) {
            Log.w(TAG, "the shared folder is not a usable address: ${e.message}")
            null
        }
    }

    /**
     * Lists one folder.
     *
     * `path` is the document id of the folder to list, as the phone handed it
     * out before; the empty string means the root of the shared folder. Only
     * one level is read per call, so a huge folder cannot flood the channel.
     *
     * Returns null when there is no shared folder, or when the folder cannot be
     * read - a folder that has been deleted, or a permission that was revoked.
     */
    fun list(path: String): List<FileEntry>? {
        val tree = root() ?: return null

        val parentId = if (path.isEmpty()) {
            try {
                DocumentsContract.getTreeDocumentId(tree)
            } catch (e: Exception) {
                Log.w(TAG, "the shared folder has no document id: ${e.message}")
                return null
            }
        } else {
            path
        }

        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val entries = ArrayList<FileEntry>()

        try {
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(1)
                    entries.add(
                        entryFor(
                            displayName = cursor.getString(0),
                            size = if (cursor.isNull(2)) 0L else cursor.getLong(2),
                            mime = mime,
                            isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        )
                    )
                }
            } ?: run {
                Log.w(TAG, "the shared folder did not answer")
                return null
            }
        } catch (e: Exception) {
            // A folder that cannot be read must not kill the connection: the
            // caller answers with an empty list.
            Log.w(TAG, "could not list the shared folder: ${e.message}")
            return null
        }

        return sortEntries(entries)
    }

    /** A readable stream of one file, or null when it is gone. */
    fun open(path: String): InputStream? {
        val document = documentUri(path) ?: return null
        return try {
            context.contentResolver.openInputStream(document)
        } catch (e: Exception) {
            Log.w(TAG, "could not open the requested file: ${e.message}")
            null
        }
    }

    /** The address of one document inside the shared folder. */
    fun documentUri(path: String): Uri? {
        val tree = root() ?: return null
        if (path.isEmpty()) return null
        return try {
            DocumentsContract.buildDocumentUriUsingTree(tree, path)
        } catch (e: Exception) {
            Log.w(TAG, "the requested path is not inside the shared folder: ${e.message}")
            null
        }
    }

    companion object {
        const val TAG = "AplusConnect"

        /**
         * One row of a listing.
         *
         * A folder's reported size is meaningless and its mime type is the
         * folder marker, so both are dropped: the desktop would otherwise draw
         * a byte count next to a folder and a mime type nobody can use.
         */
        fun entryFor(
            displayName: String?,
            size: Long,
            mime: String?,
            isDir: Boolean = false,
        ): FileEntry = FileEntry(
            name = displayName?.takeIf { it.isNotBlank() } ?: "unnamed",
            size = if (isDir) 0L else size,
            isDir = isDir,
            mime = if (isDir) "" else (mime ?: ""),
        )

        /** Folders first, then files, each by name. */
        fun sortEntries(entries: List<FileEntry>): List<FileEntry> =
            entries.sortedWith(
                compareByDescending<FileEntry> { it.isDir }
                    .thenBy { it.name.lowercase() }
            )
    }
}
