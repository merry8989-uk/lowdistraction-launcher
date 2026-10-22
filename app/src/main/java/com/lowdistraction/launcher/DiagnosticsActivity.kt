package com.lowdistraction.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The diagnostics screen.
 *
 * Shows what the app has recorded, and offers four ways out for it: save it to
 * a file, share it with any app, mail it to the maintainer with everything
 * already filled in, or wipe it. The report itself is built in
 * [DiagnosticsReport]; this screen only presents it.
 *
 * No new permissions are involved: the save uses the system file picker, and
 * sharing and mailing hand a file to another app. The launcher still has no
 * internet permission, so it cannot send anything itself.
 */
class DiagnosticsActivity : Activity() {

    private lateinit var column: LinearLayout
    private lateinit var summary: TextView
    private lateinit var preview: TextView
    private lateinit var toggle: TextView

    private var report: DiagnosticsReport.Report? = null

    /** Set while a "save to file" picker is open. */
    private var pendingBytes: ByteArray? = null
    private var pendingName: String = ""
    private var pendingFormat: Events.ExportFormat? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diag.installCrashHandler()
        buildUi()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        Events.screenView(this, "Diagnostics")
    }

    // ------------------------------------------------------------ screen build
    private fun buildUi() {
        val theme = Look.theme(this)

        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(40), dp(22), dp(28))
            setBackgroundColor(theme.bg)
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(this).apply {
                text = getString(R.string.settings_back)
                setTextColor(theme.accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                typeface = Typeface.MONOSPACE
                setPadding(0, 0, dp(14), 0)
                setOnClickListener { finish() }
            }
        )
        header.addView(
            TextView(this).apply {
                text = getString(R.string.diag_title)
                setTextColor(theme.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        column.addView(header)

        column.addView(
            TextView(this).apply {
                text = getString(R.string.diag_sub)
                setTextColor(theme.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(8), 0, dp(14))
            }
        )

        summary = TextView(this).apply {
            setTextColor(theme.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(14))
        }
        column.addView(summary)

        // Row 1 — refresh / clean
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(button(getString(R.string.diag_refresh), theme) { refresh() })
        row1.addView(spacer())
        row1.addView(button(getString(R.string.diag_clean), theme) { confirmClean() })
        column.addView(row1)

        column.addView(spacerH(dp(10)))

        // Row 2 — export / share / email
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(button(getString(R.string.diag_export), theme) { chooseFormat(false) })
        row2.addView(spacer())
        row2.addView(button(getString(R.string.diag_share), theme) { chooseFormat(true) })
        column.addView(row2)

        column.addView(spacerH(dp(10)))

        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row3.addView(button(getString(R.string.diag_email), theme, accent = true) { chooseMailFormat() })
        column.addView(row3)

        column.addView(
            TextView(this).apply {
                text = getString(R.string.diag_email_note, DiagnosticsReport.EMAIL)
                setTextColor(theme.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(8), 0, dp(4))
            }
        )

        toggle = TextView(this).apply {
            setTextColor(theme.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { toggleLogging() }
        }
        column.addView(toggle)

        column.addView(
            TextView(this).apply {
                text = getString(R.string.diag_preview)
                setTextColor(theme.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(6), 0, dp(8))
            }
        )

        preview = TextView(this).apply {
            setTextColor(theme.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            addView(preview)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        column.addView(scroll)

        setContentView(column)
    }

    private fun button(label: String, theme: Theme, accent: Boolean = false, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.MONOSPACE
            setTextColor(if (accent) theme.bg else theme.textPrimary)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            val edge = if (accent) theme.accent else (theme.textSecondary and 0x00FFFFFF) or 0x40000000
            background = GradientDrawable().apply {
                cornerRadius = dp(11).toFloat()
                setColor(if (accent) theme.accent else theme.surface)
                setStroke(dp(1), edge)
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }

    private fun spacer() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(9), 1)
    }

    private fun spacerH(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- actions
    private fun refresh() {
        val fresh = runCatching { DiagnosticsReport.collect(this) }.getOrNull()
        report = fresh
        if (fresh == null) {
            summary.text = getString(R.string.diag_failed)
            preview.text = ""
            return
        }
        summary.text = getString(
            R.string.diag_summary,
            fresh.events.size,
            humanSize(Diag.sizeBytes(this)),
            fresh.crashCount
        )
        toggle.text = getString(
            if (fresh.enabled) R.string.diag_turn_off else R.string.diag_turn_on
        )
        Events.diagnosticsAction(this, Events.DiagAction.REFRESH, null, null, null, fresh.events.size)
        val text = DiagnosticsReport.renderText(fresh)
        preview.text = if (text.length > PREVIEW_LIMIT) {
            text.substring(0, PREVIEW_LIMIT) + "\n\n… " +
                getString(R.string.diag_preview_cut, text.length - PREVIEW_LIMIT)
        } else {
            text
        }
        Toast.makeText(this, R.string.diag_refreshed, Toast.LENGTH_SHORT).show()
    }

    private fun confirmClean() {
        AlertDialog.Builder(this)
            .setTitle(R.string.diag_clean)
            .setMessage(R.string.diag_clean_confirm)
            .setPositiveButton(R.string.diag_clean_yes) { _, _ ->
                Diag.clear(this)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleLogging() {
        val on = !Diag.isEnabled(this)
        Diag.setEnabled(this, on)
        Toast.makeText(
            this,
            if (on) R.string.diag_now_on else R.string.diag_now_off,
            Toast.LENGTH_SHORT
        ).show()
        refresh()
    }

    /** One rendered report: its bytes, its file extension and its MIME type. */
    private class Blob(val bytes: ByteArray, val ext: String, val mime: String)

    private fun label(ext: String): String = when (ext) {
        "md" -> getString(R.string.diag_fmt_md)
        "json" -> getString(R.string.diag_fmt_json)
        "csv" -> getString(R.string.diag_fmt_csv)
        "zip" -> getString(R.string.diag_fmt_zip)
        "all" -> getString(R.string.diag_fmt_everything)
        else -> getString(R.string.diag_fmt_txt)
    }

    /** Export and Share: one file, in whichever shape you pick. */
    private fun chooseFormat(share: Boolean) {
        val current = report ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.diag_choose_format)
            .setItems(EXPORT_FORMATS.map { label(it) }.toTypedArray()) { _, which ->
                val ext = EXPORT_FORMATS[which]
                val blob = renderOne(current, ext)
                Events.diagnosticsAction(
                    this,
                    if (share) Events.DiagAction.SHARE else Events.DiagAction.EXPORT,
                    formatOf(ext), null, blob.bytes.size.toLong(), current.events.size
                )
                if (share) shareBlob(blob, current) else saveBlob(blob, current)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Renders one report. A `.zip` holds all four shapes in a single archive. */
    private fun renderOne(r: DiagnosticsReport.Report, ext: String): Blob = when (ext) {
        "zip" -> Blob(renderZip(r), "zip", "application/zip")
        else -> Blob(render(r, ext).toByteArray(Charsets.UTF_8), ext, mime(ext))
    }

    private fun renderZip(r: DiagnosticsReport.Report): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (ext in TEXT_FORMATS) {
                zip.putNextEntry(ZipEntry("lowdistraction-diagnostics.$ext"))
                zip.write(render(r, ext).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun render(r: DiagnosticsReport.Report, ext: String): String = when (ext) {
        "md" -> DiagnosticsReport.renderMarkdown(r)
        "json" -> DiagnosticsReport.renderJson(r)
        "csv" -> DiagnosticsReport.renderCsv(r)
        else -> DiagnosticsReport.renderText(r)
    }

    private fun formatOf(ext: String): Events.ExportFormat = when (ext) {
        "md" -> Events.ExportFormat.MD
        "json" -> Events.ExportFormat.JSON
        "csv" -> Events.ExportFormat.CSV
        "zip" -> Events.ExportFormat.ZIP
        "all" -> Events.ExportFormat.ALL
        else -> Events.ExportFormat.TXT
    }

    private fun mime(ext: String): String = when (ext) {
        "md" -> "text/markdown"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "zip" -> "application/zip"
        else -> "text/plain"
    }

    // ------------------------------------------------------------------ save
    private fun saveBlob(blob: Blob, r: DiagnosticsReport.Report) {
        pendingBytes = blob.bytes
        pendingName = DiagnosticsReport.fileName(blob.ext)
        pendingFormat = formatOf(blob.ext)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = blob.mime
            putExtra(Intent.EXTRA_TITLE, pendingName)
        }
        try {
            startActivityForResult(intent, REQUEST_SAVE)
        } catch (_: ActivityNotFoundException) {
            pendingBytes = null
            Toast.makeText(this, R.string.diag_no_picker, Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SAVE) return
        val bytes = pendingBytes ?: return
        val uri = data?.data
        val name = pendingName
        val format = pendingFormat
        pendingBytes = null
        if (resultCode != RESULT_OK || uri == null) return
        val ok = runCatching {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(bytes)
                out.flush()
            }
        }.isSuccess
        Events.diagnosticsAction(
            this, Events.DiagAction.EXPORT, format, Events.ExportChannel.SAVE,
            bytes.size.toLong(), report?.events?.size ?: 0
        )
        Toast.makeText(
            this,
            if (ok) getString(R.string.diag_saved, name) else getString(R.string.diag_save_failed),
            Toast.LENGTH_LONG
        ).show()
    }

    // ----------------------------------------------------------------- share
    private fun shareBlob(blob: Blob, r: DiagnosticsReport.Report) {
        val file = writeTemp(blob.bytes, blob.ext) ?: run {
            Toast.makeText(this, R.string.diag_save_failed, Toast.LENGTH_LONG).show()
            return
        }
        val uri = runCatching {
            FileProvider.getUriForFile(this, packageName + ".files", file)
        }.getOrNull() ?: run {
            Toast.makeText(this, R.string.diag_save_failed, Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = blob.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, mailSubject())
            putExtra(
                Intent.EXTRA_TEXT,
                mailBody(DiagnosticsReport.renderText(r), listOf(file.name))
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startChooser(intent, R.string.diag_choose_share)
    }

    // ----------------------------------------------------------------- email
    /**
     * Pick what the mail should carry, then Gmail opens with everything already
     * in place: the maintainer's address, a subject naming the version, the whole
     * report in the message body, and the report attached. Nothing to type, and
     * nothing to attach by hand.
     *
     * Gmail is targeted directly so no chooser stands in the way. If it is not
     * installed the same mail is offered to whatever else can take it, and if
     * nothing can, the address is shown so it can be copied.
     */
    private fun chooseMailFormat() {
        val current = report ?: return
        val options = listOf("all") + EXPORT_FORMATS.toList()
        AlertDialog.Builder(this)
            .setTitle(R.string.diag_choose_mail_format)
            .setItems(options.map { label(it) }.toTypedArray()) { _, which ->
                emailReport(current, options[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun emailReport(current: DiagnosticsReport.Report, what: String) {
        val text = DiagnosticsReport.renderText(current)
        val files = ArrayList<File>()
        if (what == "all") {
            // The default: a readable report and the structured export together.
            writeTemp(text.toByteArray(Charsets.UTF_8), "txt")?.let { files.add(it) }
            writeTemp(
                DiagnosticsReport.renderJson(current).toByteArray(Charsets.UTF_8), "json"
            )?.let { files.add(it) }
        } else {
            val blob = renderOne(current, what)
            writeTemp(blob.bytes, blob.ext)?.let { files.add(it) }
        }
        val uris = files.mapNotNull { file ->
            runCatching {
                FileProvider.getUriForFile(this, packageName + ".files", file)
            }.getOrNull()
        }

        Events.diagnosticsAction(
            this, Events.DiagAction.EMAIL, formatOf(what),
            Events.ExportChannel.EMAIL, text.length.toLong(), current.events.size
        )

        val subject = mailSubject()
        val body = mailBody(text, files.map { it.name })

        if (startMail(uris, subject, body, GMAIL)) return
        if (startMail(uris, subject, body, null)) return

        // No mail app would take the attachment — fall back to a plain mailto:
        // link, and show the address so it can be copied by hand.
        val mailto = Intent(
            Intent.ACTION_SENDTO,
            Uri.parse("mailto:" + DiagnosticsReport.EMAIL)
        ).apply {
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        if (!startChooser(mailto, R.string.diag_choose_mail)) {
            Toast.makeText(
                this,
                getString(R.string.diag_no_mail, DiagnosticsReport.EMAIL),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Hands the composed mail to one app, or to the chooser when [pkg] is null.
     * Returns false when nothing on the device would take it.
     */
    private fun startMail(uris: List<Uri>, subject: String, body: String, pkg: String?): Boolean {
        val multiple = uris.size > 1
        val intent = Intent(
            if (multiple) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND
        ).apply {
            type = if (multiple) "*/*" else "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(DiagnosticsReport.EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            when {
                uris.size == 1 -> putExtra(Intent.EXTRA_STREAM, uris[0])
                uris.size > 1 ->
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            if (uris.isNotEmpty()) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (pkg != null) setPackage(pkg)
        }
        val send = if (pkg == null) {
            Intent.createChooser(intent, getString(R.string.diag_choose_mail))
        } else {
            intent
        }
        return try {
            startActivity(send)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun mailSubject(): String =
        "Low Distraction diagnostics — v" + Diag.versionName(this) + " (" + Diag.versionCode(this) + ")"

    private fun mailBody(text: String, attachments: List<String>): String = buildString {
        append("Nothing needs typing — just send this as it is.\n\n")
        if (attachments.isEmpty()) {
            append("The report is below.\n\n")
        } else {
            append("Attached: ").append(attachments.joinToString(", ")).append("\n\n")
        }
        append("----------------------------------------\n\n")
        append(text)
    }

    private fun startChooser(intent: Intent, titleRes: Int): Boolean {
        val chooser = Intent.createChooser(intent, getString(titleRes))
        return try {
            startActivity(chooser)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun writeTemp(bytes: ByteArray, ext: String): File? = runCatching {
        val dir = File(cacheDir, "diagnostics").apply { mkdirs() }
        // Housekeeping only: anything a previous export left behind for a day is
        // removed. Fresh files are kept, so a share target that reads the file
        // a little later still finds it.
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < cutoff) it.delete() }
        val file = File(dir, DiagnosticsReport.fileName(ext))
        file.writeBytes(bytes)
        file
    }.getOrNull()

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024f / 1024f)
        bytes >= 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024f)
        else -> bytes.toString() + " B"
    }

    private companion object {
        const val REQUEST_SAVE = 4401
        const val PREVIEW_LIMIT = 12000

        /** The four text shapes; a .zip bundles exactly these. */
        val TEXT_FORMATS = arrayOf("txt", "md", "json", "csv")

        /** What Export and Share offer. */
        val EXPORT_FORMATS = arrayOf("txt", "md", "json", "csv", "zip")

        /** Targeted so the mail opens straight in Gmail, with no chooser. */
        const val GMAIL = "com.google.android.gm"
    }
}
