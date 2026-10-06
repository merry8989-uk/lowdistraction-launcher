package com.lowdistraction.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lowdistraction.launcher.bubble.BubblePrefs
import kotlin.math.abs

/**
 * The home screen: clock + date, a search box, and a text-only app list.
 *
 * Search behaviour:
 *  - When a query narrows the list down to exactly ONE app, that app opens
 *    automatically after a short pause (AUTO_OPEN_DELAY_MS). Keep typing and
 *    the pending open is cancelled.
 *  - Pressing Enter/Search on the keyboard opens the top match.
 *
 * Gestures:
 *  - swipe right anywhere in the list  -> dialer
 *  - swipe left  anywhere in the list  -> camera
 *  - long-press ANYWHERE (empty space, clock, background) -> Settings
 *  - long-press an app                 -> app info / uninstall / hide
 */
class MainActivity : Activity() {

    private lateinit var adapter: AppListAdapter
    private lateinit var emptyView: TextView
    private lateinit var search: EditText
    private lateinit var hiddenApps: HiddenApps

    private var allApps: List<AppInfo> = emptyList()
    private var currentResults: List<AppInfo> = emptyList()

    private val autoOpenHandler = Handler(Looper.getMainLooper())

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

        // Long-press anywhere on the home screen opens Settings.
        findViewById<View>(R.id.header).setOnLongClickListener { openSettings(); true }
        findViewById<View>(R.id.root).setOnLongClickListener { openSettings(); true }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
        })

        search.setOnEditorActionListener { _, actionId, _ -> handleSearchAction(actionId) }

        if (intent?.getBooleanExtra(EXTRA_PICK_APPS, false) == true) {
            showAppPicker()
        }

        loadApps()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_PICK_APPS, false)) {
            showAppPicker()
        }
    }

    override fun onResume() {
        super.onResume()
        // Reload so newly installed / removed apps show up without a restart.
        if (allApps.isNotEmpty()) loadApps()
    }

    override fun onPause() {
        super.onPause()
        // Never fire a queued auto-open while we are in the background.
        autoOpenHandler.removeCallbacksAndMessages(null)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
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
        // Any change to the query cancels a pending auto-open.
        autoOpenHandler.removeCallbacksAndMessages(null)

        val q = query.trim().lowercase()
        val visible = allApps.filter { !hiddenApps.isHidden(it.packageName) }
        val filtered = if (q.isEmpty()) {
            visible
        } else {
            visible.filter { it.label.lowercase().contains(q) }
        }

        currentResults = filtered
        adapter.submit(filtered)
        emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE

        // Auto-open the app once the query has narrowed down to exactly one.
        if (q.isNotEmpty() && filtered.size == 1) {
            val target = filtered.first()
            autoOpenHandler.postDelayed({ launch(target) }, AUTO_OPEN_DELAY_MS)
        }
    }

    private fun handleSearchAction(actionId: Int): Boolean {
        val submitted = actionId == EditorInfo.IME_ACTION_SEARCH ||
            actionId == EditorInfo.IME_ACTION_DONE ||
            actionId == EditorInfo.IME_ACTION_GO
        if (submitted) {
            currentResults.firstOrNull()?.let { app ->
                autoOpenHandler.removeCallbacksAndMessages(null)
                launch(app)
                return true
            }
        }
        return false
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

    private fun showAppPicker() {
        val apps = AppRepository.loadApps(this).filter { !hiddenApps.isHidden(it.packageName) }
        if (apps.isEmpty()) return

        val labels = apps.map { it.label }.toTypedArray()
        val pinned = BubblePrefs(this).pinned().toSet()
        val checked = BooleanArray(apps.size) {
            apps[it].component.flattenToString() in pinned
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.bubble_pick_apps)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val chosen = apps.filterIndexed { index, _ -> checked[index] }
                    .take(BubblePrefs.MAX)
                    .map { it.component.flattenToString() }
                BubblePrefs(this).setPinned(chosen)
                Toast.makeText(this, R.string.bubble_saved, Toast.LENGTH_SHORT).show()
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

            override fun onLongPress(e: MotionEvent) {
                // Only when the press is NOT on an app row (that opens the app
                // menu instead).
                if (list.findChildViewUnder(e.x, e.y) == null) {
                    openSettings()
                }
            }
        })

        // addOnItemTouchListener sees every event before the rows consume it,
        // so swipes and long-presses work even over the list.
        list.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                detector.onTouchEvent(e)
                return false
            }
        })
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
        const val EXTRA_PICK_APPS = "pick_apps"
        private const val SWIPE_MIN = 100f
        private const val AUTO_OPEN_DELAY_MS = 350L
    }
}
