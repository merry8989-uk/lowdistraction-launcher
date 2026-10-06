package com.lowdistraction.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * The home screen: clock + date, a search box, and a text-only app list.
 */
class MainActivity : Activity() {

    private lateinit var adapter: AppListAdapter
    private lateinit var emptyView: TextView
    private lateinit var search: EditText

    private var allApps: List<AppInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        emptyView = findViewById(R.id.empty)
        search = findViewById(R.id.search)

        adapter = AppListAdapter(
            onLaunch = { launch(it) },
            onLongPress = { app, view -> showAppMenu(app, view) }
        )

        val list = findViewById<RecyclerView>(R.id.appList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
        })

        loadApps()
    }

    override fun onResume() {
        super.onResume()
        // Reload so newly installed / removed apps show up without a restart.
        if (allApps.isNotEmpty()) loadApps()
    }

    private fun loadApps() {
        Thread {
            val apps = AppRepository.loadApps(this)
            runOnUiThread {
                allApps = apps
                filter(search.text?.toString().orEmpty())
            }
        }.start()
    }

    private fun filter(query: String) {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) {
            allApps
        } else {
            allApps.filter { it.label.lowercase().contains(q) }
        }
        adapter.submit(filtered)
        emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun launch(app: AppInfo) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = app.component
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }
        startSafely(intent)
        search.setText("")
    }

    private fun showAppMenu(app: AppInfo, anchor: View) {
        val options = arrayOf(getString(R.string.action_app_info), getString(R.string.action_uninstall))
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openAppInfo(app)
                    1 -> uninstall(app)
                }
            }
            .show()
    }

    private fun openAppInfo(app: AppInfo) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", app.packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startSafely(intent)
    }

    private fun uninstall(app: AppInfo) {
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.fromParts("package", app.packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startSafely(intent)
    }

    private fun startSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Target app has no such activity; ignore.
        }
    }

    @Deprecated("Launcher home; back should not exit the app list")
    override fun onBackPressed() {
        if (search.text.isNotEmpty()) {
            search.setText("")
        }
        // Otherwise swallow the event so we stay on the home screen.
    }
}
