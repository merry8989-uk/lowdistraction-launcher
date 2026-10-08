package com.lowdistraction.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The home screen.
 *
 *  - Left search bar  -> filters the app list (auto-opens on a unique match).
 *  - Right search bar -> looks through device settings, contacts and calendar,
 *    and can hand the query to the device's own search.
 *  - Long-press anywhere -> Settings.
 *  - The background drifts through colour so slowly you barely notice.
 */
class MainActivity : Activity() {

    private lateinit var appAdapter: AppListAdapter
    private lateinit var quickAdapter: QuickFindAdapter
    private lateinit var list: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var search: EditText
    private lateinit var quickFind: EditText
    private lateinit var devCheckBadge: View
    private lateinit var hiddenApps: HiddenApps

    private var allApps: List<AppInfo> = emptyList()
    private var currentResults: List<AppInfo> = emptyList()

    private val autoOpenHandler = Handler(Looper.getMainLooper())
    private val quickHandler = Handler(Looper.getMainLooper())
    private var quickToken = 0

    // Slow background drift
    private var bgDrawable: GradientDrawable? = null
    private var bgHue = 205f
    private var bgT = 0f
    private var lastBgFrame = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hiddenApps = HiddenApps(this)
        emptyView = findViewById(R.id.empty)
        search = findViewById(R.id.search)
        quickFind = findViewById(R.id.quickFind)
        list = findViewById(R.id.appList)
        devCheckBadge = findViewById(R.id.devCheckBadge)
        devCheckBadge.setOnClickListener { openDevCheck(null) }
        devCheckBadge.setOnLongClickListener { openSettings(); true }

        appAdapter = AppListAdapter(
            onLaunch = { launch(it) },
            onLongPress = { app, view -> showAppMenu(app, view) }
        )
        quickAdapter = QuickFindAdapter { entry -> openQuick(entry) }

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = appAdapter
        setupGestures(list)

        // Long-press anywhere on the home screen opens Settings.
        findViewById<View>(R.id.header).setOnLongClickListener { openSettings(); true }
        findViewById<View>(R.id.root).setOnLongClickListener { openSettings(); true }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (quickFind.text.isNullOrEmpty()) filter(s?.toString().orEmpty())
            }
        })
        search.setOnEditorActionListener { _, actionId, _ -> handleSearchAction(actionId) }

        quickFind.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateQuick(s?.toString().orEmpty())
        })
        quickFind.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) requestQuickPermissions()
        }

        if (intent?.getBooleanExtra(EXTRA_PICK_APPS, false) == true) {
            showAppPicker()
        }

        maybePromptDefaultLauncher()
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
        if (allApps.isNotEmpty()) loadApps()
        startBackgroundDrift()
        updateDevCheckBadge()
    }

    override fun onPause() {
        super.onPause()
        autoOpenHandler.removeCallbacksAndMessages(null)
        quickHandler.removeCallbacksAndMessages(null)
        stopBackgroundDrift()
    }

    // ------------------------------------------------ slow background drift
    private fun startBackgroundDrift() {
        if (bgDrawable == null) {
            val root = findViewById<View>(R.id.root)
            val gd = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                gradientType = GradientDrawable.RADIAL
                gradientRadius = maxOf(
                    resources.displayMetrics.widthPixels,
                    resources.displayMetrics.heightPixels
                ) * 1.15f
                colors = intArrayOf(0xFF101418.toInt(), 0xFF0B0B0C.toInt())
            }
            root.background = gd
            bgDrawable = gd
        }
        lastBgFrame = 0L
        bgHandler.post(bgTick)
    }

    private fun stopBackgroundDrift() {
        bgHandler.removeCallbacks(bgTick)
    }

    private val bgHandler = Handler(Looper.getMainLooper())
    private val bgTick = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val dt = if (lastBgFrame == 0L) 0.08f
            else ((now - lastBgFrame) / 1000f).coerceAtMost(0.3f)
            lastBgFrame = now

            bgT += dt
            bgHue = (bgHue + BG_HUE_SPEED * dt) % 360f

            // A soft coloured glow wanders slowly across the screen while the hue
            // itself drifts, so the gradient keeps rearranging itself without ever
            // being obvious about it.
            val cx = 0.5f + 0.34f * sin(bgT * 0.045f)
            val cy = 0.5f + 0.30f * cos(bgT * 0.031f)
            bgDrawable?.let { gd ->
                gd.setGradientCenter(cx, cy)
                gd.colors = intArrayOf(
                    tint(bgHue, 0.32f),
                    tint(bgHue + 34f, 0.05f)
                )
            }
            bgHandler.postDelayed(this, BG_FRAME_MS)
        }
    }

    /** A dark, softly tinted colour for the background gradient. */
    private fun tint(hue: Float, value: Float): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val rgb = Color.HSVToColor(floatArrayOf(h, 0.52f, value))
        return (0xFF shl 24) or (rgb and 0x00FFFFFF)
    }

    // ----------------------------------------------------------- app search
    private fun loadApps() {
        Thread {
            val apps = AppRepository.loadApps(this)
            runOnUiThread {
                allApps = apps
                if (quickFind.text.isNullOrEmpty()) filter(search.text?.toString().orEmpty())
            }
        }.start()
    }

    private fun filter(query: String) {
        autoOpenHandler.removeCallbacksAndMessages(null)
        list.adapter = appAdapter
        emptyView.text = getString(R.string.no_apps)

        val q = query.trim().lowercase()
        val visible = allApps.filter { !hiddenApps.isHidden(it.packageName) }
        val filtered = if (q.isEmpty()) {
            visible
        } else {
            visible.filter { it.label.lowercase().contains(q) }
        }

        currentResults = filtered
        appAdapter.submit(filtered)
        emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE

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

    // ----------------------------------------------------------- quick find
    private fun updateQuick(query: String) {
        if (query.isEmpty()) {
            filter(search.text?.toString().orEmpty())
            return
        }
        autoOpenHandler.removeCallbacksAndMessages(null)
        list.adapter = quickAdapter
        emptyView.text = getString(R.string.quickfind_none)

        val token = ++quickToken
        val q = query
        Thread {
            val results = QuickFind.search(this, q, ShortcutPrefs.isDevCheckEnabled(this))
            runOnUiThread {
                if (token != quickToken) return@runOnUiThread
                quickAdapter.submit(results)
                emptyView.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun openQuick(entry: QuickEntry) {
        quickFind.setText("")
        val id = entry.id
        if (id != null) {
            openDevCheck(DevCheck.tabFor(id))
            return
        }
        entry.intent?.let { startSafely(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun openDevCheck(tab: String?) {
        startActivity(
            Intent(this, DevCheckActivity::class.java)
                .putExtra(DevCheckActivity.EXTRA_TAB, tab)
        )
    }

    private fun requestQuickPermissions() {
        val missing = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.READ_CONTACTS)
        }
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.READ_CALENDAR)
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_QUICK)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_QUICK) {
            val q = quickFind.text?.toString().orEmpty()
            if (q.isNotEmpty()) updateQuick(q)
        }
    }

    // --------------------------------------------------------- long-press menu
    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun updateDevCheckBadge() {
        devCheckBadge.visibility =
            if (ShortcutPrefs.isDevCheckEnabled(this)) View.VISIBLE else View.GONE
    }

    private fun maybePromptDefaultLauncher() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PROMPT_SHOWN, false)) return
        if (HomeRole.isDefault(this)) return
        prefs.edit().putBoolean(KEY_PROMPT_SHOWN, true).apply()

        AlertDialog.Builder(this)
            .setTitle(R.string.set_default_title)
            .setMessage(R.string.set_default_message)
            .setPositiveButton(R.string.set_default_ok) { _, _ -> HomeRole.request(this) }
            .setNegativeButton(R.string.set_default_later, null)
            .show()
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
                if (list.findChildViewUnder(e.x, e.y) == null) {
                    openSettings()
                }
            }
        })

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
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:${app.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (packageManager.resolveActivity(intent, 0) == null) {
            Toast.makeText(this, R.string.uninstall_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        startSafely(intent)
    }

    private fun startSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.uninstall_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Launcher home; back should not exit the app list")
    override fun onBackPressed() {
        if (quickFind.text.isNotEmpty()) {
            quickFind.setText("")
        } else if (search.text.isNotEmpty()) {
            search.setText("")
        }
    }

    companion object {
        const val EXTRA_PICK_APPS = "pick_apps"
        private const val PREFS = "launcher_prefs"
        private const val KEY_PROMPT_SHOWN = "default_prompt_shown"
        private const val SWIPE_MIN = 100f
        private const val AUTO_OPEN_DELAY_MS = 350L
        private const val REQ_QUICK = 701
        /** Degrees per second for the background — a full cycle takes ~5 minutes. */
        private const val BG_HUE_SPEED = 1.6f
        /** ~12fps is plenty for a glow that moves this slowly. */
        private const val BG_FRAME_MS = 80L
    }
}
