package com.ai.playtv.base.util

import android.widget.TextView
import androidx.core.widget.TextViewCompat

/** Sony 的字号/行高基础操作；不读取语言设置或业务字号枚举。UI 主线程调用。 */
fun TextView.applyFontSizeAndLineHeight(fontSizePt: Float, lineHeightPt: Int) {
    require(fontSizePt > 0 && lineHeightPt > 0)
    setTextPtSize(fontSizePt)
    TextViewCompat.setLineHeight(this, lineHeightPt.pt)
}
