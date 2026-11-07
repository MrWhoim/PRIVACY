package com.privacy.app.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.privacy.app.R

/** Fonts that ship inside the app (all SIL Open Font License; licences are in assets/font-licenses). */
data class AppFont(val key: String, val label: String, val vibe: String, val family: FontFamily?)

object AppFonts {
    private fun fam(vararg f: Pair<Int, FontWeight>) = FontFamily(f.map { Font(it.first, it.second) })

    val all: List<AppFont> = listOf(
        AppFont("bricolage", "Bricolage Grotesque", "Warm, characterful, premium", fam(R.font.bricolage_regular to FontWeight.Normal, R.font.bricolage_regular to FontWeight.Medium, R.font.bricolage_bold to FontWeight.SemiBold, R.font.bricolage_bold to FontWeight.Bold)),
        AppFont("outfit", "Outfit", "Clean, geometric, modern", fam(R.font.outfit_regular to FontWeight.Normal, R.font.outfit_regular to FontWeight.Medium, R.font.outfit_bold to FontWeight.SemiBold, R.font.outfit_bold to FontWeight.Bold)),
        AppFont("instrument", "Instrument Sans", "Calm, editorial, readable", fam(R.font.instrument_regular to FontWeight.Normal, R.font.instrument_regular to FontWeight.Medium, R.font.instrument_bold to FontWeight.SemiBold, R.font.instrument_bold to FontWeight.Bold)),
        AppFont("tektur", "Tektur", "Futuristic, sci-fi interface", fam(R.font.tektur_regular to FontWeight.Normal, R.font.tektur_medium to FontWeight.Medium, R.font.tektur_medium to FontWeight.SemiBold, R.font.tektur_medium to FontWeight.Bold)),
        AppFont("jura", "Jura", "Soft, techy, glowing-glass feel", fam(R.font.jura_light to FontWeight.Normal, R.font.jura_medium to FontWeight.Medium, R.font.jura_medium to FontWeight.SemiBold, R.font.jura_medium to FontWeight.Bold)),
        AppFont("pixelify", "Pixelify Sans", "Pixel / dot-matrix, matches the assistant", fam(R.font.pixelify_medium to FontWeight.Normal, R.font.pixelify_medium to FontWeight.Medium, R.font.pixelify_medium to FontWeight.SemiBold, R.font.pixelify_medium to FontWeight.Bold)),
        AppFont("jetbrains", "JetBrains Mono", "Developer terminal", fam(R.font.jetbrains_regular to FontWeight.Normal, R.font.jetbrains_regular to FontWeight.Medium, R.font.jetbrains_bold to FontWeight.SemiBold, R.font.jetbrains_bold to FontWeight.Bold)),
        AppFont("plexmono", "IBM Plex Mono", "Technical, typewriter-like", fam(R.font.plexmono_regular to FontWeight.Normal, R.font.plexmono_regular to FontWeight.Medium, R.font.plexmono_bold to FontWeight.SemiBold, R.font.plexmono_bold to FontWeight.Bold)),
        AppFont("system", "System default", "Your phone's own font", null)
    )

    const val DEFAULT = "bricolage"
    fun of(key: String): AppFont = all.firstOrNull { it.key == key } ?: all.first()
}
