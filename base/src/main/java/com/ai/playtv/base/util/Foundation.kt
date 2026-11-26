package com.ai.playtv.base.util

import android.app.Application
import com.blankj.utilcode.util.Utils

/**
 * Process-level foundation initialization without exposing the chosen toolkit type.
 * Application calls Foundation.initialize(this) once in onCreate.
 */
object Foundation {
    /** Register application context for shared platform utilities; never retain an Activity. */
    fun initialize(application: Application) {
        Utils.init(application)
    }
}
