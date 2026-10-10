package com.lowdistraction.launcher

import android.content.Context
import android.graphics.Color

/** How each row in the app list is drawn. */
enum class DisplayMode(val id: Int) {
    NAME(0), ICON(1), ICON_NAME(2);

    companion object {
        fun from(id: Int): DisplayMode = entries.firstOrNull { it.id == id } ?: NAME
    }
}

/** The mask applied to an app icon. */
enum class IconShape(val id: Int) {
    ORIGINAL(0), CIRCLE(1), ROUNDED(2), SQUARE(3);

    companion object {
        fun from(id: Int): IconShape = entries.firstOrNull { it.id == id } ?: ORIGINAL
    }
}

/** One complete colour scheme. */
data class Theme(
    val id: String,
    val name: String,
    val light: Boolean,
    val bg: Int,
    val surface: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val accent: Int,
    val glowHue: Float,
    val glowStrength: Float
)

/**
 * Everything the home screen's look is made of: the display mode, the icon and
 * text sizing, and the colour theme. 60+ themes ship in the box and the user can
 * also build their own from four colours.
 */
object Look {

    private const val PREFS = "look_prefs"
    private const val KEY_MODE = "display_mode"
    private const val KEY_ICON_SIZE = "icon_size"
    private const val KEY_ICON_SHAPE = "icon_shape"
    private const val KEY_ICON_OUTLINE = "icon_outline"
    private const val KEY_NAME_SIZE = "name_size"
    private const val KEY_NAME_BOLD = "name_bold"
    private const val KEY_NAME_SPACING = "name_spacing"
    private const val KEY_THEME = "theme_id"
    private const val KEY_GLOW = "glow"
    private const val KEY_CUSTOM_BG = "custom_bg"
    private const val KEY_CUSTOM_TEXT = "custom_text"
    private const val KEY_CUSTOM_TEXT2 = "custom_text2"
    private const val KEY_CUSTOM_ACCENT = "custom_accent"
    private const val KEY_CUSTOM_LIGHT = "custom_light"

    const val CUSTOM_ID = "custom"
    const val DEFAULT_THEME_ID = "mint"

    // ------------------------------------------------------------ prefs access
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun displayMode(c: Context): DisplayMode =
        DisplayMode.from(prefs(c).getInt(KEY_MODE, DisplayMode.NAME.id))

    fun setDisplayMode(c: Context, mode: DisplayMode) =
        prefs(c).edit().putInt(KEY_MODE, mode.id).apply()

    /** Icon edge length in dp. */
    fun iconSize(c: Context): Int = prefs(c).getInt(KEY_ICON_SIZE, 44).coerceIn(MIN_ICON, MAX_ICON)

    fun setIconSize(c: Context, dp: Int) =
        prefs(c).edit().putInt(KEY_ICON_SIZE, dp.coerceIn(MIN_ICON, MAX_ICON)).apply()

    fun iconShape(c: Context): IconShape =
        IconShape.from(prefs(c).getInt(KEY_ICON_SHAPE, IconShape.ROUNDED.id))

    fun setIconShape(c: Context, shape: IconShape) =
        prefs(c).edit().putInt(KEY_ICON_SHAPE, shape.id).apply()

    /** Outline thickness in dp; 0 means no outline. */
    fun iconOutline(c: Context): Int =
        prefs(c).getInt(KEY_ICON_OUTLINE, 0).coerceIn(0, MAX_OUTLINE)

    fun setIconOutline(c: Context, dp: Int) =
        prefs(c).edit().putInt(KEY_ICON_OUTLINE, dp.coerceIn(0, MAX_OUTLINE)).apply()

    fun nameSize(c: Context): Int = prefs(c).getInt(KEY_NAME_SIZE, 18).coerceIn(MIN_NAME, MAX_NAME)

    fun setNameSize(c: Context, sp: Int) =
        prefs(c).edit().putInt(KEY_NAME_SIZE, sp.coerceIn(MIN_NAME, MAX_NAME)).apply()

    fun nameBold(c: Context): Boolean = prefs(c).getBoolean(KEY_NAME_BOLD, false)

    fun setNameBold(c: Context, bold: Boolean) =
        prefs(c).edit().putBoolean(KEY_NAME_BOLD, bold).apply()

    /** Extra letter spacing, in hundredths of an em (0–20 → 0.00–0.20 em). */
    fun nameSpacing(c: Context): Int = prefs(c).getInt(KEY_NAME_SPACING, 0).coerceIn(0, 20)

    fun setNameSpacing(c: Context, value: Int) =
        prefs(c).edit().putInt(KEY_NAME_SPACING, value.coerceIn(0, 20)).apply()

    fun glowEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_GLOW, true)

    fun setGlowEnabled(c: Context, on: Boolean) =
        prefs(c).edit().putBoolean(KEY_GLOW, on).apply()

    fun themeId(c: Context): String = prefs(c).getString(KEY_THEME, DEFAULT_THEME_ID) ?: DEFAULT_THEME_ID

    fun setThemeId(c: Context, id: String) = prefs(c).edit().putString(KEY_THEME, id).apply()

    fun theme(c: Context): Theme {
        val id = themeId(c)
        if (id == CUSTOM_ID) return customTheme(c)
        return ALL_THEMES.firstOrNull { it.id == id } ?: ALL_THEMES.first()
    }

    fun customTheme(c: Context): Theme {
        val p = prefs(c)
        val bg = p.getInt(KEY_CUSTOM_BG, 0xFF101318.toInt())
        val text = p.getInt(KEY_CUSTOM_TEXT, 0xFFEDEDED.toInt())
        val text2 = p.getInt(KEY_CUSTOM_TEXT2, 0xFF8A8A8E.toInt())
        val accent = p.getInt(KEY_CUSTOM_ACCENT, 0xFF9AE6B4.toInt())
        val light = p.getBoolean(KEY_CUSTOM_LIGHT, false)
        return Theme(
            id = CUSTOM_ID,
            name = "Custom",
            light = light,
            bg = bg,
            surface = if (light) blend(bg, text, 0.10f) else blend(bg, text, 0.07f),
            textPrimary = text,
            textSecondary = text2,
            accent = accent,
            glowHue = hueOf(accent),
            glowStrength = 1f
        )
    }

    fun setCustomColours(c: Context, bg: Int, text: Int, text2: Int, accent: Int) {
        prefs(c).edit()
            .putInt(KEY_CUSTOM_BG, bg)
            .putInt(KEY_CUSTOM_TEXT, text)
            .putInt(KEY_CUSTOM_TEXT2, text2)
            .putInt(KEY_CUSTOM_ACCENT, accent)
            .putBoolean(KEY_CUSTOM_LIGHT, luminance(bg) > 0.55f)
            .apply()
    }

    fun reset(c: Context) {
        prefs(c).edit().clear().apply()
    }

    // ------------------------------------------------------------- colour maths
    fun hsv(hue: Float, sat: Float, value: Float): Int =
        Color.HSVToColor(floatArrayOf(((hue % 360f) + 360f) % 360f, sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f)))

    fun blend(a: Int, b: Int, f: Float): Int = Color.rgb(
        (Color.red(a) * (1 - f) + Color.red(b) * f).toInt().coerceIn(0, 255),
        (Color.green(a) * (1 - f) + Color.green(b) * f).toInt().coerceIn(0, 255),
        (Color.blue(a) * (1 - f) + Color.blue(b) * f).toInt().coerceIn(0, 255)
    )

    fun luminance(color: Int): Float =
        (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f

    fun hueOf(color: Int): Float {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        return hsv[0]
    }

    /** The colour the drifting glow takes for this theme at the given hue. */
    fun glowColor(theme: Theme, hue: Float): Int =
        if (theme.light) hsv(hue, 0.34f, 1f) else hsv(hue, 0.52f, 0.30f)

    // ------------------------------------------------------------------ themes
    private data class Spec(
        val id: String,
        val name: String,
        val bgHue: Float,
        val glowHue: Float,
        val accentHue: Float
    )

    private fun dark(s: Spec): Theme {
        val bg = hsv(s.bgHue, 0.15f, 0.055f)
        return Theme(
            id = s.id,
            name = s.name,
            light = false,
            bg = bg,
            surface = hsv(s.bgHue, 0.13f, 0.105f),
            textPrimary = hsv(s.bgHue, 0.06f, 0.93f),
            textSecondary = hsv(s.bgHue, 0.11f, 0.56f),
            accent = hsv(s.accentHue, 0.45f, 0.88f),
            glowHue = s.glowHue,
            glowStrength = 1f
        )
    }

    private fun light(s: Spec): Theme {
        val bg = hsv(s.bgHue, 0.055f, 0.975f)
        return Theme(
            id = s.id,
            name = s.name,
            light = true,
            bg = bg,
            surface = hsv(s.bgHue, 0.11f, 0.90f),
            textPrimary = hsv(s.bgHue, 0.38f, 0.13f),
            textSecondary = hsv(s.bgHue, 0.18f, 0.45f),
            accent = hsv(s.accentHue, 0.68f, 0.40f),
            glowHue = s.glowHue,
            glowStrength = 1f
        )
    }

    /** The original grey-on-mint scheme, kept so nothing has to change. */
    private val GRAPHITE = Theme(
        id = "graphite",
        name = "Graphite",
        light = false,
        bg = 0xFF0B0B0C.toInt(),
        surface = 0xFF17181A.toInt(),
        textPrimary = 0xFFEDEDED.toInt(),
        textSecondary = 0xFF8A8A8E.toInt(),
        accent = 0xFF9AE6B4.toInt(),
        glowHue = 150f,
        glowStrength = 1f
    )

    private val DARK_SPECS = listOf(
        Spec("mint", "Mint", 155f, 155f, 150f),
        Spec("midnight", "Midnight", 225f, 225f, 205f),
        Spec("ink", "Ink", 220f, 220f, 230f),
        Spec("obsidian", "Obsidian", 265f, 265f, 270f),
        Spec("slate", "Slate", 210f, 210f, 200f),
        Spec("charcoal", "Charcoal", 220f, 220f, 150f),
        Spec("forest", "Forest", 145f, 145f, 140f),
        Spec("moss", "Moss", 95f, 95f, 90f),
        Spec("olive", "Olive", 70f, 70f, 65f),
        Spec("teal", "Teal", 178f, 178f, 172f),
        Spec("cyan", "Cyan", 190f, 190f, 185f),
        Spec("ocean", "Ocean", 202f, 202f, 196f),
        Spec("steel", "Steel", 208f, 208f, 215f),
        Spec("twilight", "Twilight", 250f, 250f, 242f),
        Spec("nebula", "Nebula", 285f, 285f, 275f),
        Spec("grape", "Grape", 278f, 278f, 270f),
        Spec("violet", "Violet", 268f, 268f, 260f),
        Spec("orchid", "Orchid", 302f, 302f, 296f),
        Spec("plum", "Plum", 322f, 322f, 315f),
        Spec("rose", "Rose", 342f, 342f, 336f),
        Spec("crimson", "Crimson", 356f, 356f, 350f),
        Spec("ember", "Ember", 16f, 16f, 10f),
        Spec("amber", "Amber", 36f, 36f, 30f),
        Spec("gold", "Gold", 46f, 46f, 40f),
        Spec("sand", "Sand", 40f, 40f, 34f),
        Spec("copper", "Copper", 26f, 26f, 20f),
        Spec("espresso", "Espresso", 22f, 22f, 16f),
        Spec("wine", "Wine", 346f, 346f, 340f),
        Spec("rust", "Rust", 14f, 14f, 8f),
        Spec("aurora", "Aurora", 168f, 168f, 200f),
        Spec("neon", "Neon", 310f, 310f, 90f)
    )

    private val LIGHT_SPECS = listOf(
        Spec("paper", "Paper", 212f, 212f, 216f),
        Spec("snow", "Snow", 220f, 220f, 226f),
        Spec("cloud", "Cloud", 210f, 210f, 214f),
        Spec("fog", "Fog", 200f, 200f, 204f),
        Spec("linen", "Linen", 42f, 42f, 34f),
        Spec("ivory", "Ivory", 46f, 46f, 38f),
        Spec("cream", "Cream", 46f, 46f, 34f),
        Spec("butter", "Butter", 52f, 52f, 44f),
        Spec("lemon", "Lemon", 60f, 60f, 54f),
        Spec("dune", "Dune", 40f, 40f, 28f),
        Spec("peach", "Peach", 22f, 22f, 14f),
        Spec("coral", "Coral", 12f, 12f, 4f),
        Spec("blossom", "Blossom", 342f, 342f, 334f),
        Spec("rosewater", "Rosewater", 350f, 350f, 344f),
        Spec("lilac", "Lilac", 282f, 282f, 274f),
        Spec("lavender", "Lavender", 266f, 266f, 258f),
        Spec("spearmint", "Spearmint", 152f, 152f, 146f),
        Spec("sage", "Sage", 122f, 122f, 116f),
        Spec("sky", "Sky", 202f, 202f, 196f),
        Spec("ice", "Ice", 190f, 190f, 184f),
        Spec("aqua", "Aqua", 176f, 176f, 170f),
        Spec("seafoam", "Seafoam", 166f, 166f, 160f),
        Spec("meadow", "Meadow", 102f, 102f, 96f),
        Spec("oliveleaf", "Olive Leaf", 72f, 72f, 66f),
        Spec("clay", "Clay", 26f, 26f, 18f),
        Spec("terracotta", "Terracotta", 18f, 18f, 10f),
        Spec("blush", "Blush", 346f, 346f, 340f),
        Spec("powder", "Powder", 216f, 216f, 210f),
        Spec("porcelain", "Porcelain", 220f, 220f, 230f),
        Spec("alabaster", "Alabaster", 40f, 40f, 220f)
    )

    val ALL_THEMES: List<Theme> by lazy {
        buildList {
            add(GRAPHITE)
            DARK_SPECS.forEach { add(dark(it)) }
            LIGHT_SPECS.forEach { add(light(it)) }
        }
    }

    const val MIN_ICON = 24
    const val MAX_ICON = 96
    const val MAX_OUTLINE = 4
    const val MIN_NAME = 11
    const val MAX_NAME = 34
}
