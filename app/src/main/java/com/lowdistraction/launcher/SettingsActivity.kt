package com.lowdistraction.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.lowdistraction.launcher.bubble.AssistiveTouchService
import com.lowdistraction.launcher.bubble.FloatingBubbleService

/**
 * The launcher's settings screen. Opened by long-pressing anywhere on the
 * home screen. Owns the assistive-bubble switch, the payment/government-app
 * privacy controls, the search sources, the guide and the contact link.
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

        // Straight to the system screen where the default launcher is picked.
        findViewById<View>(R.id.row_default).setOnClickListener {
            if (!HomeRole.openHomeSettings(this)) HomeRole.request(this)
        }

        findViewById<View>(R.id.row_sources).setOnClickListener {
            startActivity(Intent(this, SearchSourcesActivity::class.java))
        }

        findViewById<View>(R.id.row_pinned).setOnClickListener { showPinnedDialog() }

        findViewById<View>(R.id.row_sorting).setOnClickListener {
            startActivity(Intent(this, SortSettingsActivity::class.java))
        }

        findViewById<View>(R.id.row_wheel_sound).setOnClickListener { showWheelSoundDialog() }

        findViewById<View>(R.id.row_pause_seconds).setOnClickListener { showPauseSecondsDialog() }

        findViewById<View>(R.id.row_guide).setOnClickListener {
            startActivity(Intent(this, GuideActivity::class.java))
        }

        findViewById<View>(R.id.row_contact).setOnClickListener { openInstagram() }

        findViewById<View>(R.id.row_updates).setOnClickListener { openReleases() }
        findViewById<TextView>(R.id.updatesDesc).text =
            getString(R.string.settings_updates_desc, appVersion())

        findViewById<View>(R.id.row_sensitive_manage).setOnClickListener { showSensitiveDialog() }
        findViewById<View>(R.id.row_appearance).setOnClickListener {
            startActivity(Intent(this, AppearanceActivity::class.java))
        }
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

        val pause = findViewById<Switch>(R.id.pause_switch)
        pause.setOnCheckedChangeListener(null)
        pause.isChecked = ShortcutPrefs.isLaunchPauseEnabled(this)
        pause.setOnCheckedChangeListener { _, isChecked ->
            ShortcutPrefs.setLaunchPauseEnabled(this, isChecked)
        }

        val pauseTick = findViewById<Switch>(R.id.pause_tick_switch)
        pauseTick.setOnCheckedChangeListener(null)
        pauseTick.isChecked = ShortcutPrefs.isTickSoundEnabled(this)
        pauseTick.setOnCheckedChangeListener { _, isChecked ->
            ShortcutPrefs.setTickSoundEnabled(this, isChecked)
            // Let the switch be heard, so it is obvious what it controls.
            if (isChecked) TimerTick.play(this, urgent = false)
        }

        updatePauseSecondsRow()

        findViewById<TextView>(R.id.wheelSoundDesc).text = WheelSound.selected(this).name
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

    /**
     * The hidden apps. Tapping one offers to open it — hiding only keeps it off
     * the home list — or to put it back into the list.
     */
    private fun showHiddenAppsDialog() {
        val packages = HiddenApps(this).all().sorted()
        if (packages.isEmpty()) {
            Toast.makeText(this, R.string.no_hidden_apps, Toast.LENGTH_SHORT).show()
            return
        }

        val labels = packages.map { labelOf(it) }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.hidden_apps)
            .setItems(labels) { _, which -> showHiddenAppActions(packages[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Open a hidden app, or bring it back into the list. */
    private fun showHiddenAppActions(pkg: String) {
        val label = labelOf(pkg)
        val actions = arrayOf(
            getString(R.string.hidden_open, label),
            getString(R.string.hidden_unhide)
        )
        AlertDialog.Builder(this)
            .setTitle(label)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> openApp(pkg, label)
                    1 -> {
                        HiddenApps(this).unhide(pkg)
                        Toast.makeText(
                            this, getString(R.string.hidden_unhidden, label), Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Starts a hidden app without changing whether it is hidden. */
    private fun openApp(pkg: String, label: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Toast.makeText(this, getString(R.string.hidden_open_failed, label), Toast.LENGTH_LONG)
                .show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.hidden_open_failed, label), Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun labelOf(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    // ---------------------------------------------------------- wheel & pins
    private fun showWheelSoundDialog() {
        val options = WheelSound.ALL
        val labels = options.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.wheel_sound_title)
            .setSingleChoiceItems(labels, options.indexOf(WheelSound.selected(this))) { dialog, which ->
                WheelSound.setSelected(this, options[which].id)
                Chime.play(this)
                findViewById<TextView>(R.id.wheelSoundDesc).text = options[which].name
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Pinned apps: the grid's shape, then which apps sit in it. */
    private fun showPinnedDialog() {
        val items = arrayOf(
            getString(R.string.pinned_grid_row, Look.pinnedRows(this), Look.pinnedCols(this)),
            getString(R.string.pinned_pick)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_row_pinned)
            .setItems(items) { _, which ->
                if (which == 0) showGridDialog() else showPinnedPicker()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Rows and columns, up to 10 by 4. A fresh install is 3 by 2. */
    private fun showGridDialog() {
        val theme = Look.theme(this)
        val summary = TextView(this).apply {
            setText(
                getString(
                    R.string.pinned_grid_summary,
                    Look.pinnedRows(this@SettingsActivity),
                    Look.pinnedCols(this@SettingsActivity),
                    Look.pinnedCapacity(this@SettingsActivity)
                )
            )
            setTextColor(theme.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.MONOSPACE
        }
        val rowsBar = SeekBar(this).apply {
            max = Look.MAX_PIN_ROWS - 1
            progress = Look.pinnedRows(this@SettingsActivity) - 1
        }
        val colsBar = SeekBar(this).apply {
            max = Look.MAX_PIN_COLS - 1
            progress = Look.pinnedCols(this@SettingsActivity) - 1
        }
        fun refresh() {
            val rows = rowsBar.progress + 1
            val cols = colsBar.progress + 1
            summary.setText(getString(R.string.pinned_grid_summary, rows, cols, rows * cols))
        }
        rowsBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = refresh()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        colsBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = refresh()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        box.addView(summary)
        box.addView(smallLabel(getString(R.string.pin_rows), theme))
        box.addView(rowsBar)
        box.addView(smallLabel(getString(R.string.pin_cols), theme))
        box.addView(colsBar)

        AlertDialog.Builder(this)
            .setTitle(R.string.pinned_grid)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Look.setPinnedRows(this, rowsBar.progress + 1)
                Look.setPinnedCols(this, colsBar.progress + 1)
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPinnedPicker() {
        val apps = AppRepository.loadApps(this)
        if (apps.isEmpty()) return
        val store = PinnedApps(this)
        val capacity = Look.pinnedCapacity(this)
        val pinned = store.all().toSet()
        val labels = apps.map { it.label }.toTypedArray()
        val checked = BooleanArray(apps.size) {
            apps[it].component.flattenToString() in pinned
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pinned_pick)
            .setMultiChoiceItems(labels, checked) { dialog, which, isChecked ->
                checked[which] = isChecked
                if (isChecked && checked.count { it } > capacity) {
                    checked[which] = false
                    (dialog as? AlertDialog)?.listView?.setItemChecked(which, false)
                    Toast.makeText(
                        this, getString(R.string.pinned_full, capacity), Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                store.set(
                    apps.filterIndexed { index, _ -> checked[index] }
                        .map { it.component.flattenToString() }
                )
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun smallLabel(label: String, theme: Theme): TextView = TextView(this).apply {
        setText(label)
        setTextColor(theme.textSecondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.MONOSPACE
        setPadding(0, dp(14), 0, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** How long the breather lasts, anywhere from one second to two minutes. */
    private fun showPauseSecondsDialog() {
        val theme = Look.theme(this)
        val summary = TextView(this).apply {
            setText(
                getString(
                    R.string.pause_seconds_value,
                    ShortcutPrefs.pauseSeconds(this@SettingsActivity)
                )
            )
            setTextColor(theme.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.MONOSPACE
        }
        val bar = SeekBar(this).apply {
            max = ShortcutPrefs.MAX_PAUSE_SECONDS - 1
            progress = ShortcutPrefs.pauseSeconds(this@SettingsActivity) - 1
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                summary.setText(getString(R.string.pause_seconds_value, p + 1))
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        box.addView(summary)
        box.addView(bar)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_row_pause_seconds)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                ShortcutPrefs.setPauseSeconds(this, bar.progress + 1)
                updatePauseSecondsRow()
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updatePauseSecondsRow() {
        findViewById<TextView>(R.id.pauseSecondsValue).text =
            getString(R.string.pause_seconds_value, ShortcutPrefs.pauseSeconds(this))
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
}
