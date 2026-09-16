package com.sky22333.netboot.download

import com.sky22333.netboot.data.AppDatabase
import com.sky22333.netboot.data.DownloadSegmentEntity
import com.sky22333.netboot.data.DownloadState
import com.sky22333.netboot.data.DownloadTaskEntity
import com.sky22333.netboot.data.IsoAssetEntity
import com.sky22333.netboot.data.IsoRepository
import com.sky22333.netboot.data.IsoRequest
import com.sky22333.netboot.data.IsoState
import com.sky22333.netboot.data.MicrosoftHosts
import com.sky22333.netboot.data.MicrosoftIsoCatalog
import com.sky22333.netboot.data.TemporaryIsoLink
import com.sky22333.netboot.data.WindowsVersion
import com.sky22333.netboot.data.UsbMediaLayout
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import okhttp3.OkHttpClient
import okhttp3.Request

data class DownloadProgress(
    val taskId: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
)

@Singleton
class DownloadRepository @Inject constructor(
    database: AppDatabase,
    private val isoRepository: IsoRepository,
    private val catalog: MicrosoftIsoCatalog,
    client: OkHttpClient,
) {
    private val isoDao = database.isoDao()
    private val downloadDao = database.downloadDao()
    private val createMutex = Mutex()
    private val remoteProbe = RemoteFileProbe(client)

    fun observeTasks() = downloadDao.observeAll().map { tasks ->
        tasks.filter { it.state != DownloadState.Completed && it.state != DownloadState.Cancelled }
    }

    suspend fun create(request: IsoRequest, connections: Int): String = createMutex.withLock { withContext(Dispatchers.IO) {
        require(connections in setOf(1, 2, 4, 8))
        downloadDao.findActive(request.version.product, request.version.name, request.language.name, request.architecture.name)
            ?.let { return@withContext it.id }
        val link = catalog.resolve(request)
        val id = UUID.randomUUID().toString()
        val fileName = buildFileName(request, id)
        val destination = File(isoRepository.managedDirectory(), fileName)
        isoRepository.managedDirectory().mkdirs()
        isoDao.upsert(
            IsoAssetEntity(
                id = id,
                product = request.version.product,
                edition = request.version.name,
                language = request.language.name,
                architecture = request.architecture.name,
                source = "microsoft",
                fileName = fileName,
                filePath = destination.absolutePath,
                fileSize = 0,
                sha256 = "",
                createdAt = System.currentTimeMillis(),
                state = IsoState.Downloading,
            ),
        )
        downloadDao.upsertTask(
            DownloadTaskEntity(
                id = id,
                isoAssetId = id,
                temporaryUrl = link.url,
                expiresAt = link.expiresAt,
                etag = null,
                lastModified = null,
                totalBytes = 0,
                downloadedBytes = 0,
                connectionCount = connections,
                state = DownloadState.Queued,
                errorCode = null,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        id
    } }

    suspend fun run(taskId: String, onProgress: (DownloadProgress) -> Unit = {}) {
        val initialTask = downloadDao.findTask(taskId) ?: throw DownloadException("task_not_found")
        val asset = isoDao.find(initialTask.isoAssetId) ?: throw DownloadException("asset_not_found")
        if (initialTask.state == DownloadState.Completed) return
        try {
            var link = currentLink(initialTask, asset)
            var refreshed = false
            var forceSingle = false
            while (true) {
                try {
                    downloadWithLink(initialTask.id, asset, link, onProgress, forceSingle)
                    return
                } catch (error: HttpStatusException) {
                    when {
                        error.status == 200 && !forceSingle -> forceSingle = true
                        error.status == 403 && !refreshed && asset.source == "microsoft" -> {
                            link = refreshLink(asset)
                            refreshed = true
                        }
                        else -> throw error
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { markPaused(taskId) }
            throw cancelled
        } catch (error: Exception) {
                val current = downloadDao.findTask(taskId)
                downloadDao.updateProgress(
                    taskId,
                    current?.downloadedBytes ?: 0,
                    DownloadState.Failed,
                    errorCode(error),
                    System.currentTimeMillis(),
                )
                isoDao.setState(asset.id, IsoState.Failed)
                throw error
        }
    }

    suspend fun markPaused(taskId: String) {
        val task = downloadDao.findTask(taskId) ?: return
        if (task.state == DownloadState.Completed || task.state == DownloadState.Cancelled) return
        downloadDao.updateProgress(taskId, task.downloadedBytes, DownloadState.Paused, null, System.currentTimeMillis())
    }

    suspend fun cancel(taskId: String, deletePartial: Boolean) = withContext(Dispatchers.IO) {
        if (!deletePartial) { markPaused(taskId); return@withContext }
        val task = downloadDao.findTask(taskId) ?: return@withContext
        val asset = isoDao.find(task.isoAssetId)
        if (asset != null) {
            val partial = File(asset.filePath + PartialSuffix)
            if (partial.exists() && !partial.delete()) throw DownloadException("delete_failed")
        }
        downloadDao.updateProgress(taskId, task.downloadedBytes, DownloadState.Cancelled, null, System.currentTimeMillis())
        isoDao.setState(task.isoAssetId, IsoState.Failed)
    }

    private suspend fun currentLink(task: DownloadTaskEntity, asset: IsoAssetEntity): TemporaryIsoLink {
        if (task.temporaryUrl.isNotBlank() && (task.expiresAt ?: Long.MAX_VALUE) > System.currentTimeMillis() + 60_000) {
            return TemporaryIsoLink(task.temporaryUrl, task.expiresAt ?: Long.MAX_VALUE)
        }
        return refreshLink(asset)
    }

    private suspend fun refreshLink(asset: IsoAssetEntity): TemporaryIsoLink {
        val request = asset.toIsoRequest()
        return catalog.resolve(request)
    }

    private suspend fun downloadWithLink(
        taskId: String,
        asset: IsoAssetEntity,
        link: TemporaryIsoLink,
        onProgress: (DownloadProgress) -> Unit,
        forceSingle: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        MicrosoftHosts.requireOfficial(link.url)
        val probed = remoteProbe.probe(link.url)
        val metadata = if (forceSingle) probed.copy(rangeSupported = false) else probed
        val previous = downloadDao.findTask(taskId) ?: throw DownloadException("task_not_found")
        val partial = File(asset.filePath + PartialSuffix)
        require(isoRepository.availableBytes() + partial.length() > metadata.totalBytes) {
            "insufficient_storage"
        }
        val identityMatches = previous.totalBytes == metadata.totalBytes &&
            if (previous.etag != null) previous.etag == metadata.etag else
                previous.lastModified != null && previous.lastModified == metadata.lastModified
        if (previous.downloadedBytes > 0 && partial.exists() && !identityMatches) throw DownloadException("remote_file_changed")
        val canResume = metadata.rangeSupported && identityMatches &&
            previous.errorCode != "media_invalid_iso" &&
            previous.totalBytes > 0 &&
            partial.exists()
        val connectionCount = if (metadata.rangeSupported) previous.connectionCount else 1
        val storedSegments = if (canResume) downloadDao.segments(taskId) else emptyList()
        val segments = if (canResume) {
            storedSegments.takeIf { validSegments(it, metadata.totalBytes) }
                ?: newSegments(taskId, metadata.totalBytes, connectionCount)
        } else {
            newSegments(taskId, metadata.totalBytes, connectionCount)
        }
        if (!canResume) {
            RandomAccessFile(partial, "rw").use { it.setLength(metadata.totalBytes) }
            downloadDao.replaceSegments(taskId, segments)
        } else if (!validSegments(storedSegments, metadata.totalBytes)) {
            downloadDao.replaceSegments(taskId, segments)
        }
        downloadDao.prepare(
            taskId = taskId,
            url = link.url,
            expiresAt = link.expiresAt,
            etag = metadata.etag,
            lastModified = metadata.lastModified,
            totalBytes = metadata.totalBytes,
            connections = connectionCount,
            state = DownloadState.Running,
            updatedAt = System.currentTimeMillis(),
        )
        isoDao.setState(asset.id, IsoState.Downloading)
        val progress = segments.associate { it.segmentIndex to AtomicLong(it.currentByte) }
        coroutineScope {
            val reporter = launch {
                while (true) {
                    val downloaded = downloadedBytes(segments, progress)
                    downloadDao.updateProgress(taskId, downloaded, DownloadState.Running, null, System.currentTimeMillis())
                    onProgress(DownloadProgress(taskId, downloaded, metadata.totalBytes))
                    delay(2000)
                }
            }
            try {
                segments.map { segment ->
                    async {
                        downloadSegment(link.url, partial, segment, metadata, progress.getValue(segment.segmentIndex))
                    }
                }.awaitAll()
            } finally {
                reporter.cancel()
            }
        }
        val downloaded = downloadedBytes(segments, progress)
        downloadDao.updateProgress(taskId, downloaded, DownloadState.Verifying, null, System.currentTimeMillis())
        isoDao.setState(asset.id, IsoState.Verifying)
        if (partial.length() != metadata.totalBytes || downloaded != metadata.totalBytes) {
            throw DownloadException("size_mismatch")
        }
        try { UsbMediaLayout.requireIso(partial) } catch (error: IOException) {
            throw DownloadException("media_invalid_iso")
        }
        val hash = IsoRepository.sha256(partial)
        val destination = File(asset.filePath)
        Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        isoDao.complete(asset.id, IsoState.Ready, destination.length(), hash)
        downloadDao.updateProgress(taskId, downloaded, DownloadState.Completed, null, System.currentTimeMillis())
        onProgress(DownloadProgress(taskId, downloaded, metadata.totalBytes))
    }

    internal fun probe(url: String): RemoteMetadata = remoteProbe.probe(url)

    private suspend fun downloadSegment(
        url: String,
        destination: File,
        segment: DownloadSegmentEntity,
        metadata: RemoteMetadata,
        progress: AtomicLong,
    ) {
        val rangeSupported = metadata.rangeSupported
        var position = progress.get()
        if (position > segment.endByte) return
        var lastCheckpoint = position
        var attempt = 0
        while (position <= segment.endByte) {
            coroutineContext.ensureActive()
            if (!rangeSupported) {
                position = 0
                progress.set(0)
                lastCheckpoint = 0
                downloadDao.updateSegment(segment.taskId, segment.segmentIndex, 0)
            }
            try {
                val builder = Request.Builder().url(url).header("Accept-Encoding", "identity")
                if (rangeSupported) builder.header("Range", "bytes=$position-${segment.endByte}")
                val validator = metadata.etag?.takeUnless { it.startsWith("W/") } ?: metadata.lastModified
                if (rangeSupported && validator != null) builder.header("If-Range", validator)
                remoteProbe.transfer(builder.build()) { response ->
                    if (response.code == 403) throw HttpStatusException(403)
                    val expected = if (rangeSupported) 206 else 200
                    if (response.code != expected) throw HttpStatusException(response.code)
                    RemoteFileProbe.validateTransfer(response, position, segment.endByte, metadata)
                    RandomAccessFile(destination, "rw").channel.use { channel ->
                        val input = response.body.byteStream()
                        val buffer = ByteArray(BufferSize)
                        while (position <= segment.endByte) {
                            coroutineContext.ensureActive()
                            val limit = minOf(buffer.size.toLong(), segment.endByte - position + 1).toInt()
                            val read = input.read(buffer, 0, limit)
                            if (read < 0) break
                            var byteBuffer = ByteBuffer.wrap(buffer, 0, read)
                            var writePosition = position
                            while (byteBuffer.hasRemaining()) {
                                val written = channel.write(byteBuffer, writePosition)
                                writePosition += written
                            }
                            position += read
                            progress.set(position)
                            if (position - lastCheckpoint >= CheckpointBytes) {
                                downloadDao.updateSegment(segment.taskId, segment.segmentIndex, position)
                                lastCheckpoint = position
                            }
                        }
                    }
                }
                if (position <= segment.endByte) throw IOException("unexpected_eof")
                downloadDao.updateSegment(segment.taskId, segment.segmentIndex, position)
                return
            } catch (error: DownloadException) {
                throw error
            } catch (error: HttpStatusException) {
                throw error
            } catch (error: IOException) {
                attempt++
                if (attempt >= MaxAttempts) throw error
                delay((500L shl (attempt - 1)) + kotlin.random.Random.nextLong(250))
            }
        }
    }

    private fun errorCode(error: Exception): String = when (error) {
        is DownloadException -> error.code
        is HttpStatusException -> "http_${error.status}"
        is IOException -> "network_or_storage_error"
        else -> error.message ?: "download_failed"
    }

    private fun buildFileName(request: IsoRequest, id: String): String {
        val version = if (request.version == WindowsVersion.Windows11) "11" else "10"
        return "Windows_${version}_${request.language.locale}_${request.architecture.name.lowercase()}_${id.take(8)}.iso"
    }

    private fun IsoAssetEntity.toIsoRequest() = IsoRequest(
        version = enumValueOf(edition),
        language = enumValueOf(language),
        architecture = enumValueOf(architecture),
    )

    companion object {
        private const val PartialSuffix = ".part"
        private const val BufferSize = 256 * 1024
        private const val CheckpointBytes = 8L * 1024 * 1024
        private const val MaxAttempts = 3

        fun newSegments(taskId: String, totalBytes: Long, connections: Int): List<DownloadSegmentEntity> {
            require(totalBytes > 0)
            require(connections > 0)
            val count = minOf(connections.toLong(), totalBytes).toInt()
            val baseSize = totalBytes / count
            val remainder = totalBytes % count
            var start = 0L
            return List(count) { index ->
                val size = baseSize + if (index < remainder) 1 else 0
                DownloadSegmentEntity(taskId, index, start, start + size - 1, start).also { start += size }
            }
        }

        fun validSegments(segments: List<DownloadSegmentEntity>, totalBytes: Long): Boolean {
            if (segments.isEmpty() || segments.first().startByte != 0L || segments.last().endByte != totalBytes - 1) return false
            return segments.withIndex().all { (index, segment) ->
                segment.segmentIndex == index &&
                    segment.startByte <= segment.currentByte && segment.currentByte <= segment.endByte + 1 &&
                    (index == 0 || segments[index - 1].endByte + 1 == segment.startByte)
            }
        }

        private fun downloadedBytes(
            segments: List<DownloadSegmentEntity>,
            progress: Map<Int, AtomicLong>,
        ): Long = segments.sumOf { progress.getValue(it.segmentIndex).get() - it.startByte }
    }
}
