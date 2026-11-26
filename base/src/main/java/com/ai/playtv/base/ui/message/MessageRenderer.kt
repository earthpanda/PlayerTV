package com.ai.playtv.base.ui.message

import android.widget.Toast
import androidx.fragment.app.FragmentActivity

/** Main-thread dismissal must also remove any custom View, listener and delayed callback. */
fun interface MessageHandle {
    fun dismiss()
}

/** A renderer owns one visible message until its handle is dismissed; no global host lookup. */
fun interface MessageRenderer {
    fun show(host: FragmentActivity, message: CharSequence): MessageHandle
}

/** Neutral platform implementation; does not promise custom success/failure skins. */
object PlatformToastRenderer : MessageRenderer {
    override fun show(host: FragmentActivity, message: CharSequence): MessageHandle {
        val toast = Toast.makeText(host, message, Toast.LENGTH_SHORT)
        toast.show()
        return MessageHandle { toast.cancel() }
    }
}
