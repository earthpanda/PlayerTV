package com.ai.playtv.ui.test.sourceentry

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.ai.playtv.model.repository.source.SourceRules

/** Receives a user-selected source from the TV launcher, without a public URL navigation route. */
class ShareSourceImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { finish(); return }
        val source = try {
            if (callingPackage != "com.gentv.tv" || intent?.action != ShareSourceHandoff.ACTION) null
            else intent.getStringExtra(Intent.EXTRA_TEXT)?.let { SourceRules.checkedUrl(it).toString() }
        } catch (_: RuntimeException) { null } catch (_: java.net.URISyntaxException) { null }
        if (source != null) {
            val token = ShareSourceHandoff.offer(source)
            try {
                startActivity(Intent(this, SourceEntryActivity::class.java)
                    .setAction(ShareSourceHandoff.ACTION)
                    .putExtra(ShareSourceHandoff.EXTRA_TOKEN, token)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                setResult(RESULT_OK)
            } catch (_: RuntimeException) {
                ShareSourceHandoff.consume(token)
                setResult(RESULT_CANCELED)
            }
        } else setResult(RESULT_CANCELED)
        finish()
    }
}
