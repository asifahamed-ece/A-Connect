package com.aplusconnect.companion.net

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Where files received from the computer are saved.
 *
 * The phone picks a folder with the system folder picker, which hands back a
 * document tree the app keeps a permission for. Without one, files land in
 * the app's own downloads folder, which is private to the app - the shared
 * Downloads folder needs a permission the store does not hand out lightly.
 */
class DownloadDestination(private val context: Context) {

    /** Copies a received file into the chosen folder. Returns where it landed. */
    fun save(source: File, name: String, treeUri: String?): Uri? {
        val safeName = sanitize(name)

        if (!treeUri.isNullOrEmpty()) {
            try {
                val tree = Uri.parse(treeUri)
                // createDocument needs the document inside the tree, not the
                // tree itself.
                val parent = DocumentsContract.buildDocumentUriUsingTree(
                    tree,
                    DocumentsContract.getTreeDocumentId(tree),
                )
                val document = DocumentsContract.createDocument(
                    context.contentResolver,
                    parent,
                    mimeOf(name),
                    safeName,
                ) ?: throw IllegalStateException("the folder did not accept a new file")

                context.contentResolver.openOutputStream(document)?.use { out ->
                    source.inputStream().use { input -> input.copyTo(out) }
                } ?: throw IllegalStateException("the folder did not open the file")
                Log.i(TAG, "saved $safeName into the chosen folder")
                return document
            } catch (e: Exception) {
                // A folder that stops working must not lose the file: fall
                // back to the app's own downloads folder.
                Log.w(TAG, "could not save into the chosen folder: ${e.message}")
            }
        }

        val folder = defaultFolder()
        if (!folder.exists()) folder.mkdirs()
        val target = File(folder, safeName)
        source.inputStream().use { input ->
            FileOutputStream(target).use { out -> input.copyTo(out) }
        }
        Log.i(TAG, "saved $safeName into ${folder.absolutePath}")
        return Uri.fromFile(target)
    }

    /** The app's own downloads folder, used until the user picks one. */
    fun defaultFolder(): File =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")

    /** A name that is safe on any file system. */
    private fun sanitize(name: String): String {
        val cleaned = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        return cleaned.ifEmpty { "received-file" }
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    /** A file that arrived, with the name it was sent under. */
    data class Received(val file: File, val name: String, val mime: String)

    companion object {
        const val TAG = "AplusConnect"
    }
}

/**
 * Assembles a file that arrives in chunks over the companion connection.
 *
 * The bytes are written to a temporary file first and only moved into place
 * when the last chunk arrives, so an interrupted transfer never leaves a
 * half-written file in the downloads folder.
 */
class IncomingFile(private val tempFolder: File) {

    private var currentId: String? = null
    private var name: String = ""
    private var mime: String = ""
    private var tempFile: File? = null
    private var output: OutputStream? = null
    private var received: Long = 0

    /** Starts a new transfer. Returns false if one is already running. */
    fun start(id: String, fileName: String, size: Long, fileMime: String): Boolean {
        if (currentId != null) {
            Log.w(TAG, "a transfer is already running, ignoring $fileName")
            return false
        }
        try {
            tempFolder.mkdirs()
            val temp = File(tempFolder, "incoming-${System.currentTimeMillis()}")
            currentId = id
            name = fileName
            mime = fileMime
            tempFile = temp
            received = 0
            output = FileOutputStream(temp)
            Log.i(TAG, "receiving $fileName ($size bytes)")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "could not start the transfer: ${e.message}")
            reset()
            return false
        }
    }

    /** Appends a chunk. */
    fun chunk(id: String, bytes: ByteArray) {
        if (id != currentId) return
        try {
            output?.write(bytes)
            received += bytes.size
        } catch (e: Exception) {
            Log.w(TAG, "could not write the chunk: ${e.message}")
            reset()
        }
    }

    /**
     * Finishes the transfer and returns the assembled file, or null.
     *
     * The file is handed over, not thrown away: the caller moves it into the
     * chosen folder and deletes it afterwards.
     */
    fun finish(id: String): Received? {
        if (id != currentId) return null
        try {
            output?.close()
        } catch (e: Exception) {
            Log.w(TAG, "could not close the file: ${e.message}")
        }
        output = null

        val file = tempFile
        val size = received
        tempFile = null
        currentId = null
        received = 0

        if (file != null && file.exists() && file.length() > 0) {
            Log.i(TAG, "received $name ($size bytes)")
            return Received(file, name, mime)
        }
        file?.delete()
        return null
    }

    /** Throws the partial file away. */
    fun reset() {
        try {
            output?.close()
        } catch (e: Exception) {
            // Nothing to do: the stream is already gone.
        }
        output = null
        tempFile?.delete()
        tempFile = null
        currentId = null
        received = 0
    }

    /** A file that arrived, with the name it was sent under. */
    data class Received(val file: File, val name: String, val mime: String)

    companion object {
        const val TAG = "AplusConnect"
    }
}
