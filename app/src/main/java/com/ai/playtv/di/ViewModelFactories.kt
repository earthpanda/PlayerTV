package com.ai.playtv.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Explicit creation helper without a DI framework or business singleton.
 * Example: viewModelFactory(SampleViewModel::class.java) { SampleViewModel(repository) }.
 */
fun <VM : ViewModel> viewModelFactory(type: Class<VM>, create: () -> VM): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        /** Construct only the registered ViewModel type; reject accidental owner mismatches. */
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == type) { "Unregistered ViewModel: ${modelClass.name}" }
            return modelClass.cast(create())
        }
    }
