package com.ai.playtv.base.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ai.playtv.base.R

/** 一个 View 的 Insets 责任方；使用首次 padding、不累加，编辑页可显式合并 IME 底边。 */
object SystemBarInsets {
    data class Edges(val left: Boolean = true, val top: Boolean = true,
        val right: Boolean = true, val bottom: Boolean = true)
    private data class Padding(val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun apply(view: View, edges: Edges = Edges(), includeIme: Boolean = false) {
        val initial = (view.getTag(R.id.foundation_insets_padding) as? Padding) ?: Padding(
            view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom).also {
            view.setTag(R.id.foundation_insets_padding, it)
        }
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val bottom = if (includeIme) maxOf(bars.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom) else bars.bottom
            target.setPadding(initial.left + if (edges.left) bars.left else 0,
                initial.top + if (edges.top) bars.top else 0,
                initial.right + if (edges.right) bars.right else 0,
                initial.bottom + if (edges.bottom) bottom else 0)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /** 仅由本工具的所有者调用；清监听并恢复首次 padding，不能覆盖其他控制器的监听。 */
    fun clear(view: View) {
        val initial = view.getTag(R.id.foundation_insets_padding) as? Padding ?: return
        ViewCompat.setOnApplyWindowInsetsListener(view, null)
        view.setPadding(initial.left, initial.top, initial.right, initial.bottom)
        view.setTag(R.id.foundation_insets_padding, null)
    }
}
