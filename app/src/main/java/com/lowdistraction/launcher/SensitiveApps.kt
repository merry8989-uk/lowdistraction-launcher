package com.lowdistraction.launcher

import android.content.Context
import android.content.Intent

/**
 * Decides which apps get the "sensitive" treatment: while one of them is in the
 * foreground the assistive bubble is removed from the screen and the
 * accessibility service stays silent.
 *
 * An app counts as sensitive when it is
 *  - in the built-in starter list below (well-known Indian payment, banking and
 *    government apps), or
 *  - added by the user, or
 *  - detected as a tap-to-pay app, i.e. it declares an NFC Host Card Emulation
 *    service (this is how most UPI apps advertise themselves).
 */
object SensitiveApps {

    private const val PREFS = "sensitive_apps"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_STRICT = "strict"
    private const val KEY_AUTO_HCE = "auto_hce"
    private const val KEY_USER = "user_packages"
    private const val KEY_STRICT_PAUSED = "strict_paused"

    /** Starter list. Not exhaustive — the user can add anything missing. */
    val BUILT_IN: Set<String> = setOf(
        // UPI / wallets
        "com.google.android.apps.nbu.paisa.user",
        "com.phonepe.app",
        "net.one97.paytm",
        "in.org.npci.upiapp",
        "com.dreamplug.androidapp",
        "com.freecharge.android",
        "com.mobikwik_new",
        "in.amazon.mShop.android.shopping",
        "com.paypal.android.p2pmobile",
        // banks
        "com.sbi.lotusintouch",
        "com.sbi.SBIFreedomPlus",
        "com.csam.icici.bank.imobile",
        "com.axis.mobile",
        "com.msf.kbank.mobile",
        "com.idfcfirstbank.optimus",
        "com.hdfcbank.lite",
        "com.bankofbaroda.upi",
        // government
        "com.digilocker.android",
        "in.gov.uidai.mAadhaarPlus",
        "in.gov.umang.negd.g20",
        "com.nic.mparivahan",
        "in.gov.cowin"
    )

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_ENABLED, true)

    fun setEnabled(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_ENABLED, value).apply()

    /** Opt-in: switch accessibility off entirely over a sensitive app. */
    fun isStrict(c: Context): Boolean = prefs(c).getBoolean(KEY_STRICT, false)

    fun setStrict(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_STRICT, value).apply()

    fun isAutoHce(c: Context): Boolean = prefs(c).getBoolean(KEY_AUTO_HCE, true)

    fun setAutoHce(c: Context, value: Boolean) {
        prefs(c).edit().putBoolean(KEY_AUTO_HCE, value).apply()
        hceCache = null
    }

    fun userPackages(c: Context): Set<String> =
        prefs(c).getStringSet(KEY_USER, emptySet())!!.toSet()

    fun addUserPackage(c: Context, pkg: String) =
        prefs(c).edit().putStringSet(KEY_USER, userPackages(c) + pkg).apply()

    fun removeUserPackage(c: Context, pkg: String) =
        prefs(c).edit().putStringSet(KEY_USER, userPackages(c) - pkg).apply()

    /**
     * Set while strict mode has switched the accessibility service off, so the
     * launcher can offer to bring it back.
     */
    fun isStrictPaused(c: Context): Boolean = prefs(c).getBoolean(KEY_STRICT_PAUSED, false)

    fun setStrictPaused(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_STRICT_PAUSED, value).apply()

    @Volatile
    private var hceCache: Set<String>? = null

    /** Packages declaring an NFC Host Card Emulation (tap-to-pay) service. */
    private fun tapToPayPackages(c: Context): Set<String> {
        hceCache?.let { return it }
        val found = runCatching {
            c.packageManager
                .queryIntentServices(Intent(ACTION_HCE), 0)
                .mapNotNull { it.serviceInfo?.packageName }
                .toSet()
        }.getOrDefault(emptySet())
        hceCache = found
        return found
    }

    fun isSensitive(c: Context, pkg: String?): Boolean {
        if (pkg.isNullOrEmpty() || pkg == c.packageName) return false
        if (!isEnabled(c)) return false
        if (pkg in BUILT_IN || pkg in userPackages(c)) return true
        if (isAutoHce(c) && pkg in tapToPayPackages(c)) return true
        return false
    }

    fun label(c: Context, pkg: String): String = runCatching {
        c.packageManager.getApplicationLabel(
            c.packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    }.getOrDefault(pkg)

    const val ACTION_HCE = "android.nfc.cardemulation.HOST_APDU_SERVICE"
}
