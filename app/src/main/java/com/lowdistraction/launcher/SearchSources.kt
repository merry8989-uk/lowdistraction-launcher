package com.lowdistraction.launcher

import android.content.Context

/**
 * Which sources the second search box is allowed to look through.
 *
 * Everything starts **off**, so a fresh install shows only the app search box.
 * Turning on at least one source makes the second box appear.
 */
object SearchSources {

    private const val PREFS = "search_sources"
    private const val KEY_SETTINGS = "settings"
    private const val KEY_CONTACTS = "contacts"
    private const val KEY_CALENDAR = "calendar"
    private const val KEY_FILES = "files"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun settings(c: Context): Boolean = prefs(c).getBoolean(KEY_SETTINGS, false)

    fun setSettings(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_SETTINGS, value).apply()

    fun contacts(c: Context): Boolean = prefs(c).getBoolean(KEY_CONTACTS, false)

    fun setContacts(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_CONTACTS, value).apply()

    fun calendar(c: Context): Boolean = prefs(c).getBoolean(KEY_CALENDAR, false)

    fun setCalendar(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_CALENDAR, value).apply()

    fun files(c: Context): Boolean = prefs(c).getBoolean(KEY_FILES, false)

    fun setFiles(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_FILES, value).apply()

    /** Dev.Check keeps its own switch but counts as a source too. */
    fun devCheck(c: Context): Boolean = ShortcutPrefs.isDevCheckEnabled(c)

    /** True when at least one source is on, i.e. the second box should exist. */
    fun anyEnabled(c: Context): Boolean =
        settings(c) || contacts(c) || calendar(c) || files(c) || devCheck(c)

    /** Placeholder text built from whatever is currently on. */
    fun hint(c: Context): String {
        val parts = ArrayList<String>()
        if (settings(c)) parts.add(c.getString(R.string.source_short_settings))
        if (contacts(c)) parts.add(c.getString(R.string.source_short_contacts))
        if (calendar(c)) parts.add(c.getString(R.string.source_short_calendar))
        if (files(c)) parts.add(c.getString(R.string.source_short_files))
        if (devCheck(c)) parts.add(c.getString(R.string.source_short_devcheck))
        return if (parts.isEmpty()) "" else parts.joinToString(", ") + "…"
    }
}
