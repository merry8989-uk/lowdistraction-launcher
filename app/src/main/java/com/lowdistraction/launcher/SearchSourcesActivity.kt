package com.lowdistraction.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat

/**
 * Chooses what the second search box looks through, and owns the permissions
 * behind those choices.
 *
 * Every source starts off. With at least one on, the second search box appears
 * on the home screen; with none on, it stays hidden.
 */
class SearchSourcesActivity : Activity() {

    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        // Permission state may have changed while we were away.
        buildUi()
    }

    // ------------------------------------------------------------- screen build
    private fun buildUi() {
        val theme = Look.theme(this)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(44), dp(24), dp(40))
        }

        column.addView(
            TextView(this).apply {
                setText(R.string.settings_back)
                setTextColor(theme.accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { finish() }
            }
        )
        column.addView(
            TextView(this).apply {
                setText(R.string.sources_title)
                setTextColor(theme.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(6), 0, 0)
            }
        )
        column.addView(
            TextView(this).apply {
                setText(R.string.sources_intro)
                setTextColor(theme.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(8), 0, 0)
            }
        )

        // ---------------------------------------------------------- sources
        column.addView(section(getString(R.string.sources_section), theme))

        column.addView(
            switchRow(
                getString(R.string.sources_settings),
                getString(R.string.sources_settings_desc),
                SearchSources.settings(this),
                theme
            ) { on -> SearchSources.setSettings(this, on); buildUi() }
        )

        column.addView(
            switchRow(
                getString(R.string.sources_contacts),
                getString(R.string.sources_contacts_desc),
                SearchSources.contacts(this),
                theme
            ) { on ->
                SearchSources.setContacts(this, on)
                if (!on) forgetContacts()
                buildUi()
            }
        )
        if (SearchSources.contacts(this)) {
            column.addView(
                permissionRow(
                    getString(R.string.sources_permission_contacts),
                    hasPermission(Manifest.permission.READ_CONTACTS),
                    theme
                )
            )
        }

        column.addView(
            switchRow(
                getString(R.string.sources_calendar),
                getString(R.string.sources_calendar_desc),
                SearchSources.calendar(this),
                theme
            ) { on -> SearchSources.setCalendar(this, on); buildUi() }
        )
        if (SearchSources.calendar(this)) {
            column.addView(
                permissionRow(
                    getString(R.string.sources_permission_calendar),
                    hasPermission(Manifest.permission.READ_CALENDAR),
                    theme
                )
            )
        }

        column.addView(
            switchRow(
                getString(R.string.sources_files),
                getString(R.string.sources_files_desc),
                SearchSources.files(this),
                theme
            ) { on ->
                SearchSources.setFiles(this, on)
                if (!on) forgetFiles()
                buildUi()
            }
        )
        if (SearchSources.files(this)) {
            filesControls(theme)
        }

        column.addView(
            switchRow(
                getString(R.string.sources_devcheck),
                getString(R.string.sources_devcheck_desc),
                SearchSources.devCheck(this),
                theme
            ) { on -> ShortcutPrefs.setDevCheckEnabled(this, on); buildUi() }
        )

        column.addView(
            TextView(this).apply {
                setText(R.string.sources_footer)
                setTextColor(theme.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(24), 0, 0)
            }
        )

        val scroll = ScrollView(this).apply {
            setBackgroundColor(theme.bg)
            addView(column)
        }
        setContentView(scroll)

        window.statusBarColor = theme.bg
        window.navigationBarColor = theme.bg
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = theme.light
        controller.isAppearanceLightNavigationBars = theme.light
    }

    /** The file-search sub-rows: storage access, fallback folders, the index. */
    private fun filesControls(theme: Theme) {
        val full = FileIndex.hasAllFilesAccess(this)

        column.addView(
            valueRow(
                getString(R.string.sources_full_storage),
                if (full) getString(R.string.sources_granted)
                else getString(R.string.sources_not_granted),
                theme
            ) { openAllFilesAccess() }
        )
        column.addView(
            TextView(this).apply {
                setText(R.string.sources_full_storage_desc)
                setTextColor(theme.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.MONOSPACE
                setPadding(0, 0, 0, dp(4))
            }
        )

        if (full) {
            column.addView(permissionRow(getString(R.string.sources_all_files), true, theme))
        } else {
            // Fallback: folders shared through the system picker.
            column.addView(
                valueRow(
                    getString(R.string.sources_fallback_add),
                    getString(R.string.sources_fallback_hint),
                    theme
                ) { pickFolder() }
            )
            for (root in FileIndex.roots(this)) {
                column.addView(
                    valueRow(
                        folderLabel(root),
                        getString(R.string.sources_fallback_remove),
                        theme
                    ) {
                        FileIndex.removeRoot(this, root)
                        runCatching {
                            contentResolver.releasePersistableUriPermission(
                                root, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                        buildUi()
                    }
                )
            }
        }

        column.addView(
            valueRow(
                getString(R.string.sources_rebuild),
                getString(R.string.sources_indexed, FileIndex.count(this)),
                theme
            ) { rebuildIndex() }
        )
    }

    /** "Granted / Not granted" plus a button that opens the app's permission page. */
    private fun permissionRow(title: String, granted: Boolean, theme: Theme): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), 0, 0, dp(6))
        }
        row.addView(
            TextView(this).apply {
                setText(
                    getString(
                        if (granted) R.string.sources_permission_holding
                        else R.string.sources_permission_none,
                        title
                    )
                )
                setTextColor(theme.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.MONOSPACE
            }
        )
        if (granted) {
            row.addView(
                TextView(this).apply {
                    setText(R.string.sources_remove_permission)
                    setTextColor(theme.accent)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.MONOSPACE
                    setPadding(0, dp(6), 0, 0)
                    isClickable = true
                    setOnClickListener { openAppPermissionScreen() }
                }
            )
        }
        return row
    }

    // --------------------------------------------------------------- actions
    /** Opens the system screen where the user can change this app's permissions. */
    private fun openAppPermissionScreen() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS))
        }
    }

    private fun openAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.fromParts("package", packageName, null)
                    )
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_STORAGE)
        }
    }

    private fun pickFolder() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            ),
            REQ_FOLDER
        )
    }

    private fun rebuildIndex() {
        Toast.makeText(this, R.string.files_indexing, Toast.LENGTH_SHORT).show()
        Thread {
            runCatching { FileIndex.rebuild(this) }
            val count = runCatching { FileIndex.count(this) }.getOrDefault(0)
            runOnUiThread {
                Toast.makeText(this, getString(R.string.files_rebuild_done, count), Toast.LENGTH_LONG)
                    .show()
                buildUi()
            }
        }.start()
    }

    /** Files were switched off: drop the index and hand back the folder grants. */
    private fun forgetFiles() {
        FileIndex.clearCache(this)
        FileIndex.releaseFallbackFolders(this)
    }

    /** Contacts were switched off: nothing is cached, so just say so plainly. */
    private fun forgetContacts() {
        Toast.makeText(this, R.string.sources_contacts_off, Toast.LENGTH_SHORT).show()
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                rebuildIndex()
            } else {
                buildUi()
            }
        }
    }

    // ---------------------------------------------------------------- widgets
    private fun section(title: String, theme: Theme): TextView =
        TextView(this).apply {
            setText(title)
            setTextColor(theme.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.12f
            setPadding(0, dp(26), 0, dp(4))
        }

    private fun valueRow(title: String, value: String, theme: Theme, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(
            TextView(this).apply {
                setText(title)
                setTextColor(theme.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.MONOSPACE
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            TextView(this).apply {
                setText(value)
                setTextColor(theme.accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.END
            }
        )
        return row
    }

    private fun switchRow(
        title: String,
        desc: String,
        checked: Boolean,
        theme: Theme,
        onChange: (Boolean) -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, dp(14))
        }
        texts.addView(
            TextView(this).apply {
                setText(title)
                setTextColor(theme.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.MONOSPACE
            }
        )
        texts.addView(
            TextView(this).apply {
                setText(desc)
                setTextColor(theme.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.MONOSPACE
            }
        )
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            Switch(this).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, value -> onChange(value) }
            }
        )
        return row
    }

    private fun hasPermission(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun folderLabel(uri: Uri): String =
        runCatching { Uri.decode(uri.lastPathSegment ?: uri.toString()) }
            .getOrDefault(uri.toString())

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_FOLDER = 903
        private const val REQ_STORAGE = 904
    }
}
