package com.lowdistraction.launcher

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** One file or folder found while walking the folders the user has shared. */
data class FileEntry(
    val name: String,
    val uri: String,
    val tree: String,
    val mime: String,
    val isDirectory: Boolean,
    val size: Long,
    val parent: String
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
 * Searches the contents of folders the user has shared with the app through the
 * system folder picker (Storage Access Framework). No storage permission is
 * needed and nothing outside the shared folders is ever read.
 *
 * The walk is done once and cached on disk, because re-walking a whole phone on
 * every keystroke would be far too slow.
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

    fun isEnabled(c: Context): Boolean = roots(c).isNotEmpty()

    fun indexedAt(c: Context): Long = prefs(c).getLong(KEY_INDEXED_AT, 0L)

    fun count(c: Context): Int = load(c).size

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

    /** Walks every shared folder and caches what it finds. Blocking — use a thread. */
    fun rebuild(c: Context) {
        val out = ArrayList<FileEntry>()
        val seen = HashSet<String>()
        for (root in roots(c)) {
            runCatching {
                walk(c, root, DocumentsContract.getTreeDocumentId(root), "", 0, out, seen)
            }
        }
        cache = out
        runCatching { File(c.filesDir, CACHE_FILE).writeText(serialize(out)) }
        prefs(c).edit().putLong(KEY_INDEXED_AT, System.currentTimeMillis()).apply()
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
                    // The private Android/data area is never readable anyway.
                    if (isDir && parentPath.isEmpty() && name == "Android") continue

                    val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    out.add(FileEntry(name, docUri.toString(), tree.toString(), mime, isDir, size, parentPath))

                    if (isDir && seen.add(tree.toString() + "/" + id)) {
                        val path = if (parentPath.isEmpty()) name else "$parentPath/$name"
                        walk(c, tree, id, path, depth + 1, out, seen)
                    }
                }
            }
        }
    }

    /** Direct children of a folder the user shared, for the folder-open dialog. */
    fun children(c: Context, entry: FileEntry, limit: Int = 200): List<FileEntry> {
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
        return out.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    /** Name match: exact first, then starts-with, then contains. */
    fun search(c: Context, query: String, limit: Int): List<FileEntry> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        val scored = ArrayList<Pair<Int, FileEntry>>()
        for (e in load(c)) {
            val n = e.name.lowercase()
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
            out.add(
                FileEntry(
                    name = o.optString("n"),
                    uri = o.optString("u"),
                    tree = o.optString("t"),
                    mime = o.optString("m"),
                    isDirectory = o.optBoolean("d"),
                    size = o.optLong("s"),
                    parent = o.optString("p")
                )
            )
        }
        return out
    }
}
