package com.ai.playtv.base.ui

import android.content.res.Resources
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.ai.playtv.base.util.ScreenUnits

/**
 * Business-independent Activity with pt adaptation and edge-to-edge window setup.
 * Subclasses call applySystemBarInsets once on their content root after setContentView.
 */
open class BaseActivity : AppCompatActivity() {
    /** Keep XML pt units on the same configured coordinate system as code conversions. */
    override fun getResources(): Resources = ScreenUnits.adaptDesignPoints(super.getResources())

    /** Configure a light, custom-title window without account, payment or tracking hooks. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }

    /**
     * Add system-bar insets to initial padding, never cumulatively.
     * Child views do not consume these insets again; IME policies are feature-specific.
     */
    protected fun applySystemBarInsets(root: View) {
        SystemBarInsets.apply(root)
    }
}
