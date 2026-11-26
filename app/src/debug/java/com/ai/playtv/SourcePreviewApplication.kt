package com.ai.playtv

import com.ai.playtv.di.SourceDiscoveryOwner

/** Debug-only composition root; sessions are started explicitly by the entry screen. */
class SourcePreviewApplication : AppApplication() {
    val discoveryOwner = SourceDiscoveryOwner()
}
