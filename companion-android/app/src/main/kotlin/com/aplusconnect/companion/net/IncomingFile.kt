package com.aplusconnect.companion.net

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class DownloadDestination(private val context: Context) {

    sealed class Result {
        data class Saved(val uri: Uri, val where: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun save(source: File, name: String, treeUri: String?): Result {
        val safeName = FileNames.sanitize(name)
        val mime = FileNames.mimeOf(safeName)
        return try {
            when {
                // A tree the app still holds a write grant for: the folder the
                // user chose, used as chosen.
                canWriteTree(treeUri) -> saveToTree(source, safeName, mime, Uri.parse(treeUri!!))
                // No usable tree: put the file in the visible Downloads folder.
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> saveToDownloads(source, safeName, mime)
                else -> saveToAppFolder(source, safeName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not save $safeName: ${e.message}")
            Result.Failed(e.message ?: "the folder did not accept the file")
        }
    }

    /**
     * Whether the saved tree is still usable.
     *
     * A tree URI survives an app update but not a reinstall: the persisted
     * permission goes with the old install, and every write through it then
     * fails with "requires that you obtain access using ACTION_OPEN_DOCUMENT".
     * Checking the grant before using it keeps a stale choice from blocking
     * every incoming file.
     */
    private fun canWriteTree(treeUri: String?): Boolean {
        if (treeUri.isNullOrEmpty()) return false
        val uri = Uri.parse(treeUri)
        val granted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        if (!granted) Log.i(TAG, "the chosen folder is no longer writable, using Downloads")
        return granted
    }

    /** Whether the saved folder can still be written to. */
    fun canWrite(treeUri: String?): Boolean = canWriteTree(treeUri)

    private fun saveToTree(source: File, name: String, mime: String, tree: Uri): Result {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val document = DocumentsContract.createDocument(context.contentResolver, parent, mime, name)
            ?: throw IllegalStateException("the chosen folder did not accept a new file - pick it again")
        try {
            copyInto(source, context.contentResolver.openOutputStream(document))
        } catch (e: Exception) {
            try { DocumentsContract.deleteDocument(context.contentResolver, document) } catch (_: Exception) {}
            throw e
        }
        Log.i(TAG, "saved $name into the chosen folder")
        return Result.Saved(document, "chosen folder")
    }

    /**
     * Saves into the visible Downloads/A+ Connect folder through MediaStore.
     *
     * The insert goes into the generic media collection rather than the
     * Downloads collection: on some Android builds the Downloads collection
     * hands back a document-style URI that the app may not open for writing
     * without a document-tree grant, while the media collection returns a URI
     * the app owns and can write straight away. Both land in the same folder,
     * so the file is visible in the phone's Downloads either way.
     */
    private fun saveToDownloads(source: File, name: String, mime: String): Result {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
        if (uri != null) {
            try {
                val out = resolver.openOutputStream(uri)
                    ?: throw IllegalStateException("the phone's Downloads folder did not open the file for writing")
                copyInto(source, out)
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                Log.i(TAG, "saved $name into Downloads/$SUBFOLDER")
                return Result.Saved(uri, "Downloads/$SUBFOLDER")
            } catch (e: Exception) {
                try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                Log.w(TAG, "MediaStore would not take $name: ${e.message}")
            }
        }
        // The app holds all-files access, so it can write the public folder
        // directly. The file lands in the same visible place, so the user
        // finds it exactly where a MediaStore save would have put it.
        return saveToPublicDownloads(source, name)
    }

    /** Writes straight into the public Downloads folder, without MediaStore. */
    private fun saveToPublicDownloads(source: File, name: String): Result {
        val folder = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUBFOLDER,
        )
        if (!folder.exists() && !folder.mkdirs()) {
            throw IllegalStateException("the phone's Downloads folder could not be created")
        }
        val target = uniqueFile(folder, name)
        copyInto(source, FileOutputStream(target))
        Log.i(TAG, "saved $name into ${target.absolutePath}")
        return Result.Saved(Uri.fromFile(target), "Downloads/$SUBFOLDER")
    }

    private fun saveToAppFolder(source: File, name: String): Result {
        val folder = appFolder()
        if (!folder.exists()) folder.mkdirs()
        val target = uniqueFile(folder, name)
        copyInto(source, FileOutputStream(target))
        return Result.Saved(Uri.fromFile(target), folder.absolutePath)
    }

    private fun copyInto(source: File, out: OutputStream?) {
        val stream = out ?: throw IllegalStateException("the folder did not open the file for writing")
        stream.use { o -> source.inputStream().use { it.copyTo(o) } }
    }

    private fun uniqueFile(folder: File, name: String): File {
        var target = File(folder, name)
        var n = 1
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        while (target.exists()) target = File(folder, "$base (${n++})$ext")
        return target
    }

    fun appFolder(): File =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")

    companion object {
        const val TAG = "AplusConnect"
        const val SUBFOLDER = "A+ Connect"
    }
}

object FileNames {
    fun sanitize(name: String): String {
        val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9 ._()+-]"), "_").trim()
        return cleaned.ifEmpty { "received-file" }
    }

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "apk" -> "application/vnd.android.package-archive"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "pdf" -> "application/pdf"
        "txt", "log" -> "text/plain"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "zip" -> "application/zip"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else -> "application/octet-stream"
    }
}

data class Received(val file: File, val name: String, val mime: String)

class IncomingFile(private val tempFolder: File) {

    private var currentId: String? = null
    private var name: String = ""
    private var mime: String = ""
    private var expected: Long = 0
    private var tempFile: File? = null
    private var output: OutputStream? = null

    var received: Long = 0
        private set

    val activeId: String? get() = currentId

    @Synchronized
    fun start(id: String, fileName: String, size: Long, fileMime: String): Boolean {
        if (currentId != null) {
            Log.w(TAG, "a transfer is already running, ignoring $fileName")
            return false
        }
        return try {
            // The cache folder (and any parents) may not exist yet on a fresh
            // install. Create it so incoming PC->phone transfers can land here.
            if (!tempFolder.exists() && !tempFolder.mkdirs()) {
                throw IllegalStateException("the incoming transfer folder could not be created")
            }
            val temp = File(tempFolder, "incoming-$id.part")
            if (temp.exists()) temp.delete()
            currentId = id
            name = fileName
            mime = fileMime
            expected = size
            tempFile = temp
            received = 0
            output = FileOutputStream(temp).buffered(256 * 1024)
            Log.i(TAG, "receiving $fileName ($size bytes)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not start receiving $fileName: ${e.message}")
            reset()
            false
        }
    }

    @Synchronized
    fun chunk(id: String, bytes: ByteArray): Boolean {
        if (id != currentId) return false
        return try {
            output?.write(bytes)
            received += bytes.size
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not write the chunk: ${e.message}")
            reset()
            false
        }
    }

    sealed class Outcome {
        data class Complete(val received: Received) : Outcome()
        data class Broken(val reason: String) : Outcome()
        object NotRunning : Outcome()
    }

    @Synchronized
    fun finish(id: String): Outcome {
        if (id != currentId) return Outcome.NotRunning
        try { output?.close() } catch (_: Exception) {}
        output = null

        val file = tempFile
        val size = received
        val want = expected
        tempFile = null
        currentId = null
        received = 0

        if (file == null || !file.exists()) return Outcome.Broken("the file was not written")
        if (want > 0 && file.length() != want) {
            file.delete()
            return Outcome.Broken("only ${file.length()} of $want bytes arrived")
        }
        if (file.length() == 0L && want != 0L) {
            file.delete()
            return Outcome.Broken("the file arrived empty")
        }
        Log.i(TAG, "received $name ($size bytes)")
        return Outcome.Complete(Received(file, name, mime))
    }

    @Synchronized
    fun cancel(id: String): Boolean {
        if (id != currentId) return false
        reset()
        return true
    }

    @Synchronized
    fun reset() {
        try { output?.close() } catch (_: Exception) {}
        output = null
        tempFile?.delete()
        tempFile = null
        currentId = null
        received = 0
    }

    companion object {
        const val TAG = "AplusConnect"
    }
}