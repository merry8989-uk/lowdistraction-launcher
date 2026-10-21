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
import java.io.File

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
    private var pendingText: String? = null
    private var pendingName: String = ""

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
        row3.addView(button(getString(R.string.diag_email), theme, accent = true) { emailReport() })
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

    /** Ask which file type, then either save it or hand it to another app. */
    private fun chooseFormat(share: Boolean) {
        val current = report ?: return
        val labels = arrayOf(
            getString(R.string.diag_fmt_txt),
            getString(R.string.diag_fmt_md),
            getString(R.string.diag_fmt_json),
            getString(R.string.diag_fmt_csv)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.diag_choose_format)
            .setItems(labels) { _, which ->
                val ext = FORMATS[which]
                val text = render(current, ext)
                Events.diagnosticsAction(
                    this,
                    if (share) Events.DiagAction.SHARE else Events.DiagAction.EXPORT,
                    formatOf(ext), null, text.length.toLong(), current.events.size
                )
                if (share) shareText(text, ext) else saveText(text, ext)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        else -> Events.ExportFormat.TXT
    }

    private fun mime(ext: String): String = when (ext) {
        "md" -> "text/markdown"
        "json" -> "application/json"
        "csv" -> "text/csv"
        else -> "text/plain"
    }

    // ------------------------------------------------------------------ save
    private fun saveText(text: String, ext: String) {
        pendingText = text
        pendingName = DiagnosticsReport.fileName(ext)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime(ext)
            putExtra(Intent.EXTRA_TITLE, pendingName)
        }
        try {
            startActivityForResult(intent, REQUEST_SAVE)
        } catch (_: ActivityNotFoundException) {
            pendingText = null
            Toast.makeText(this, R.string.diag_no_picker, Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SAVE) return
        val text = pendingText ?: return
        val uri = data?.data
        pendingText = null
        if (resultCode != RESULT_OK || uri == null) return
        val ok = runCatching {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
            }
        }.isSuccess
        Events.diagnosticsAction(
            this, Events.DiagAction.EXPORT, null, Events.ExportChannel.SAVE,
            text.length.toLong(), report?.events?.size ?: 0
        )
        Toast.makeText(
            this,
            if (ok) getString(R.string.diag_saved, pendingName) else getString(R.string.diag_save_failed),
            Toast.LENGTH_LONG
        ).show()
    }

    // ----------------------------------------------------------------- share
    private fun shareText(text: String, ext: String) {
        val file = writeTemp(text, ext) ?: run {
            Toast.makeText(this, R.string.diag_save_failed, Toast.LENGTH_LONG).show()
            return
        }
        val uri = runCatching {
            FileProvider.getUriForFile(this, packageName + ".files", file)
        }.getOrNull() ?: run {
            Toast.makeText(this, R.string.diag_save_failed, Toast.LENGTH_LONG).show()
            return
        }
        Events.diagnosticsAction(
            this, Events.DiagAction.SHARE, formatOf(ext),
            Events.ExportChannel.SHARE, text.length.toLong(), report?.events?.size ?: 0
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime(ext)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, mailSubject())
            putExtra(Intent.EXTRA_TEXT, mailBody(text))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startChooser(intent, R.string.diag_choose_share)
    }

    // ----------------------------------------------------------------- email
    private fun emailReport() {
        val current = report ?: return
        val text = DiagnosticsReport.renderText(current)
        Events.diagnosticsAction(
            this, Events.DiagAction.EMAIL, Events.ExportFormat.TXT,
            Events.ExportChannel.EMAIL, text.length.toLong(), current.events.size
        )
        val file = writeTemp(text, "txt")
        val uri = file?.let {
            runCatching { FileProvider.getUriForFile(this, packageName + ".files", it) }.getOrNull()
        }

        // ACTION_SEND carries the attachment and pre-fills the recipient in
        // Gmail and most other mail apps; the body holds the whole report so
        // nothing has to be typed or attached by hand.
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(DiagnosticsReport.EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, mailSubject())
            putExtra(Intent.EXTRA_TEXT, mailBody(text))
            if (uri != null) {
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        if (!startChooser(intent, R.string.diag_choose_mail)) {
            // No mail app would take it — fall back to a plain mailto: link and
            // show the address so it can be copied by hand.
            val mailto = Intent(
                Intent.ACTION_SENDTO,
                Uri.parse("mailto:" + DiagnosticsReport.EMAIL)
            ).apply {
                putExtra(Intent.EXTRA_SUBJECT, mailSubject())
                putExtra(Intent.EXTRA_TEXT, mailBody(text))
            }
            if (!startChooser(mailto, R.string.diag_choose_mail)) {
                Toast.makeText(
                    this,
                    getString(R.string.diag_no_mail, DiagnosticsReport.EMAIL),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun mailSubject(): String =
        "Low Distraction diagnostics — v" + Diag.versionName(this) + " (" + Diag.versionCode(this) + ")"

    private fun mailBody(text: String): String =
        "Nothing needs typing — just send this as it is.\n\n" +
            "The report below is attached as a file as well.\n\n" +
            "----------------------------------------\n\n" + text

    private fun startChooser(intent: Intent, titleRes: Int): Boolean {
        val chooser = Intent.createChooser(intent, getString(titleRes))
        return try {
            startActivity(chooser)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun writeTemp(text: String, ext: String): File? = runCatching {
        val dir = File(cacheDir, "diagnostics").apply { mkdirs() }
        // Housekeeping only: anything a previous export left behind for a day is
        // removed. Fresh files are kept, so a share target that reads the file
        // a little later still finds it.
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < cutoff) it.delete() }
        val file = File(dir, DiagnosticsReport.fileName(ext))
        file.writeText(text, Charsets.UTF_8)
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
        val FORMATS = arrayOf("txt", "md", "json", "csv")
    }
}
