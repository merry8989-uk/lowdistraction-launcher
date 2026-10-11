package com.lowdistraction.launcher

import android.content.Context

/**
 * The chime the assistive wheel plays as it spins. Sixteen are built in and the
 * user picks one from Settings.
 */
object WheelSound {

    data class Option(val id: Int, val name: String, val res: Int)

    val ALL: List<Option> = listOf(
        Option(0, "Soft chime", R.raw.wheel_chime),
        Option(1, "Bell", R.raw.wheel_sound_01),
        Option(2, "Marimba", R.raw.wheel_sound_02),
        Option(3, "Glass", R.raw.wheel_sound_03),
        Option(4, "Wood block", R.raw.wheel_sound_04),
        Option(5, "Pluck", R.raw.wheel_sound_05),
        Option(6, "Drop", R.raw.wheel_sound_06),
        Option(7, "Ping", R.raw.wheel_sound_07),
        Option(8, "Halo", R.raw.wheel_sound_08),
        Option(9, "Bubble", R.raw.wheel_sound_09),
        Option(10, "Kalimba", R.raw.wheel_sound_10),
        Option(11, "Vibraphone", R.raw.wheel_sound_11),
        Option(12, "Ceramic", R.raw.wheel_sound_12),
        Option(13, "Tine", R.raw.wheel_sound_13),
        Option(14, "Ripple", R.raw.wheel_sound_14),
        Option(15, "Night", R.raw.wheel_sound_15)
    )

    private const val PREFS = "wheel_sound"
    private const val KEY = "selected"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun selectedId(c: Context): Int = prefs(c).getInt(KEY, 0)

    fun setSelected(c: Context, id: Int) = prefs(c).edit().putInt(KEY, id).apply()

    fun selected(c: Context): Option = ALL.firstOrNull { it.id == selectedId(c) } ?: ALL.first()

    fun resource(c: Context): Int = selected(c).res
}
