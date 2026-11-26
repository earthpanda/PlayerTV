package com.ai.playtv.base.ui

import android.os.Looper
import android.view.View
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.Closeable

/** 单输入框的短寿命控制器；宿主销毁前 close，取消尚未执行的显示请求，不修改触摸监听器。 */
class KeyboardController(private val input: EditText) : Closeable {
    private var pending: Runnable? = null
    private var closed = false

    fun showAfterLayout(requestFocus: Boolean = true) {
        mainThread()
        check(!closed) { "KeyboardController closed" }
        cancelPending()
        pending = Runnable {
            pending = null
            if (closed || !input.isAttachedToWindow || !input.isShown || !input.isEnabled) return@Runnable
            if (requestFocus) input.requestFocus()
            if (input.hasFocus()) ViewCompat.getWindowInsetsController(input)?.show(WindowInsetsCompat.Type.ime())
        }.also { input.post(it) }
    }

    fun hide(clearFocus: Boolean = false) {
        mainThread()
        cancelPending()
        ViewCompat.getWindowInsetsController(input)?.hide(WindowInsetsCompat.Type.ime())
        if (clearFocus) input.clearFocus()
    }

    override fun close() { mainThread(); closed = true; cancelPending() }
    private fun cancelPending() { pending?.let(input::removeCallbacks); pending = null }
    private fun mainThread() = check(Looper.myLooper() == Looper.getMainLooper()) { "Main thread required" }
}
