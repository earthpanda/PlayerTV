package com.ai.playtv.di

import com.ai.playtv.model.manager.source.SourceDiscoverySession
import com.ai.playtv.model.network.service.source.GithubSourceService
import com.ai.playtv.model.repository.source.SourceRepository
import com.ai.playtv.support.network.NetworkConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.UUID

/** Holds at most one short-lived discovery session across the entry and player screens. */
class SourceDiscoveryOwner {
    private var current: Pair<String, SourceDiscoverySession>? = null

    @Synchronized fun start(input: String): Pair<String, SourceDiscoverySession> {
        current?.second?.close()
        val network = NetworkDependencies(sourceNetworkConfig())
        val session = try {
            SourceDiscoverySession(SourceRepository(network.service(GithubSourceService::class.java)), input, network::close)
        } catch (failure: Throwable) {
            network.close()
            throw failure
        }
        return (UUID.randomUUID().toString() to session).also { current = it }
    }

    @Synchronized fun find(id: String): SourceDiscoverySession? = current?.takeIf { it.first == id }?.second

    @Synchronized fun close(id: String) {
        val session = current?.takeIf { it.first == id } ?: return
        current = null
        session.second.close()
    }
}

fun sourceNetworkConfig() = NetworkConfig(
    "https://api.github.com/".toHttpUrl(),
    connectTimeoutSeconds = 20, readTimeoutSeconds = 45, callTimeoutSeconds = 90,
)
