package com.aplusconnect.companion.file

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.webkit.MimeTypeMap
import com.aplusconnect.companion.protocol.FeaturePayloads.FileEntry
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * The phone's files, listed for the computer.
 *
 * This browses the whole of the phone's shared storage. Reaching it needs
 * Android's "all files access" special permission (MANAGE_EXTERNAL_STORAGE),
 * which the user grants once from Settings. The system folder picker cannot be
 * used for this: since Android 11 it refuses to grant the storage root, the SD
 * card root and the `Download` directory, so a picker-based browser can never
 * show the folders a user actually wants. All files access is not an
 * accessibility service, so it does not affect contactless payments.
 *
 * Paths are RELATIVE to the storage root: `""` is the root, `Download/a.pdf`
 * is a file inside it. Relative paths mean the computer can never name a path
 * outside the phone's shared storage, whatever it sends; [within] enforces
 * that even against `..` and a symlink.
 *
 * Browsing is read-only: the computer may look and may ask for a copy, and
 * nothing here can delete, rename, move or create anything.
 * [PhoneFileBrowserTest] guards that at the source level.
 */
class PhoneFileBrowser(private val context: Context) {

    /** The storage root, or null on a device that has no shared storage. */
    fun root(): File? {
        val root = Environment.getExternalStorageDirectory() ?: return null
        return if (root.isDirectory) root else null
    }

    /** Whether the user has granted the access this browser needs. */
    fun isGranted(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        }
        // Before Android 11 the ordinary read permission is the whole story.
        return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * The intent that asks the user for all-files access.
     *
     * The system page is the only way: this permission has no runtime dialog,
     * deliberately, because granting it is a bigger decision than a tap on a
     * prompt while an app is starting.
     */
    fun grantIntent(): android.content.Intent {
        val specific = android.content.Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            android.net.Uri.parse("package:${context.packageName}")
        )
        // Older builds know the general page only; a device that has neither
        // falls back to the app's own settings page, which says why.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) specific else
            android.content.Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:${context.packageName}")
            )
    }

    /**
     * Lists one folder, or null when it cannot be read.
     *
     * `path` is a relative path as handed out by [list]; the empty string is
     * the root. One level per call, so a huge folder cannot flood the channel.
     */
    fun list(path: String): List<FileEntry>? {
        if (!isGranted()) return null
        val root = root() ?: return null
        val dir = within(root, path) ?: run {
            Log.w(TAG, "the computer asked for a folder outside the phone's storage")
            return null
        }
        if (!dir.isDirectory) return null

        val children = try {
            dir.listFiles()
        } catch (e: Exception) {
            Log.w(TAG, "could not list a folder: ${e.message}")
            null
        } ?: return null

        // The ids are built from the folder's own relative path, not by
        // canonicalising each child: on Android's FUSE storage every
        // canonicalise is a syscall, and a folder with 650 files would pay for
        // it 650 times to produce what the input already told us.
        val dirId = idOf(root, dir)
        return sortEntries(
            children.map { child ->
                entryFor(child, if (dirId.isEmpty()) child.name else "$dirId/${child.name}")
            }
        )
    }

    /** A readable stream of one file, or null when it is gone. */
    fun open(path: String): InputStream? {
        if (!isGranted()) return null
        val root = root() ?: return null
        val file = within(root, path) ?: return null
        if (!file.isFile) return null
        return try {
            FileInputStream(file)
        } catch (e: Exception) {
            Log.w(TAG, "could not open the requested file: ${e.message}")
            null
        }
    }

    /** The absolute file for a relative path, or null when it escapes the root. */
    fun resolve(path: String): File? {
        val root = root() ?: return null
        return within(root, path)
    }

    /** One row of a listing, carrying its relative path as the id. */
    private fun entryFor(file: File, id: String): FileEntry {
        val isDir = file.isDirectory
        return row(
            id = id,
            name = file.name,
            size = if (isDir) 0L else file.length(),
            mime = if (isDir) "" else mimeOf(file.name),
            isDir = isDir,
        )
    }

    companion object {
        const val TAG = "AplusConnect"

        /** The type the computer shows next to a row; empty when it is unknown. */
        fun mimeOf(name: String): String {
            val extension = name.substringAfterLast('.', "").lowercase()
            if (extension.isEmpty()) return ""
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: ""
        }

        /**
         * Resolves a relative path inside [root], refusing anything that
         * escapes it.
         *
         * `..` is rejected outright rather than normalised, and the answer is
         * canonicalised, so a symlink pointing outside the phone's storage is
         * refused too. A path that cannot be resolved is not a path this app
         * will read.
         */
        fun within(root: File, relative: String): File? {
            val cleaned = relative.trim().trimStart('/')
            if (cleaned.split('/').any { it == ".." }) return null
            val candidate = if (cleaned.isEmpty()) root else File(root, cleaned)
            return try {
                val rootPath = root.canonicalFile
                val target = candidate.canonicalFile
                if (target == rootPath) return target
                if (target.path.startsWith(rootPath.path + File.separator)) target else null
            } catch (e: Exception) {
                null
            }
        }

        /** A file's path relative to the root, with forward slashes. */
        fun idOf(root: File, file: File): String {
            val rootPath = try {
                root.canonicalPath
            } catch (e: Exception) {
                root.absolutePath
            }
            val full = try {
                file.canonicalPath
            } catch (e: Exception) {
                file.absolutePath
            }
            if (full == rootPath) return ""
            val prefix = if (rootPath.endsWith(File.separator)) rootPath else rootPath + File.separator
            return if (full.startsWith(prefix)) full.substring(prefix.length) else file.name
        }

        /**
         * One row of a listing.
         *
         * A folder's reported size is meaningless and its mime type is the
         * folder marker, so both are dropped: the desktop would otherwise draw
         * a byte count next to a folder and a mime type nobody can use.
         */
        fun row(
            id: String?,
            name: String?,
            size: Long,
            mime: String?,
            isDir: Boolean = false,
        ): FileEntry = FileEntry(
            id = id ?: "",
            name = name?.takeIf { it.isNotBlank() } ?: "unnamed",
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