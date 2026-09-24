package com.sky22333.netboot.download

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RemoteFileProbeTest {
    @Test
    fun probeUsesRangeAndReturnsMetadata() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("bytes=0-0", chain.request().header("Range"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("Partial Content")
                .header("Content-Range", "bytes 0-0/4096").header("ETag", "version-1").body("x".toResponseBody()).build()
        }.build()
        try {
            val result = RemoteFileProbe(client).probe("https://www.microsoft.com/image.iso")
            assertEquals(RemoteMetadata(4096, true, "version-1", null), result)
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun cancellingProbeCancelsPendingHttpCall() = runBlocking {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.countDown() }
        }).addInterceptor {
            entered.countDown()
            check(cancelled.await(5, TimeUnit.SECONDS))
            throw IOException("cancelled")
        }.build()
        try {
            val job = launch { RemoteFileProbe(client).probe("https://www.microsoft.com/image.iso") }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            withTimeout(1000) { job.cancelAndJoin() }
            assertEquals(0L, cancelled.count)
        } finally {
            cancelled.countDown()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
