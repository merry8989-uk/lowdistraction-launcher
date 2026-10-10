package com.lowdistraction.launcher

import android.content.Context
import android.content.Intent
import java.util.Locale

/**
 * Decides which apps get the "sensitive" treatment: while one of them is in the
 * foreground the assistive bubble is removed from the screen and the
 * accessibility service stays silent.
 *
 * An app counts as sensitive when any of these is true:
 *  1. it is in the curated list below (Indian payment, banking, NBFC and
 *     government apps),
 *  2. the user added it by hand,
 *  3. it declares an NFC Host Card Emulation (tap-to-pay) service — this is how
 *     most UPI apps advertise themselves,
 *  4. its name or package matches the payment / government word lists, which is
 *     what makes the coverage "all of them" rather than only the ones someone
 *     remembered to list.
 *
 * The user can also force an app either way: `allowBubble` wins over everything.
 */
object SensitiveApps {

    private const val PREFS = "sensitive_apps"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_STRICT = "strict"
    private const val KEY_AUTO_HCE = "auto_hce"
    private const val KEY_AUTO_NAME = "auto_name"
    private const val KEY_USER = "user_packages"
    private const val KEY_EXCLUDED = "excluded_packages"
    private const val KEY_STRICT_PAUSED = "strict_paused"

    /**
     * Curated starter list: UPI / wallets, banks, NBFCs and lenders, brokers,
     * insurers, and central + state government services. A package that is not
     * installed simply never matches, so the list can afford to be generous.
     */
    val BUILT_IN: Set<String> = setOf(
        // ---- UPI, wallets, payment gateways ----
        "com.google.android.apps.nbu.paisa.user",
        "com.phonepe.app",
        "net.one97.paytm",
        "in.org.npci.upiapp",
        "com.dreamplug.androidapp",
        "com.freecharge.android",
        "com.mobikwik_new",
        "com.paypal.android.p2pmobile",
        "com.samsung.android.spay",
        "com.google.android.apps.walletnfcrel",
        "com.razorpay.payments",
        "com.payu.payumoney",
        "com.snapwork.hdfc",
        "in.amazon.mShop.android.shopping",
        "com.flipkart.android",
        // ---- banks ----
        "com.sbi.lotusintouch",
        "com.sbi.SBIFreedomPlus",
        "com.sbi.yono",
        "com.csam.icici.bank.imobile",
        "com.axis.mobile",
        "com.msf.kbank.mobile",
        "com.idfcfirstbank.optimus",
        "com.hdfcbank.lite",
        "com.hdfcbank.mobilebanking",
        "com.bankofbaroda.upi",
        "com.bankofbaroda.mconnect",
        "com.unionbankofindia.unionmobile",
        "com.canarabank.mobility",
        "com.pnb.mobilebanking",
        "com.indusind.mobilebanking",
        "com.yesbank.mobile",
        "com.federalbank.mobilebanking",
        "com.rbl.bank.mobilebanking",
        "com.dbs.in.mobilebanking",
        "com.hsbc.hsbcindia",
        "com.citibank.mobile",
        "com.sc.mobile",
        "com.cordys.pnb",
        // ---- NBFCs, lenders, fintech ----
        "com.naviapp",
        "com.dhani.app",
        "com.kreditbee.android",
        "in.zestmoney.android",
        "com.bajajfinserv",
        "com.bajajfinserv.app",
        "com.tatacapital",
        "com.paisabazaar",
        "com.moneyview",
        "com.earlysalary",
        "com.fibemoney",
        "com.lendingkart",
        "com.indifi",
        "com.cashkaro",
        "com.simpl.android",
        "com.lazypay.app",
        // ---- broking, investing, insurance ----
        "com.groww",
        "com.zerodha.kite",
        "com.upstox",
        "com.angelbroking",
        "com.moneycontrol",
        "com.policybazaar",
        "com.acko.android",
        "com.godigit",
        "com.nsdl.apan",
        "in.mutualfunds",
        // ---- central government ----
        "com.digilocker.android",
        "in.gov.uidai.mAadhaarPlus",
        "in.gov.umang.negd.g20",
        "com.nic.mparivahan",
        "in.gov.cowin",
        "cris.org.in.prs.ima",
        "com.nic.epfo",
        "in.gov.abdm.abha",
        "com.nic.abha",
        "in.gov.pmkisan",
        "com.nsdl.nsp",
        "in.gov.ayushmanbharat",
        "com.nic.ayushman",
        "in.gov.csc",
        "com.csc.csc",
        "in.gov.epass",
        "in.gov.incometax",
        "com.gst.gst",
        "in.gov.nic.gst",
        "in.nic.pesu",
        "in.gov.indiapost",
        "com.indiapost.mobile",
        // ---- state / civic services ----
        "in.gov.maharashtra.maha",
        "com.mahaonline.app",
        "in.gov.up.erevenue",
        "in.gov.kerala",
        "com.tn.gov",
        "in.gov.karnataka",
        "in.gov.telangana",
        "in.gov.wb",
        "in.gov.rajasthan",
        "in.gov.gujarat"
    )

    /**
     * Words that mark an app as a money app. Matched against the package name
     * (substring) and the app label (whole word), so "Bank of Baroda" and
     * `com.some.nbfc.app` both land here.
     */
    private val MONEY_WORDS = listOf(
        "upi", "bhim", "wallet", "paytm", "phonepe", "mobikwik", "freecharge",
        "cred", "paypal", "razorpay", "cashfree", "instamojo", "simpl",
        "lazypay", "bajaj", "finserv", "nbfc", "fintech", "lending", "loan",
        "credit", "debit", "netbanking", "mobilebanking", "banking", "imobile",
        "yono", "bank", "icici", "hdfc", "kotak", "axisbank", "idfc",
        "indusind", "yesbank", "canara", "bankofbaroda", "unionbank",
        "punjabnationalbank", "federalbank", "rblbank", "hsbc", "citibank",
        "standardchartered", "demat", "broking", "trading", "zerodha", "groww",
        "upstox", "angelone", "invest", "mutualfund", "insurance",
        "policybazaar", "acko", "nps", "ppf", "kyc", "neft", "imps", "rupay",
        "paisa", "paisabazaar", "moneyview", "kreditbee", "dhani", "navi",
        "zestmoney", "earlysalary", "payu", "billdesk", "recharge"
    )

    /** Words that mark an app as a government service. */
    private val GOV_WORDS = listOf(
        "gov", "govt", "government", "nic.in", "aadhaar", "aadhar", "uidai",
        "digilocker", "umang", "mparivahan", "parivahan", "vahan", "sarathi",
        "yojana", "seva", "eseva", "epfo", "gst", "incometax", "ayushman",
        "cowin", "abha", "abdm", "kisan", "pmjay", "irctc", "railway", "rail",
        "metro", "passport", "voter", "csc", "ration", "municipal", "nagar",
        "police", "court", "myscheme", "scholarship", "bhamashah", "egov",
        "epass", "indiapost", "eoffice", "pds"
    )

    private val ALL_WORDS = MONEY_WORDS + GOV_WORDS

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

    fun isAutoName(c: Context): Boolean = prefs(c).getBoolean(KEY_AUTO_NAME, true)

    fun setAutoName(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_AUTO_NAME, value).apply()

    fun userPackages(c: Context): Set<String> =
        prefs(c).getStringSet(KEY_USER, emptySet())!!.toSet()

    fun excludedPackages(c: Context): Set<String> =
        prefs(c).getStringSet(KEY_EXCLUDED, emptySet())!!.toSet()

    /** Force the bubble to hide over this app, whatever else says otherwise. */
    fun hideBubble(c: Context, pkg: String) {
        prefs(c).edit()
            .putStringSet(KEY_USER, userPackages(c) + pkg)
            .putStringSet(KEY_EXCLUDED, excludedPackages(c) - pkg)
            .apply()
    }

    /** Force the bubble to stay over this app, even if it looks sensitive. */
    fun allowBubble(c: Context, pkg: String) {
        prefs(c).edit()
            .putStringSet(KEY_EXCLUDED, excludedPackages(c) + pkg)
            .putStringSet(KEY_USER, userPackages(c) - pkg)
            .apply()
    }

    fun removeUserPackage(c: Context, pkg: String) =
        prefs(c).edit().putStringSet(KEY_USER, userPackages(c) - pkg).apply()

    fun removeExcluded(c: Context, pkg: String) =
        prefs(c).edit().putStringSet(KEY_EXCLUDED, excludedPackages(c) - pkg).apply()

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
        if (pkg in excludedPackages(c)) return false
        if (pkg in BUILT_IN || pkg in userPackages(c)) return true
        if (isAutoHce(c) && pkg in tapToPayPackages(c)) return true
        if (isAutoName(c)) {
            val label = label(c, pkg)
            if (matchesWords(label, pkg) != null) return true
        }
        return false
    }

    /** The word that made an app match, or null. Shown in the settings list. */
    fun matchReason(c: Context, pkg: String): String? {
        if (pkg in BUILT_IN) return "built-in list"
        if (pkg in userPackages(c)) return "added by you"
        if (isAutoHce(c) && pkg in tapToPayPackages(c)) return "tap-to-pay"
        if (isAutoName(c)) {
            val word = matchesWords(label(c, pkg), pkg)
            if (word != null) return "name contains \"$word\""
        }
        return null
    }

    /** Package substring first, then whole-word label match. */
    private fun matchesWords(label: String, pkg: String): String? {
        val l = label.lowercase(Locale.ROOT)
        val p = pkg.lowercase(Locale.ROOT)
        for (w in ALL_WORDS) if (p.contains(w)) return w
        for (w in ALL_WORDS) if (containsWord(l, w)) return w
        return null
    }

    private fun containsWord(text: String, word: String): Boolean {
        var from = 0
        while (true) {
            val i = text.indexOf(word, from)
            if (i < 0) return false
            val beforeOk = i == 0 || !text[i - 1].isLetterOrDigit()
            val end = i + word.length
            val afterOk = end >= text.length || !text[end].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            from = i + 1
        }
    }

    fun label(c: Context, pkg: String): String = runCatching {
        c.packageManager.getApplicationLabel(
            c.packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    }.getOrDefault(pkg)

    const val ACTION_HCE = "android.nfc.cardemulation.HOST_APDU_SERVICE"
}
