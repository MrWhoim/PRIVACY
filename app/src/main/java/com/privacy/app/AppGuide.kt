package com.privacy.app

import android.content.Context

/** Reads the one app guide (assets/app_guide.md) and hands out its `## sections`, for the About page and for the assistant. */
object AppGuide {
    @Volatile private var cache: String? = null
    private fun text(ctx: Context): String = cache ?: runCatching { ctx.assets.open("app_guide.md").bufferedReader().use { it.readText() } }.getOrDefault("").also { cache = it }

    /** The body of the section whose heading starts with [title] (without the heading line). */
    fun section(ctx: Context, title: String): String {
        val t = text(ctx)
        val m = Regex("(?m)^## ${Regex.escape(title)}.*$").find(t) ?: return ""
        val start = m.range.last + 1
        val end = Regex("(?m)^## ").find(t, start)?.range?.first ?: t.length
        return t.substring(start, end).trim()
    }
}
