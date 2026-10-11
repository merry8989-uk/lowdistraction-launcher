package com.lowdistraction.launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** One file or folder found while walking storage. */
data class FileEntry(
    val name: String,
    val uri: String,
    val tree: String,
    val mime: String,
    val isDirectory: Boolean,
    val size: Long,
    val parent: String,
    /** Absolute path when this came from a direct filesystem walk. */
    val filePath: String? = null
) {
    fun where(): String = if (parent.isEmpty()) "Storage" else parent

    fun kind(): String = when {
        mime.startsWith("image/") -> "Image"
        mime.startsWith("video/") -> "Video"
        mime.startsWith("audio/") -> "Audio"
        mime == "application/pdf" -> "PDF"
        mime.contains("word") || mime.contains("opendocument.text") -> "Document"
        mime.contains("sheet") || mime.contains("excel") -> "Sheet"
        mime.contains("presentation") || mime.contains("powerpoint") -> "Slides"
        mime.startsWith("text/") -> "Text"
        mime.contains("zip") || mime.contains("compressed") -> "Archive"
        mime.startsWith("application/vnd.android.package") -> "App"
        else -> "File"
    }

    fun sizeText(): String {
        if (isDirectory || size <= 0) return ""
        val kb = size / 1024.0
        return when {
            kb < 1 -> "$size B"
            kb < 1024 -> "${kb.toInt()} KB"
            else -> String.format(Locale.US, "%.1f MB", kb / 1024.0)
        }
    }
}

/**
 * Searches storage.
 *
 * The preferred route is **All files access** (`MANAGE_EXTERNAL_STORAGE`), which
 * lets the app walk the whole of internal storage and any SD card directly — no
 * folder picking at all. When that has not been granted, folders the user shared
 * through the system picker are used instead, and if neither is available file
 * search simply stays off.
 *
 * The walk is cached on disk because re-walking a whole phone on every keystroke
 * would be far too slow.
 */
object FileIndex {

    private const val PREFS = "file_search"
    private const val KEY_ROOTS = "roots"
    private const val KEY_INDEXED_AT = "indexed_at"
    private const val CACHE_FILE = "file_index.json"
    private const val MAX_ENTRIES = 40000
    private const val MAX_DEPTH = 12

    @Volatile
    private var cache: List<FileEntry>? = null

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // -------------------------------------------------------- all files access
    /** True when the app may read the whole of storage. */
    fun hasAllFilesAccess(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else {
            c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** True when file search has anything at all to work with. */
    fun isEnabled(c: Context): Boolean = hasAllFilesAccess(c) || roots(c).isNotEmpty()

    /** True when the app is searching the whole of storage rather than picked folders. */
    fun usesFullStorage(c: Context): Boolean = hasAllFilesAccess(c)

    private fun fileRoots(c: Context): List<File> {
        val out = ArrayList<File>()
        runCatching { Environment.getExternalStorageDirectory() }.getOrNull()?.let { out.add(it) }
        runCatching {
            for (dir in c.getExternalFilesDirs(null)) {
                if (dir == null) continue
                // /storage/VOL/Android/data/<pkg>/files -> climb back to /storage/VOL
                var climb: File? = dir
                repeat(4) { climb = climb?.parentFile }
                val root = climb ?: continue
                if (root.path.startsWith("/storage/") && root.canRead() && root !in out) {
                    out.add(root)
                }
            }
        }
        return out
    }

    // ------------------------------------------------------- fallback folders
    fun roots(c: Context): List<Uri> = prefs(c)
        .getStringSet(KEY_ROOTS, emptySet())!!
        .mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }

    fun addRoot(c: Context, uri: Uri) {
        val next = roots(c).map { it.toString() }.toSet() + uri.toString()
        prefs(c).edit().putStringSet(KEY_ROOTS, next).apply()
        cache = null
    }

    fun removeRoot(c: Context, uri: Uri) {
        val next = roots(c).map { it.toString() }.toSet() - uri.toString()
        prefs(c).edit().putStringSet(KEY_ROOTS, next).apply()
        cache = null
    }

    /** Forgets the picked folders and hands their permissions back. */
    fun releaseFallbackFolders(c: Context) {
        for (root in roots(c)) {
            runCatching {
                c.contentResolver.releasePersistableUriPermission(
                    root, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        prefs(c).edit().putStringSet(KEY_ROOTS, emptySet()).apply()
        cache = null
    }

    fun indexedAt(c: Context): Long = prefs(c).getLong(KEY_INDEXED_AT, 0L)

    fun count(c: Context): Int = load(c).size

    /** Throws away everything the index knows. */
    fun clearCache(c: Context) {
        cache = emptyList()
        runCatching { File(c.filesDir, CACHE_FILE).delete() }
        prefs(c).edit().putLong(KEY_INDEXED_AT, 0L).apply()
    }

    fun load(c: Context): List<FileEntry> {
        cache?.let { return it }
        val file = File(c.filesDir, CACHE_FILE)
        val list = if (file.exists()) {
            runCatching { parse(file.readText()) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        cache = list
        return list
    }

    /** Walks storage and caches what it finds. Blocking — call it on a thread. */
    fun rebuild(c: Context) {
        val out = ArrayList<FileEntry>()
        val seen = HashSet<String>()

        if (hasAllFilesAccess(c)) {
            for (root in fileRoots(c)) {
                runCatching { walkFile(root, "", 0, out, seen) }
            }
        }
        // Fallback: folders the user shared through the system picker.
        for (root in roots(c)) {
            runCatching {
                walk(c, root, DocumentsContract.getTreeDocumentId(root), "", 0, out, seen)
            }
        }

        cache = out
        runCatching { File(c.filesDir, CACHE_FILE).writeText(serialize(out)) }
        prefs(c).edit().putLong(KEY_INDEXED_AT, System.currentTimeMillis()).apply()
    }

    // ------------------------------------------------------------- the walks
    private fun walkFile(
        dir: File,
        parentPath: String,
        depth: Int,
        out: MutableList<FileEntry>,
        seen: MutableSet<String>
    ) {
        if (depth > MAX_DEPTH || out.size >= MAX_ENTRIES) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            if (out.size >= MAX_ENTRIES) return
            val name = child.name
            if (name.startsWith(".")) continue
            val isDir = child.isDirectory
            // The app-private Android/data area is not readable anyway.
            if (isDir && parentPath.isEmpty() && (name == "Android" || name == "data")) continue
            out.add(
                FileEntry(
                    name = name,
                    uri = "",
                    tree = "",
                    mime = if (isDir) DocumentsContract.Document.MIME_TYPE_DIR else mimeFor(name),
                    isDirectory = isDir,
                    size = child.length(),
                    parent = parentPath,
                    filePath = child.absolutePath
                )
            )
            if (isDir && seen.add(child.absolutePath)) {
                val path = if (parentPath.isEmpty()) name else "$parentPath/$name"
                walkFile(child, path, depth + 1, out, seen)
            }
        }
    }

    private fun walk(
        c: Context,
        tree: Uri,
        parentDocId: String,
        parentPath: String,
        depth: Int,
        out: MutableList<FileEntry>,
        seen: MutableSet<String>
    ) {
        if (depth > MAX_DEPTH || out.size >= MAX_ENTRIES) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        runCatching {
            c.contentResolver.query(childrenUri, projection, null, null, null)?.use { cur ->
                while (cur.moveToNext() && out.size < MAX_ENTRIES) {
                    val id = cur.getString(0) ?: continue
                    val name = cur.getString(1) ?: continue
                    val mime = cur.getString(2).orEmpty()
                    val size = if (cur.isNull(3)) 0L else cur.getLong(3)
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    if (name.startsWith(".")) continue
                    if (isDir && parentPath.isEmpty() && name == "Android") continue

                    val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    out.add(
                        FileEntry(name, docUri.toString(), tree.toString(), mime, isDir, size, parentPath)
                    )
                    if (isDir && seen.add(tree.toString() + "/" + id)) {
                        val path = if (parentPath.isEmpty()) name else "$parentPath/$name"
                        walk(c, tree, id, path, depth + 1, out, seen)
                    }
                }
            }
        }
    }

    /** Direct children of a folder, for the folder-open dialog. */
    fun children(c: Context, entry: FileEntry, limit: Int = 200): List<FileEntry> {
        val path = entry.filePath
        if (path != null) {
            val kids = runCatching { File(path).listFiles() }.getOrNull() ?: return emptyList()
            return kids.filter { !it.name.startsWith(".") }
                .take(limit)
                .map { child ->
                    val isDir = child.isDirectory
                    FileEntry(
                        name = child.name,
                        uri = "",
                        tree = "",
                        mime = if (isDir) DocumentsContract.Document.MIME_TYPE_DIR
                        else mimeFor(child.name),
                        isDirectory = isDir,
                        size = child.length(),
                        parent = entry.name,
                        filePath = child.absolutePath
                    )
                }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
        }

        val tree = runCatching { Uri.parse(entry.tree) }.getOrNull() ?: return emptyList()
        val docId = runCatching { DocumentsContract.getDocumentId(Uri.parse(entry.uri)) }.getOrNull()
            ?: return emptyList()
        val out = ArrayList<FileEntry>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        runCatching {
            c.contentResolver.query(childrenUri, projection, null, null, null)?.use { cur ->
                while (cur.moveToNext() && out.size < limit) {
                    val id = cur.getString(0) ?: continue
                    val name = cur.getString(1) ?: continue
                    val mime = cur.getString(2).orEmpty()
                    val size = if (cur.isNull(3)) 0L else cur.getLong(3)
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    out.add(FileEntry(name, docUri.toString(), tree.toString(), mime, isDir, size, entry.where()))
                }
            }
        }
        return out.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
    }

    // ---------------------------------------------------------------- opening
    /** A shareable content URI for an entry, whatever route found it. */
    fun contentUri(c: Context, entry: FileEntry): Uri? {
        val path = entry.filePath
        if (path != null) {
            return runCatching {
                FileProvider.getUriForFile(c, c.packageName + FILE_PROVIDER_SUFFIX, File(path))
            }.getOrNull()
        }
        return runCatching { Uri.parse(entry.uri) }.getOrNull()
    }

    fun openIntent(c: Context, entry: FileEntry): Intent? {
        val uri = contentUri(c, entry) ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, entry.mime.ifEmpty { "*/*" })
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ---------------------------------------------------------------- search
    /** Name match: exact first, then starts-with, then contains. */
    fun search(c: Context, query: String, limit: Int): List<FileEntry> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.length < 2) return emptyList()
        val scored = ArrayList<Pair<Int, FileEntry>>()
        for (e in load(c)) {
            val n = e.name.lowercase(Locale.ROOT)
            val score = when {
                n == q -> 0
                n.startsWith(q) -> 1
                n.contains(q) -> 2
                else -> continue
            }
            scored.add(score to e)
            if (scored.size >= 5000) break
        }
        return scored
            .sortedWith(compareBy({ it.first }, { it.second.name.length }))
            .take(limit)
            .map { it.second }
    }

    private fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext.isEmpty()) return ""
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext).orEmpty()
    }

    // ------------------------------------------------------------- persistence
    private fun serialize(list: List<FileEntry>): String {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject()
                    .put("n", e.name)
                    .put("u", e.uri)
                    .put("t", e.tree)
                    .put("m", e.mime)
                    .put("d", e.isDirectory)
                    .put("s", e.size)
                    .put("p", e.parent)
                    .put("f", e.filePath ?: "")
            )
        }
        return arr.toString()
    }

    private fun parse(text: String): List<FileEntry> {
        if (text.isBlank()) return emptyList()
        val arr = JSONArray(text)
        val out = ArrayList<FileEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val path = o.optString("f").ifEmpty { null }
            out.add(
                FileEntry(
                    name = o.optString("n"),
                    uri = o.optString("u"),
                    tree = o.optString("t"),
                    mime = o.optString("m"),
                    isDirectory = o.optBoolean("d"),
                    size = o.optLong("s"),
                    parent = o.optString("p"),
                    filePath = path
                )
            )
        }
        return out
    }

    const val FILE_PROVIDER_SUFFIX = ".files"
}
