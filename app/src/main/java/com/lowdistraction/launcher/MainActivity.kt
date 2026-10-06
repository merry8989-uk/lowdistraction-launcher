package com.lowdistraction.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * The home screen: clock + date, a search box, and a text-only app list.
 *
 * Gestures:
 *  - swipe right anywhere in the list  -> dialer
 *  - swipe left  anywhere in the list  -> camera
 *  - long-press the clock              -> manage hidden apps
 *  - long-press an app                 -> app info / uninstall / hide
 */
class MainActivity : Activity() {

    private lateinit var adapter: AppListAdapter
    private lateinit var emptyView: TextView
    private lateinit var search: EditText
    private lateinit var hiddenApps: HiddenApps

    private var allApps: List<AppInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hiddenApps = HiddenApps(this)
        emptyView = findViewById(R.id.empty)
        search = findViewById(R.id.search)

        adapter = AppListAdapter(
            onLaunch = { launch(it) },
            onLongPress = { app, view -> showAppMenu(app, view) }
        )

        val list = findViewById<RecyclerView>(R.id.appList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        setupGestures(list)

        val clock = findViewById<TextView>(R.id.clock)
        clock.isClickable = true
        clock.setOnLongClickListener {
            showHiddenAppsDialog()
            true
        }

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
        val visible = allApps.filter { !hiddenApps.isHidden(it.packageName) }
        val filtered = if (q.isEmpty()) {
            visible
        } else {
            visible.filter { it.label.lowercase().contains(q) }
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
        val options = arrayOf(
            getString(R.string.action_app_info),
            getString(R.string.action_uninstall),
            getString(R.string.action_hide)
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openAppInfo(app)
                    1 -> uninstall(app)
                    2 -> {
                        hiddenApps.hide(app.packageName)
                        loadApps()
                    }
                }
            }
            .show()
    }

    private fun showHiddenAppsDialog() {
        val pkgs = hiddenApps.all().toList()
        if (pkgs.isEmpty()) {
            Toast.makeText(this, R.string.no_hidden_apps, Toast.LENGTH_SHORT).show()
            return
        }

        val labels = pkgs.map { pkg ->
            try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                pkg
            }
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.hidden_apps)
            .setItems(labels) { _, which ->
                hiddenApps.unhide(pkgs[which])
                loadApps()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupGestures(list: RecyclerView) {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                if (abs(dx) > abs(dy) && abs(dx) > SWIPE_MIN) {
                    if (dx > 0) openDialer() else openCamera()
                    return true
                }
                return false
            }
        })

        list.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            false // do not consume, so the list still scrolls normally
        }
    }

    private fun openDialer() {
        startSafely(Intent(Intent.ACTION_DIAL).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
    }

    private fun openCamera() {
        startSafely(
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
    }

    private fun openAppInfo(app: AppInfo) {
        startSafely(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", app.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
    }

    private fun uninstall(app: AppInfo) {
        startSafely(
            Intent(Intent.ACTION_DELETE).apply {
                data = Uri.fromParts("package", app.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
    }

    private fun startSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Target has no such activity; ignore.
        }
    }

    @Deprecated("Launcher home; back should not exit the app list")
    override fun onBackPressed() {
        if (search.text.isNotEmpty()) {
            search.setText("")
        }
        // Otherwise swallow the event so we stay on the home screen.
    }

    companion object {
        private const val SWIPE_MIN = 100f
    }
}
