package com.lowdistraction.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lowdistraction.launcher.bubble.AssistiveTouchService
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
    private lateinit var sortChip: TextView
    private lateinit var hiddenApps: HiddenApps

    private var allApps: List<AppInfo> = emptyList()
    private var currentResults: List<AppInfo> = emptyList()

    // Sizes for the two size orders; filled in on a worker thread when needed.
    private var appSizes: Map<String, Long> = emptyMap()
    private var sizesAttempted = false

    private val autoOpenHandler = Handler(Looper.getMainLooper())
    private val quickHandler = Handler(Looper.getMainLooper())
    private var quickToken = 0

    // The seven-second breather shown before an app opens
    private val gateHandler = Handler(Looper.getMainLooper())
    private var gateDialog: Dialog? = null
    private var gateRunnable: Runnable? = null
    private var gateApp: AppInfo? = null
    private var gateFinished = false
    private var lastLockAt = 0L

    // Slow background drift
    private lateinit var theme: Theme
    private var bgDrawable: GradientDrawable? = null
    private var bgHue = 205f
    private var bgT = 0f
    private var lastBgFrame = 0L

    // Diagnostics: the intent→outcome pair for a launch, plus friction counters.
    private var pendingAttempt: String? = null
    private var pendingRequestedAt = 0L
    private var lastLaunchAt = 0L
    private var firstValueLogged = false
    private var backPresses = 0
    private var firstBackAt = 0L
    private var rageTarget = -1
    private var rageCount = 0
    private var rageWindowStart = 0L

    /** Deepest quarter of the list reached since the screen was last shown. */
    private var scrollBand = -1
    private val searchLogHandler = Handler(Looper.getMainLooper())
    private var searchLogRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startedAt = System.currentTimeMillis()
        setContentView(R.layout.activity_main)

        hiddenApps = HiddenApps(this)
        emptyView = findViewById(R.id.empty)
        search = findViewById(R.id.search)
        quickFind = findViewById(R.id.quickFind)
        list = findViewById(R.id.appList)
        devCheckBadge = findViewById(R.id.devCheckBadge)
        devCheckBadge.setOnClickListener { openDevCheck(null) }
        devCheckBadge.setOnLongClickListener { openSettings(); true }

        sortChip = findViewById(R.id.sortChip)
        sortChip.setOnClickListener { showSortDialog() }

        appAdapter = AppListAdapter(
            onLaunch = { requestLaunch(it) },
            onLongPress = { app, view -> showAppMenu(app, view) }
        )
        theme = Look.theme(this)
        appAdapter.refreshLook(this)
        quickAdapter = QuickFindAdapter { entry -> openQuick(entry) }

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = appAdapter
        setupGestures(list)
        setupScrollDepth(list)

        // Long-press anywhere on the home screen opens Settings.
        findViewById<View>(R.id.header).setOnLongClickListener { openSettings(); true }
        findViewById<View>(R.id.root).setOnLongClickListener { openSettings(); true }
        // Long-press opens Settings; a double-tap in the same places locks the screen.
        attachDoubleTap(findViewById(R.id.header))
        attachDoubleTap(findViewById(R.id.root))

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

        if (ShortcutPrefs.isGuideSeen(this)) {
            maybePromptDefaultLauncher()
        } else {
            // First run: show the guide before anything else.
            startActivity(Intent(this, GuideActivity::class.java))
        }
        loadApps()

        // One launch is one session: the first Activity start creates it, a later
        // one is a warm start that keeps the same session id.
        Diag.startSession(this, Diag.coldStartMs(), System.currentTimeMillis() - startedAt)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_PICK_APPS, false)) {
            showAppPicker()
        }
    }

    override fun onResume() {
        super.onResume()
        Events.screenView(this, SCREEN_HOME)
        Diag.startWatch(this)
        // We are the launcher, so by definition nothing sensitive is in front.
        AssistiveTouchService.clearSensitive()
        if (allApps.isNotEmpty()) loadApps()
        applyLook()
        updateDevCheckBadge()
        maybeRebuildFileIndex()
        maybeOfferStrictResume()
    }

    // ----------------------------------------------------------- appearance
    /** Applies the saved theme and sizing to everything on this screen. */
    private fun applyLook() {
        theme = Look.theme(this)

        findViewById<TextView>(R.id.clock).setTextColor(theme.textPrimary)
        findViewById<TextView>(R.id.date).setTextColor(theme.textSecondary)
        findViewById<TextView>(R.id.devCheckBadge).setTextColor(theme.accent)
        search.setTextColor(theme.textPrimary)
        search.setHintTextColor(theme.textSecondary)
        quickFind.setTextColor(theme.textPrimary)
        quickFind.setHintTextColor(theme.textSecondary)
        emptyView.setTextColor(theme.textSecondary)

        search.background = rounded(theme.surface, 10)
        quickFind.background = rounded(theme.surface, 10)
        findViewById<TextView>(R.id.devCheckBadge).background =
            rounded(theme.surface, 14, Look.blend(theme.surface, theme.accent, 0.45f))
        sortChip.setTextColor(theme.accent)
        sortChip.background =
            rounded(theme.surface, 14, Look.blend(theme.surface, theme.accent, 0.45f))
        updateSortChip()

        list.layoutManager = if (Look.displayMode(this) == DisplayMode.ICON) {
            GridLayoutManager(this, gridSpans())
        } else {
            LinearLayoutManager(this)
        }
        appAdapter.refreshLook(this)
        applyQuickFindVisibility()
        renderPinned()

        // Rebuild the background with the new palette and restart the drift.
        bgDrawable = null
        bgHue = theme.glowHue
        startBackgroundDrift()

        window.statusBarColor = theme.bg
        window.navigationBarColor = theme.bg
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = theme.light
        controller.isAppearanceLightNavigationBars = theme.light
    }

    /** The second box exists only while at least one source is switched on. */
    private fun applyQuickFindVisibility() {
        val on = SearchSources.anyEnabled(this)
        quickFind.visibility = if (on) View.VISIBLE else View.GONE
        if (!on && !quickFind.text.isNullOrEmpty()) quickFind.setText("")
        quickFind.hint = SearchSources.hint(this)
    }

    /**
     * The pinned apps get their own grid above the list: two columns, four rows
     * at most, always bold — heavier than anything else on screen — and half
     * again the chosen text size.
     */
    private fun renderPinned() {
        if (!::theme.isInitialized) theme = Look.theme(this)
        val box = findViewById<LinearLayout>(R.id.pinnedBox)
        val divider = findViewById<View>(R.id.pinnedDivider)
        box.removeAllViews()

        val byComponent = allApps.associateBy { it.component.flattenToString() }
        val columns = Look.pinnedCols(this)
        val apps = PinnedApps(this).all()
            .mapNotNull { byComponent[it] }
            .take(Look.pinnedCapacity(this))
        if (apps.isEmpty()) {
            box.visibility = View.GONE
            divider.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        divider.visibility = View.VISIBLE
        divider.setBackgroundColor(Look.blend(theme.bg, theme.textSecondary, 0.35f))

        for (pair in apps.chunked(columns)) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (app in pair) {
                line.addView(
                    pinnedCell(app),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
            // Keep the columns aligned when the last row is half full.
            repeat(columns - pair.size) {
                line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            }
            box.addView(line)
        }
    }

    /** One pinned cell: bold, 1.5x, carrying the display mode's icon. */
    private fun pinnedCell(app: AppInfo): LinearLayout {
        val cell = layoutInflater.inflate(R.layout.item_app, null, false) as LinearLayout
        val icon = cell.findViewById<ImageView>(R.id.appIcon)
        val label = cell.findViewById<TextView>(R.id.appLabel)

        label.text = app.label
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, Look.nameSize(this) * PINNED_SCALE)
        label.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        label.paint.isFakeBoldText = true
        label.letterSpacing = Look.nameSpacing(this) / 100f
        label.setTextColor(theme.textPrimary)

        if (Look.displayMode(this) == DisplayMode.NAME) {
            icon.visibility = View.GONE
        } else {
            val density = resources.displayMetrics.density
            val iconPx = (Look.iconSize(this) * PINNED_SCALE * density).toInt()
            icon.visibility = View.VISIBLE
            val lp = icon.layoutParams as LinearLayout.LayoutParams
            lp.width = iconPx
            lp.height = iconPx
            icon.layoutParams = lp
            val labelLp = label.layoutParams as LinearLayout.LayoutParams
            labelLp.marginStart = dp(10)
            label.layoutParams = labelLp
            runCatching { packageManager.getApplicationIcon(app.packageName) }
                .getOrNull()
                ?.let { base ->
                    icon.setImageDrawable(
                        ShapedIconDrawable(
                            base,
                            Look.iconShape(this),
                            Look.iconOutline(this) * density,
                            theme.accent
                        )
                    )
                }
        }

        cell.setOnClickListener { requestLaunch(app) }
        cell.setOnLongClickListener { view ->
            showAppMenu(app, view)
            true
        }
        return cell
    }

    private fun rounded(fill: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun gridSpans(): Int {
        val metrics = resources.displayMetrics
        val widthDp = metrics.widthPixels / metrics.density - 40f
        val cell = Look.iconSize(this) + 28f
        return (widthDp / cell).toInt().coerceIn(3, 8)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Three taps on the same row inside two seconds is a rage tap. */
    private fun trackRageTap(position: Int) {
        val now = System.currentTimeMillis()
        if (position == rageTarget && now - rageWindowStart < 2000L) {
            rageCount++
            if (rageCount == 3) {
                Events.rageTap(this, "app_row", rageCount, now - rageWindowStart)
            }
        } else {
            rageTarget = position
            rageCount = 1
            rageWindowStart = now
        }
    }

    override fun onPause() {
        super.onPause()
        autoOpenHandler.removeCallbacksAndMessages(null)
        quickHandler.removeCallbacksAndMessages(null)
        cancelGate()
        stopBackgroundDrift()
        Diag.stopWatch()
        // Leaving the launcher without opening anything is a drop-off; leaving
        // because an app was just opened is not.
        if (System.currentTimeMillis() - lastLaunchAt > 2000L) Diag.endSession(this)
        scrollBand = -1
    }

    override fun onDestroy() {
        TimerTick.release()
        super.onDestroy()
    }

    // ------------------------------------------------ slow background drift
    private fun startBackgroundDrift() {
        if (bgDrawable == null) {
            val root = findViewById<View>(R.id.root)
            val gd = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = maxOf(
                    resources.displayMetrics.widthPixels,
                    resources.displayMetrics.heightPixels
                ) * 1.15f
                colors = intArrayOf(theme.bg, theme.bg)
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
            // itself drifts, so the background keeps rearranging itself without
            // ever being obvious about it. Its colours come from the theme.
            val cx = 0.5f + 0.34f * sin(bgT * 0.045f)
            val cy = 0.5f + 0.30f * cos(bgT * 0.031f)
            val glow = if (Look.glowEnabled(this@MainActivity)) {
                Look.glowColor(theme, bgHue)
            } else {
                theme.bg
            }
            bgDrawable?.let { gd ->
                gd.setGradientCenter(cx, cy)
                gd.colors = intArrayOf(glow, theme.bg)
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
            val t0 = System.currentTimeMillis()
            val apps = AppRepository.loadApps(this)
            val took = System.currentTimeMillis() - t0
            runOnUiThread {
                allApps = apps
                // A fresh app list may include new apps, so sizes are re-read.
                appSizes = emptyMap()
                sizesAttempted = false
                if (quickFind.text.isNullOrEmpty()) filter(search.text?.toString().orEmpty())
                renderPinned()
                Events.appListLoaded(
                    this, apps.size, took,
                    hiddenApps.all().size, SortPrefs.mode(this).name
                )
            }
        }.start()
    }

    private fun filter(query: String) {
        val startedAt = System.currentTimeMillis()
        autoOpenHandler.removeCallbacksAndMessages(null)
        list.adapter = appAdapter
        emptyView.text = getString(R.string.no_apps)

        val q = query.trim().lowercase()
        val visible = allApps.filter { !hiddenApps.isHidden(it.packageName) }
        val matched = if (q.isEmpty()) {
            visible
        } else {
            visible.filter { it.label.lowercase().contains(q) }
        }
        val filtered = AppSorter.sort(this, matched, SortPrefs.mode(this), appSizes)
        ensureSizes()

        currentResults = filtered
        appAdapter.submit(filtered)
        emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE

        if (q.isNotEmpty() && filtered.size == 1) {
            val target = filtered.first()
            autoOpenHandler.postDelayed(
                { requestLaunch(target, Events.LaunchSource.SEARCH) }, AUTO_OPEN_DELAY_MS
            )
        }

        logSearchQuery(q, filtered.size)

        // Only worth a marker when a rebuild is slow enough to be felt: one
        // frame is about 16 ms. A fast filter stays silent, so the marker means
        // something when it appears.
        val took = System.currentTimeMillis() - startedAt
        if (took > SLOW_RENDER_MS) Events.performanceMarker(this, "home_list_render", took)
    }

    /**
     * Records how far down the list the user got, once per quarter, so the
     * report can say whether the list is too long to scan.
     */
    private fun setupScrollDepth(list: RecyclerView) {
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val range = rv.computeVerticalScrollRange()
                if (range <= 0 || rv.height <= 0) return
                val seen = ((rv.computeVerticalScrollOffset() + rv.height) * 100 / range)
                val band = seen.coerceIn(0, 100) / 25
                if (band > scrollBand) {
                    scrollBand = band
                    Events.scrollDepth(this@MainActivity, seen.coerceIn(0, 100), SCREEN_HOME)
                }
            }
        })
    }

    /**
     * Records what a search returned — only the query's length, never the query.
     * Debounced, so typing a word is one event rather than one per keystroke.
     */
    private fun logSearchQuery(query: String, resultCount: Int) {
        searchLogRunnable?.let { searchLogHandler.removeCallbacks(it) }
        if (query.isEmpty()) return
        val runnable = Runnable {
            if (resultCount == 0) {
                Events.searchNoResults(this, query.length, Events.SearchSource.APPS)
            } else {
                Events.searchPerformed(
                    this, query.length, resultCount, Events.SearchSource.APPS
                )
            }
        }
        searchLogRunnable = runnable
        searchLogHandler.postDelayed(runnable, 700L)
    }

    private fun handleSearchAction(actionId: Int): Boolean {
        val submitted = actionId == EditorInfo.IME_ACTION_SEARCH ||
            actionId == EditorInfo.IME_ACTION_DONE ||
            actionId == EditorInfo.IME_ACTION_GO
        if (submitted) {
            currentResults.firstOrNull()?.let { app ->
                autoOpenHandler.removeCallbacksAndMessages(null)
                requestLaunch(app)
                return true
            }
        }
        return false
    }

    /**
     * Every app opening goes through here: a seven-second breather first, which
     * opens the app if it runs out and cancels if the user taps anywhere.
     */
    private fun requestLaunch(app: AppInfo, source: Events.LaunchSource = Events.LaunchSource.LIST) {
        val attempt = Diag.newAttempt()
        pendingAttempt = attempt
        pendingRequestedAt = System.currentTimeMillis()
        Diag.setLastAction("launch " + app.packageName)
        Events.appLaunchRequested(
            this, app.packageName, source,
            ShortcutPrefs.isLaunchPauseEnabled(this), attempt
        )
        if (!ShortcutPrefs.isLaunchPauseEnabled(this)) {
            launch(app)
            return
        }
        cancelGate()
        gateFinished = false
        gateApp = app

        val density = resources.displayMetrics.density
        fun px(value: Int) = (value * density).toInt()

        val seconds = ShortcutPrefs.pauseSeconds(this)
        val counter = TextView(this).apply {
            text = seconds.toString()
            setTextColor(theme.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 52f)
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, px(22), 0, 0)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(px(28), px(28), px(28), px(28))
            background = ColorDrawable(theme.bg)
        }
        box.addView(
            TextView(this).apply {
                text = app.label
                setTextColor(theme.accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
            }
        )
        box.addView(
            TextView(this).apply {
                text = getString(R.string.pause_question)
                setTextColor(theme.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setPadding(0, px(16), 0, 0)
            }
        )
        box.addView(
            TextView(this).apply {
                text = getString(R.string.pause_sub)
                setTextColor(theme.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setPadding(0, px(10), 0, 0)
            }
        )
        box.addView(counter)
        box.addView(
            TextView(this).apply {
                text = getString(R.string.pause_cancel_hint, seconds)
                setTextColor(theme.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setPadding(0, px(14), 0, 0)
            }
        )

        // A plain full-screen dialog rather than an AlertDialog. An AlertDialog's
        // content view is only as tall as its own text, so taps above and below
        // that strip never reached the cancel listener. This one fills the screen,
        // so a tap truly anywhere cancels.
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(
            box,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        dialog.setCancelable(false)
        gateDialog = dialog

        var remaining = seconds
        val tick = object : Runnable {
            override fun run() {
                if (gateFinished) return
                remaining--
                if (remaining <= 0) {
                    val target = gateApp
                    cancelGate()
                    if (target != null) launch(target)
                    return
                }
                counter.text = remaining.toString()
                playTick(remaining)
                gateHandler.postDelayed(this, 1000L)
            }
        }
        gateRunnable = tick

        box.setOnClickListener {
            if (gateFinished) return@setOnClickListener
            val target = gateApp
            cancelGate()
            if (target != null) announceCancel(target)
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP &&
                !gateFinished
            ) {
                val target = gateApp
                cancelGate()
                if (target != null) announceCancel(target)
                true
            } else {
                false
            }
        }

        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        dialog.window?.setBackgroundDrawable(ColorDrawable(theme.bg))
        playTick(remaining)
        gateHandler.postDelayed(tick, 1000L)
    }

    /** A soft tick each second; the last few are a little brighter. */
    private fun playTick(remaining: Int) {
        if (!ShortcutPrefs.isTickSoundEnabled(this)) return
        TimerTick.play(this, urgent = remaining <= 3)
    }

    private fun announceCancel(app: AppInfo) {
        Events.appLaunchResult(
            this, app.packageName, pendingAttempt, Events.Outcome.CANCELLED, waitedMs()
        )
        pendingAttempt = null
        pendingRequestedAt = 0L
        Diag.setLastAction("cancelled " + app.packageName)
        Toast.makeText(this, getString(R.string.pause_cancelled, app.label), Toast.LENGTH_SHORT)
            .show()
    }

    /** How long the user waited on the breather, in milliseconds. */
    private fun waitedMs(): Long =
        if (pendingRequestedAt == 0L) 0L else System.currentTimeMillis() - pendingRequestedAt

    private fun cancelGate() {
        gateFinished = true
        gateRunnable?.let { gateHandler.removeCallbacks(it) }
        gateRunnable = null
        gateDialog?.let { runCatching { it.dismiss() } }
        gateDialog = null
    }

    /** Locks the device through the accessibility service. */
    private fun lockScreen() {
        val now = SystemClock.uptimeMillis()
        if (now - lastLockAt < LOCK_DEBOUNCE_MS) return
        lastLockAt = now
        if (AssistiveTouchService.lock()) return
        Toast.makeText(this, R.string.lock_needs_accessibility, Toast.LENGTH_SHORT).show()
    }

    /** Double-tap anywhere on [view] locks the screen; taps and long-press still work. */
    private fun attachDoubleTap(view: View) {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                lockScreen()
                return true
            }
        })
        view.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            false
        }
    }

    // -------------------------------------------------------------- sorting
    /**
     * App sizes are asked of the system, which is slow, so they are fetched off
     * the UI thread the first time a size order is used and then kept.
     */
    private fun ensureSizes() {
        val mode = SortPrefs.mode(this)
        if (mode != SortMode.LARGEST && mode != SortMode.SMALLEST) return
        // Only ever tried once per app-list load, so this cannot loop.
        if (sizesAttempted) return
        sizesAttempted = true
        Thread {
            val sizes = AppSorter.loadSizes(this, allApps)
            runOnUiThread {
                appSizes = sizes
                if (SortPrefs.mode(this) == SortMode.LARGEST ||
                    SortPrefs.mode(this) == SortMode.SMALLEST
                ) {
                    filter(search.text?.toString().orEmpty())
                }
            }
        }.start()
    }

    private fun updateSortChip() {
        sortChip.text = getString(R.string.sort_chip, getString(sortLabel(SortPrefs.mode(this))))
    }

    private fun showSortDialog() {
        val modes = SortPrefs.enabledModes(this)
        val hasAccess = AppSorter.hasUsageAccess(this)
        val labels = modes.map { mode ->
            val name = getString(sortLabel(mode))
            if (mode.needsUsageAccess && !hasAccess) "$name  (needs access)" else name
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.sort_title)
            .setSingleChoiceItems(labels, modes.indexOf(SortPrefs.mode(this))) { dialog, which ->
                val mode = modes[which]
                dialog.dismiss()
                if (mode.needsUsageAccess && !AppSorter.hasUsageAccess(this)) {
                    promptUsageAccess()
                    return@setSingleChoiceItems
                }
                SortPrefs.setMode(this, mode)
                updateSortChip()
                filter(search.text?.toString().orEmpty())
            }
            .setNeutralButton(R.string.settings_row_sorting) { _, _ ->
                startActivity(Intent(this, SortSettingsActivity::class.java))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** "Most time used" needs Usage access, which only the system can grant. */
    private fun promptUsageAccess() {
        AlertDialog.Builder(this)
            .setTitle(R.string.sort_usage_title)
            .setMessage(R.string.sort_usage_message)
            .setPositiveButton(R.string.sort_usage_ok) { _, _ ->
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sortLabel(mode: SortMode): Int = when (mode) {
        SortMode.NAME -> R.string.sort_name
        SortMode.NAME_DESC -> R.string.sort_name_desc
        SortMode.PACKAGE -> R.string.sort_package
        SortMode.PACKAGE_DESC -> R.string.sort_package_desc
        SortMode.INSTALLED -> R.string.sort_installed
        SortMode.INSTALLED_OLDEST -> R.string.sort_installed_oldest
        SortMode.UPDATED -> R.string.sort_updated
        SortMode.MOST_OPENED -> R.string.sort_most_opened
        SortMode.LEAST_OPENED -> R.string.sort_least_opened
        SortMode.RECENTLY_USED -> R.string.sort_recent
        SortMode.LEAST_RECENTLY_USED -> R.string.sort_least_recent
        SortMode.MOST_TIME -> R.string.sort_most_time
        SortMode.LEAST_TIME -> R.string.sort_least_time
        SortMode.LARGEST -> R.string.sort_largest
        SortMode.SMALLEST -> R.string.sort_smallest
        SortMode.SHORTEST_NAME -> R.string.sort_shortest
        SortMode.LONGEST_NAME -> R.string.sort_longest
    }

    private fun launch(app: AppInfo) {
        lastLaunchAt = System.currentTimeMillis()
        Events.appLaunchResult(
            this, app.packageName, pendingAttempt, Events.Outcome.SUCCESS, waitedMs()
        )
        if (!firstValueLogged) {
            firstValueLogged = true
            Events.timeToFirstValue(this, "first_app_opened", Diag.sessionDurationMs())
        }
        pendingAttempt = null
        pendingRequestedAt = 0L
        Diag.setLastAction("opened " + app.packageName)
        AppUsage.record(this, app.component.flattenToString())
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
            val results = QuickFind.search(this, q)
            runOnUiThread {
                if (token != quickToken) return@runOnUiThread
                quickAdapter.submit(results)
                emptyView.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun openQuick(entry: QuickEntry) {
        quickFind.setText("")
        val folder = entry.folder
        if (folder != null) {
            showFolder(folder)
            return
        }
        val id = entry.id
        if (id != null) {
            openDevCheck(DevCheck.tabFor(id))
            return
        }
        entry.intent?.let { startSafely(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Lists what is inside a folder result, so a folder is useful to tap too. */
    private fun showFolder(folder: FileEntry) {
        val children = FileIndex.children(this, folder)
        if (children.isEmpty()) {
            Toast.makeText(this, R.string.files_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = children.map { if (it.isDirectory) it.name + "/" else it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(folder.name)
            .setItems(labels) { _, which -> openFileEntry(children[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openFileEntry(f: FileEntry) {
        if (f.isDirectory) {
            showFolder(f)
            return
        }
        startSafely(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(f.uri), f.mime.ifEmpty { "*/*" })
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }

    /** Keeps the file index fresh without ever blocking the home screen. */
    private fun maybeRebuildFileIndex() {
        Thread {
            runCatching {
                if (!FileIndex.isEnabled(this)) return@runCatching
                val stale = FileIndex.count(this) == 0 ||
                    System.currentTimeMillis() - FileIndex.indexedAt(this) > INDEX_MAX_AGE_MS
                if (stale) FileIndex.rebuild(this)
            }
        }.start()
    }

    /**
     * Strict mode switches accessibility off over a sensitive app, and Android
     * gives no way to switch it back on for us — so ask the user once.
     */
    private fun maybeOfferStrictResume() {
        if (!SensitiveApps.isStrictPaused(this)) return
        if (AssistiveTouchService.isReady) {
            SensitiveApps.setStrictPaused(this, false)
            return
        }
        SensitiveApps.setStrictPaused(this, false)
        AlertDialog.Builder(this)
            .setTitle(R.string.sensitive_paused_title)
            .setMessage(R.string.sensitive_paused_message)
            .setPositiveButton(R.string.sensitive_paused_ok) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openDevCheck(tab: String?) {
        startActivity(
            Intent(this, DevCheckActivity::class.java)
                .putExtra(DevCheckActivity.EXTRA_TAB, tab)
        )
    }

    private fun requestQuickPermissions() {
        val missing = ArrayList<String>()
        if (SearchSources.contacts(this) &&
            checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.READ_CONTACTS)
        }
        if (SearchSources.calendar(this) &&
            checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED
        ) {
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
            .setPositiveButton(R.string.set_default_ok) { _, _ ->
                if (!HomeRole.openHomeSettings(this)) HomeRole.request(this)
            }
            .setNegativeButton(R.string.set_default_later, null)
            .show()
    }

    private fun showAppMenu(app: AppInfo, anchor: View) {
        val sensitive = SensitiveApps.isSensitive(this, app.packageName)
        val component = app.component.flattenToString()
        val pinned = PinnedApps(this).isPinned(component)
        val options = arrayOf(
            getString(R.string.action_app_info),
            getString(R.string.action_uninstall),
            getString(R.string.action_hide),
            if (sensitive) getString(R.string.action_show_bubble)
            else getString(R.string.action_hide_bubble),
            if (pinned) getString(R.string.action_unpin) else getString(R.string.action_pin)
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
                    3 -> {
                        if (sensitive) {
                            SensitiveApps.allowBubble(this, app.packageName)
                            Toast.makeText(
                                this,
                                getString(R.string.sensitive_allowed, app.label),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            SensitiveApps.hideBubble(this, app.packageName)
                            Toast.makeText(
                                this,
                                getString(R.string.sensitive_added, app.label),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    4 -> {
                        val store = PinnedApps(this)
                        val capacity = Look.pinnedCapacity(this)
                        val message = if (pinned) {
                            store.remove(component)
                            getString(R.string.pinned_removed, app.label)
                        } else if (store.all().size < capacity && store.add(component)) {
                            getString(R.string.pinned_added, app.label)
                        } else {
                            getString(R.string.pinned_full, capacity)
                        }
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                        renderPinned()
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

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Only on the empty space, so double-tapping an app row is not a lock.
                if (list.findChildViewUnder(e.x, e.y) == null) {
                    lockScreen()
                    return true
                }
                return false
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val child = list.findChildViewUnder(e.x, e.y)
                if (child == null) {
                    // A tap on empty space does nothing at all.
                    Events.deadClick(this@MainActivity, "home_empty")
                } else {
                    trackRageTap(list.getChildAdapterPosition(child))
                }
                return false
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
            Events.errorStateShown(this, "uninstall_unavailable", SCREEN_HOME, "uninstall_unavailable")
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
        val now = System.currentTimeMillis()
        if (now - firstBackAt > 2000L) {
            firstBackAt = now
            backPresses = 1
        } else {
            backPresses++
            if (backPresses == 3) {
                Events.backButtonSpam(this, backPresses, now - firstBackAt, SCREEN_HOME)
            }
        }
        if (quickFind.visibility == View.VISIBLE && quickFind.text.isNotEmpty()) {
            quickFind.setText("")
        } else if (search.text.isNotEmpty()) {
            search.setText("")
        }
    }

    companion object {
        const val EXTRA_PICK_APPS = "pick_apps"
        private const val SCREEN_HOME = "Home"
        private const val SLOW_RENDER_MS = 16L
        private const val PREFS = "launcher_prefs"
        private const val KEY_PROMPT_SHOWN = "default_prompt_shown"
        private const val SWIPE_MIN = 100f
        private const val AUTO_OPEN_DELAY_MS = 350L
        private const val REQ_QUICK = 701
        private const val INDEX_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        /** Pinned rows are drawn half again as large as the rest. */
        private const val PINNED_SCALE = 1.5f
        private const val LOCK_DEBOUNCE_MS = 600L
        /** Degrees per second for the background — a full cycle takes ~5 minutes. */
        private const val BG_HUE_SPEED = 1.6f
        /** ~12fps is plenty for a glow that moves this slowly. */
        private const val BG_FRAME_MS = 80L
    }
}
