package com.lowdistraction.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.lowdistraction.launcher.bubble.AssistiveTouchService
import com.lowdistraction.launcher.bubble.FloatingBubbleService

/**
 * The launcher's settings screen. Opened by long-pressing anywhere on the
 * home screen. Owns the assistive-bubble switch, the payment/government-app
 * privacy controls, the folder search and the contact link.
 */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<View>(R.id.back).setOnClickListener { finish() }

        findViewById<View>(R.id.row_pick_apps).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_PICK_APPS, true)
            )
        }

        findViewById<View>(R.id.row_overlay).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }

        findViewById<View>(R.id.row_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<View>(R.id.row_hidden).setOnClickListener { showHiddenAppsDialog() }

        findViewById<View>(R.id.row_default).setOnClickListener { HomeRole.request(this) }

        findViewById<View>(R.id.row_contact).setOnClickListener { openInstagram() }

        findViewById<View>(R.id.row_updates).setOnClickListener { openReleases() }
        findViewById<TextView>(R.id.updatesDesc).text =
            getString(R.string.settings_updates_desc, appVersion())

        findViewById<View>(R.id.row_sensitive_manage).setOnClickListener { showSensitiveDialog() }
        findViewById<View>(R.id.row_files).setOnClickListener { showFilesDialog() }
    }

    /**
     * The app has no INTERNET permission by design, so it cannot check for a new
     * version itself. Instead we hand off to the browser, which shows the
     * releases page (with the latest version) without weakening the lockdown.
     */
    private fun openReleases() {
        val url = getString(R.string.updates_url)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull() ?: "—"

    override fun onResume() {
        super.onResume()
        // Reflect the real service state whenever we come back to this screen.
        val toggle = findViewById<Switch>(R.id.bubble_switch)
        toggle.setOnCheckedChangeListener(null)
        toggle.isChecked = FloatingBubbleService.running
        toggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) enableBubble() else disableBubble()
        }

        val devCheck = findViewById<Switch>(R.id.devcheck_switch)
        devCheck.setOnCheckedChangeListener(null)
        devCheck.isChecked = ShortcutPrefs.isDevCheckEnabled(this)
        devCheck.setOnCheckedChangeListener { _, isChecked ->
            ShortcutPrefs.setDevCheckEnabled(this, isChecked)
        }

        val sensitive = findViewById<Switch>(R.id.sensitive_switch)
        sensitive.setOnCheckedChangeListener(null)
        sensitive.isChecked = SensitiveApps.isEnabled(this)
        sensitive.setOnCheckedChangeListener { _, isChecked ->
            SensitiveApps.setEnabled(this, isChecked)
        }

        val strict = findViewById<Switch>(R.id.strict_switch)
        strict.setOnCheckedChangeListener(null)
        strict.isChecked = SensitiveApps.isStrict(this)
        strict.setOnCheckedChangeListener { _, isChecked ->
            SensitiveApps.setStrict(this, isChecked)
        }

        updateFilesDesc()
    }

    private fun enableBubble() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.bubble_need_overlay, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (!AssistiveTouchService.isReady) {
            Toast.makeText(this, R.string.bubble_need_accessibility, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        FloatingBubbleService.start(this)
    }

    private fun disableBubble() {
        FloatingBubbleService.stop(this)
    }

    private fun openInstagram() {
        val url = getString(R.string.contact_instagram_url)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }

    private fun showHiddenAppsDialog() {
        val hiddenApps = HiddenApps(this)
        val packages = hiddenApps.all().toList()
        if (packages.isEmpty()) {
            Toast.makeText(this, R.string.no_hidden_apps, Toast.LENGTH_SHORT).show()
            return
        }

        val labels = packages.map { pkg ->
            try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                pkg
            }
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.hidden_apps)
            .setItems(labels) { _, which ->
                hiddenApps.unhide(packages[which])
                Toast.makeText(this, R.string.bubble_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------- payment & government apps
    private fun showSensitiveDialog() {
        val items = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        items += if (SensitiveApps.isAutoName(this)) {
            getString(R.string.sensitive_names_on)
        } else {
            getString(R.string.sensitive_names_off)
        }
        actions += {
            SensitiveApps.setAutoName(this, !SensitiveApps.isAutoName(this))
            showSensitiveDialog()
        }

        items += if (SensitiveApps.isAutoHce(this)) {
            getString(R.string.sensitive_auto_on)
        } else {
            getString(R.string.sensitive_auto_off)
        }
        actions += {
            SensitiveApps.setAutoHce(this, !SensitiveApps.isAutoHce(this))
            showSensitiveDialog()
        }

        items += getString(R.string.sensitive_add)
        actions += { pickSensitiveApp() }

        val user = SensitiveApps.userPackages(this).sorted()
        if (user.isEmpty()) {
            items += getString(R.string.sensitive_none_added)
            actions += {}
        } else {
            for (pkg in user) {
                items += getString(R.string.sensitive_remove, SensitiveApps.label(this, pkg))
                actions += {
                    SensitiveApps.removeUserPackage(this, pkg)
                    showSensitiveDialog()
                }
            }
        }

        for (pkg in SensitiveApps.excludedPackages(this).sorted()) {
            items += getString(R.string.sensitive_allow, SensitiveApps.label(this, pkg))
            actions += {
                SensitiveApps.removeExcluded(this, pkg)
                showSensitiveDialog()
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.sensitive_manage_title)
            .setItems(items.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNeutralButton(R.string.sensitive_builtin) { _, _ -> showBuiltInDialog() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickSensitiveApp() {
        val apps = AppRepository.loadApps(this)
        if (apps.isEmpty()) return
        val labels = apps.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.sensitive_add)
            .setItems(labels) { _, which ->
                val app = apps[which]
                SensitiveApps.hideBubble(this, app.packageName)
                Toast.makeText(
                    this, getString(R.string.sensitive_added, app.label), Toast.LENGTH_SHORT
                ).show()
                showSensitiveDialog()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showBuiltInDialog() {
        val text = buildString {
            append(getString(R.string.sensitive_builtin_desc))
            append("\n\n")
            append(SensitiveApps.BUILT_IN.sorted().joinToString("\n"))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.sensitive_builtin)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ------------------------------------------------------------- file search
    private fun showFilesDialog() {
        val roots = FileIndex.roots(this)
        val items = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        items += getString(R.string.files_add)
        actions += { pickFolder() }

        if (roots.isNotEmpty()) {
            items += getString(R.string.files_rebuild)
            actions += { rebuildIndex() }
        }

        for (root in roots) {
            items += getString(R.string.files_remove, folderLabel(root))
            actions += {
                runCatching {
                    contentResolver.releasePersistableUriPermission(
                        root, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                FileIndex.removeRoot(this, root)
                showFilesDialog()
            }
        }

        if (roots.isEmpty()) {
            items += getString(R.string.files_none)
            actions += {}
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_row_files)
            .setItems(items.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun folderLabel(uri: Uri): String =
        runCatching { Uri.decode(uri.lastPathSegment ?: uri.toString()) }
            .getOrDefault(uri.toString())

    private fun pickFolder() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            ),
            REQ_FOLDER
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FOLDER && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            FileIndex.addRoot(this, uri)
            rebuildIndex()
        }
    }

    private fun rebuildIndex() {
        Toast.makeText(this, R.string.files_indexing, Toast.LENGTH_SHORT).show()
        Thread {
            runCatching { FileIndex.rebuild(this) }
            val count = runCatching { FileIndex.count(this) }.getOrDefault(0)
            runOnUiThread {
                Toast.makeText(
                    this, getString(R.string.files_rebuild_done, count), Toast.LENGTH_LONG
                ).show()
                updateFilesDesc()
            }
        }.start()
    }

    private fun updateFilesDesc() {
        val desc = findViewById<TextView>(R.id.filesDesc)
        if (!FileIndex.isEnabled(this)) {
            desc.text = getString(R.string.files_none)
            return
        }
        Thread {
            val roots = FileIndex.roots(this).size
            val count = runCatching { FileIndex.count(this) }.getOrDefault(0)
            runOnUiThread { desc.text = getString(R.string.files_desc, roots, count) }
        }.start()
    }

    companion object {
        private const val REQ_FOLDER = 902
    }
}
