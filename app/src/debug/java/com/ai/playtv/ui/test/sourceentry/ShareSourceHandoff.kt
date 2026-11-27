package com.ai.playtv.ui.test.sourceentry

import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import java.util.UUID

/** At most one handoff, consumed only by its token and within its lifetime. */
internal class ShareSourceHandoffStore(private val now: () -> Long, private val token: () -> String) {
    private data class Pending(val token: String, val url: String, val createdAt: Long)
    private var pending: Pending? = null

    @Synchronized fun offer(url: String): String {
        val item = Pending(token(), url, now())
        pending = item
        return item.token
    }

    @Synchronized fun consume(token: String?): String? {
        val item = pending ?: return null
        if (token == null || token != item.token) return null
        pending = null
        val elapsed = now() - item.createdAt
        return item.url.takeIf { elapsed in 0 until MAX_AGE_MS }
    }

    @Synchronized fun discard(token: String) {
        if (pending?.token == token) pending = null
    }

    companion object { const val MAX_AGE_MS = 30_000L }
}

/** Expire even an unconsumed link; public entry intents carry only a single-use token. */
internal object ShareSourceHandoff {
    const val ACTION = "com.ai.playtv.action.IMPORT_SHARED_SOURCE"
    const val EXTRA_TOKEN = "com.ai.playtv.extra.SHARED_SOURCE_TOKEN"
    private val handler = Handler(Looper.getMainLooper())
    private val store = ShareSourceHandoffStore(SystemClock::elapsedRealtime) { UUID.randomUUID().toString() }
    private var expiry: Runnable? = null

    @Synchronized fun offer(url: String): String {
        expiry?.let { handler.removeCallbacks(it) }
        val token = store.offer(url)
        val task = Runnable { store.discard(token) }
        expiry = task
        handler.postDelayed(task, ShareSourceHandoffStore.MAX_AGE_MS)
        return token
    }

    @Synchronized fun consume(token: String?): String? {
        val url = store.consume(token)
        if (url != null) {
            expiry?.let { handler.removeCallbacks(it) }
            expiry = null
        }
        return url
    }
}
