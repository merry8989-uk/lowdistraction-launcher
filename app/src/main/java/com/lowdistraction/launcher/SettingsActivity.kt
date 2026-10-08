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
 * home screen. Owns the assistive-bubble on/off switch and the contact link.
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
}
