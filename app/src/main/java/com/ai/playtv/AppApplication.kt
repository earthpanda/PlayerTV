package com.ai.playtv

import android.app.Application
import com.ai.playtv.base.util.Foundation
import com.ai.playtv.base.diagnostics.StartupStep
import com.ai.playtv.di.AppContainer

/**
 * Application composition root; initializes the explicitly selected foundation toolkit.
 * Register new capabilities here only when their documented lifecycle requires it.
 */
class AppApplication : Application() {
    lateinit var container: AppContainer
        private set

    /** Initialize process-level toolkit context without any Sony SDK or service startup. */
    override fun onCreate() {
        super.onCreate()
        StartupStep.run("foundation") { Foundation.initialize(this) }
        container = StartupStep.run("container") { AppContainer(this) }
    }
}
