package com.ai.playtv.model.contract.mvi

/** Marker for immutable screen snapshots, e.g. SampleState. */
interface UiState

/** Marker for inputs carrying business data, never View or Activity instances. */
interface UiEvent

/** Marker for transient results; consumers bind them to their current UI lifecycle. */
interface UiEffect
