package com.ai.playtv.di

import com.ai.playtv.model.manager.source.SourceDiscoverySession
import com.ai.playtv.model.network.service.source.GithubSourceService
import com.ai.playtv.model.repository.source.SourceRepository
import com.ai.playtv.model.storage.source.SourceHistoryStore
import com.ai.playtv.support.network.NetworkConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Holds at most one short-lived discovery session across the entry and player screens. */
class SourceDiscoveryOwner(private val history: SourceHistoryStore) {
    private var current: Pair<String, SourceDiscoverySession>? = null
    private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized fun start(input: String): Pair<String, SourceDiscoverySession> {
        current?.second?.close()
        val network = NetworkDependencies(sourceNetworkConfig())
        val session = try {
            SourceDiscoverySession(SourceRepository(network.service(GithubSourceService::class.java)), input, network::close)
        } catch (failure: Throwable) {
            network.close()
            throw failure
        }
        val owned = UUID.randomUUID().toString() to session
        current = owned
        history.begin(input, owned.first)
        historyScope.launch {
            try {
                session.state.first { snapshot ->
                    snapshot.result?.takeIf { it.items.isNotEmpty() }?.let { result ->
                        try {
                            history.save(input, owned.first, result, snapshot.error != null || snapshot.running || snapshot.cancelled)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // The store exposes failure to the entry screen; playback remains available.
                        }
                    }
                    !snapshot.running
                }
            } finally {
                history.end(input, owned.first)
            }
        }
        return owned
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
