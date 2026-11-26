package com.ai.playtv.di

import com.ai.playtv.support.network.NetworkConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.http.GET
import kotlin.concurrent.thread

class NetworkDependenciesTest {
    interface ExampleService { @GET("resource") suspend fun get(): String }

    @Test fun closeIsIdempotentAndRejectsNewServicesWhileCleanupCompletes() = runBlocking {
        val dependencies = NetworkDependencies(NetworkConfig("https://example.org/".toHttpUrl()))
        assertNotNull(dependencies.service(ExampleService::class.java))
        val calls = List(8) { thread { dependencies.close() } }
        calls.forEach { it.join() }
        assertThrows(IllegalStateException::class.java) { dependencies.service(ExampleService::class.java) }
        withTimeout(5_000) { dependencies.awaitClosed() }
        dependencies.close()
        withTimeout(5_000) { dependencies.awaitClosed() }
    }
}
