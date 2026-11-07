package com.privacy.app.ai

import com.privacy.app.AppGraph

/**
 * A brand-new user must hear nothing until they have actually used the app: no morning brief, no water or meal nudges,
 * no assistant chat bubbles. "Used" = finished the name screen AND sent a chat message, or added a task / note / health entry / memory.
 */
object Engagement {
    suspend fun ok(g: AppGraph): Boolean = runCatching {
        if (!g.settings.profileDone) return false
        g.db.chatDao().all().any { it.role == "user" } || g.db.taskDao().all().isNotEmpty() ||
            g.db.noteDao().all().isNotEmpty() || g.db.healthDao().allEntries().isNotEmpty() || g.db.memoryDao().all().isNotEmpty()
    }.getOrDefault(false)
}
