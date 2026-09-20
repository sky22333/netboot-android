package com.sky22333.netboot.download

import com.sky22333.netboot.data.MicrosoftHosts
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Call
import okhttp3.Callback
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException

/** Range support controls parallelism; length and validators determine resumability. */
internal data class RemoteMetadata(
    val totalBytes: Long,
    val rangeSupported: Boolean,
    val etag: String?,
    val lastModified: String?,
)

/** Checks download metadata and Range support without fetching the full ISO. */
internal class RemoteFileProbe(private val client: OkHttpClient) {

    /** Cancels both the request and a blocked response read when a transfer is paused. */
    suspend fun <T> transfer(request: Request, redirects: Int = 0, consume: suspend (Response) -> T): T = coroutineScope {
        if (redirects > MaxRedirects) throw DownloadException("too_many_redirects")
        MicrosoftHosts.requireOfficial(request.url.toString())
        val call = client.newCall(request)
        val response = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            })
        }
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            response.use {
                if (it.code in 300..399) {
                    val target = it.header("Location")?.let(request.url::resolve)
                        ?: throw DownloadException("redirect_without_location")
                    it.close()
                    transfer(request.newBuilder().url(target).build(), redirects + 1, consume)
                } else consume(it)
            }
        } finally {
            cancellation.cancel()
        }
    }

    fun probe(url: String): RemoteMetadata {
        val response = execute(
            Request.Builder().url(url).header("Range", "bytes=0-0").header("Accept-Encoding", "identity").build(),
        )
        response.use {
            if (it.code == 403) throw HttpStatusException(403)
            if (it.code == 206) {
                val total = parseContentRangeTotal(it.header("Content-Range"))
                return RemoteMetadata(total, true, it.header("ETag"), it.header("Last-Modified"))
            }
            if (it.code == 200) {
                val total = it.body.contentLength()
                if (total <= 0) throw DownloadException("unknown_file_size")
                return RemoteMetadata(total, false, it.header("ETag"), it.header("Last-Modified"))
            }
            throw HttpStatusException(it.code)
        }
    }

    /** Validate every redirect against the Microsoft host allow-list. */
    internal fun execute(request: Request, redirects: Int = 0): Response {
        if (redirects > MaxRedirects) throw DownloadException("too_many_redirects")
        MicrosoftHosts.requireOfficial(request.url.toString())
        val response = client.newCall(request).execute()
        if (response.code !in 300..399) return response
        val location = response.header("Location")
        response.close()
        if (location == null) throw DownloadException("redirect_without_location")
        val target = request.url.resolve(location)?.toString() ?: throw DownloadException("invalid_redirect")
        MicrosoftHosts.requireOfficial(target)
        return execute(request.newBuilder().url(target).build(), redirects + 1)
    }

    companion object {
        const val MaxRedirects = 5

        fun validateTransfer(response: Response, start: Long, end: Long, metadata: RemoteMetadata) {
            if (response.header("Content-Encoding")?.let { it != "identity" } == true) throw DownloadException("range_mismatch")
            if (metadata.rangeSupported && response.header("Content-Range") != "bytes $start-$end/${metadata.totalBytes}") {
                throw DownloadException("range_mismatch")
            }
            val length = response.body.contentLength()
            if (length >= 0 && length != end - start + 1) throw DownloadException("range_mismatch")
            if (metadata.etag != null && response.header("ETag")?.let { it != metadata.etag } == true ||
                metadata.etag == null && metadata.lastModified != null && response.header("Last-Modified")?.let { it != metadata.lastModified } == true) {
                throw DownloadException("remote_file_changed")
            }
        }

        fun parseContentRangeTotal(value: String?): Long {
            val total = value?.substringAfter('/')?.toLongOrNull()
            if (total == null || total <= 0) throw DownloadException("invalid_content_range")
            return total
        }

    }
}

class DownloadException(val code: String) : IOException(code)
class HttpStatusException(val status: Int) : IOException("HTTP $status")
