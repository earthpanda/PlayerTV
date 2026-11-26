package com.ai.playtv.base.ui

import androidx.fragment.app.Fragment

/**
 * Minimal platform Fragment base; no model state, singleton or app dependency.
 * Use app's BaseMviFragment when a feature needs State/Event/Effect collection.
 */
open class BaseFragment : Fragment()
