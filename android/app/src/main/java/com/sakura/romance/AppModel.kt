package com.sakura.romance

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import coil.memory.MemoryCache
import coil.request.CachePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.coroutineContext

class AppModel(application: Application) : AndroidViewModel(application) {
    val vault = Vault(application)
    val api = Api(vault)
    internal val previewCache = PreviewCache(application, vault)
    internal val imageDownloads = mutableStateMapOf<String, Float?>()
    private var imageEpoch = 0
    var images by mutableStateOf(newImageLoader()); private set
    private fun newImageLoader(): ImageLoader {
        val epoch = imageEpoch
        val client = api.client.newBuilder().dispatcher(okhttp3.Dispatcher()).addInterceptor(previewCache)
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                val tracked = url.startsWith("${vault.url.trimEnd('/')}/api/media/") && url.endsWith("/content")
                if (tracked) viewModelScope.launch { if (epoch == imageEpoch) imageDownloads.remove(url) }
                val response = chain.proceed(chain.request())
                val body = response.body
                if (response.isSuccessful && body != null && body.contentType()?.type == "image" &&
                    tracked) {
                    response.newBuilder().body(ImageDownloadBody(body) { progress ->
                        viewModelScope.launch { if (epoch == imageEpoch) imageDownloads[url] = progress }
                    }).build()
                } else response
            }.build()
        return ImageLoader.Builder(getApplication<Application>()).okHttpClient(client)
            .memoryCache { MemoryCache.Builder(getApplication<Application>()).maxSizeBytes(24 * 1024 * 1024).build() }
            .diskCachePolicy(CachePolicy.DISABLED).memoryCachePolicy(CachePolicy.ENABLED).build()
    }
    private fun clearImages() {
        imageEpoch++
        imageDownloads.clear()
        val previous = images
        images = newImageLoader()
        previous.memoryCache?.clear()
        previous.shutdown()
    }
    private val inbox = InboxModel(api, viewModelScope, { ready }, { visibilityEpoch }, ::showError)
    val inboxOpen get() = inbox.inboxOpen
    val letterTarget get() = inbox.letterTarget
    val letterDetail get() = inbox.letterDetail
    val letters get() = inbox.letters
    val lettersBefore get() = inbox.lettersBefore
    val lettersLoading get() = inbox.lettersLoading
    val lettersError get() = inbox.lettersError

    fun handleMessageIntent(intent: android.content.Intent?) {
        validMessageId(intent?.getStringExtra("sakura_message_id"))?.let {
            inbox.showTarget(it); viewing = null
            if (ready) loadLetters()
        }
    }
    fun openInbox() { inbox.openInbox(); clearSelection(); viewing = null }
    fun closeInbox() = inbox.closeInbox()
    fun backFromInbox() = inbox.backFromInbox()
    fun openLetter(id: String) = inbox.openLetter(id)
    fun loadLetters(more: Boolean = false) = inbox.loadLetters(more)
    var bound by mutableStateOf(vault.token.isNotEmpty()); private set
    var ready by mutableStateOf(false); private set
    var serverConnected by mutableStateOf(false); private set
    private var sessionChecks by mutableIntStateOf(0)
    internal val checkingSession get() = sessionChecks > 0
    var busy by mutableStateOf(false); private set
    var albumPasswordEnabled by mutableStateOf(false); private set
    var albumPrivacySupported by mutableStateOf(false); private set
    var albumSessionUnlocked by mutableStateOf(false); private set
    var albumToUnlock by mutableStateOf<Album?>(null); private set
    private var afterAlbumUnlock: (() -> Unit)? = null
    var deviceName by mutableStateOf(""); private set
    var signature by mutableStateOf(""); private set
    var signatureSaving by mutableStateOf(false); private set
    private var signatureVersion = -1L

    private fun applySignature(result: JSONObject) {
        val next = result.optLong("signature_version", 0)
        if (next >= signatureVersion) {
            signatureVersion = next
            signature = result.optString("signature", "").takeUnless { result.isNull("signature") }.orEmpty()
        }
    }

    fun saveSignature(value: String, done: () -> Unit) {
        if (signatureSaving) return
        signatureSaving = true
        val epoch = bindingEpoch
        action {
            try {
                val result = api.obj("/api/profile", "PUT", json("signature" to value))
                if (epoch == bindingEpoch) { applySignature(result); done() }
            } finally { signatureSaving = false }
        }
    }
    var message by mutableStateOf<String?>(null)
    var albums by mutableStateOf<List<Album>>(emptyList()); private set
    var albumsLoading by mutableStateOf(true); private set
    var albumsError by mutableStateOf(false); private set
    var mediaLoading by mutableStateOf(true); private set
    var mediaError by mutableStateOf(false); private set
    var organizingAlbums by mutableStateOf(false); private set
    private var albumRevision = 0L
    var albumMore by mutableStateOf(false); private set
    var currentAlbum by mutableStateOf<Album?>(null); private set
    var media by mutableStateOf<List<Media>>(emptyList()); private set
    var mediaMore by mutableStateOf(false); private set
    var selecting by mutableStateOf(false); private set
    var selectedMediaIds by mutableStateOf<Set<String>>(emptySet()); private set
    var managingMedia by mutableStateOf(false); private set
    var viewing by mutableStateOf<Media?>(null)
    var used by mutableStateOf(0L); private set
    var quota by mutableStateOf(0L); private set
    var maxFile by mutableStateOf(2L * 1024 * 1024 * 1024); private set
    internal var deferredUploadHash = false; private set
    internal var uploadChunkSize = 4L * 1024 * 1024; private set
    internal var parallelChunkSize = 0L; private set
    internal var parallelUploads = 1; private set
    internal var parallelWindow = 1; private set
    var tasks by mutableStateOf<List<UploadTask>>(emptyList()); private set
    var uploading by mutableStateOf(false); private set
    internal var uploadRunId = 0L; private set
    private val automaticUploads = mutableSetOf<String>()
    var uploadNotice by mutableStateOf<String?>(null); internal set
    private var pendingRemovals = emptyList<UploadTask>()
    private var cleanupJob: Job? = null
    private var foreground = false
    private var visibilityEpoch = 0
    private var version = -1
    private var bindingEpoch = 0
    private var activeActions = 0
    private var monitor: Job? = null
    private var uploadJob: Job? = null
    private var currentUploadJob: Deferred<Unit>? = null
    private var priorityUploadId: String? = null
    internal val progressEpoch = AtomicLong()
    var activeUploadId by mutableStateOf<String?>(null); private set
    private var lockJob: Job? = null
    private val queue = UploadQueue(application)

    init {
        runCatching {
            val entries = queue.read()
            tasks = entries.filterNot { it.optBoolean("removed") }.map { UploadTask.parse(it) }
            pendingRemovals = entries.filter { it.optBoolean("removed") }.map { UploadTask.parse(it) }
        }.onFailure {
            if (queue.exists) message = "上传队列读取失败，请重新选择文件；服务器内容不受影响"
        }
    }

    private suspend fun saveQueue() = queue.save(tasks, pendingRemovals)

    internal suspend fun updateTask(task: UploadTask, persist: Boolean = true) {
        if (tasks.none { it.id == task.id }) return
        val snapshot = tasks.map { if (it.id == task.id) task else it }
        tasks = snapshot
        if (uploading) UploadService.update(getApplication(), snapshot, activeUploadId)
        if (persist) saveQueue()
    }

    private fun showError(error: Exception) {
        if (error is CancellationException) return
        if (error is ApiError && error.status == 401) {
            serverConnected = false
            clearImages()
            MorningReminder.disable(getApplication())
            ready = false; bound = false; vault.token = ""; api.unlockToken = ""; viewing = null
        }
        if (error is ApiError && error.status == 423) {
            clearImages()
            api.unlockToken = ""; viewing = null; media = emptyList()
            albumSessionUnlocked = false
            val locked = currentAlbum
            currentAlbum = null
            if (locked != null) requestAlbumAccess(locked.copy(protected = true)) { openAlbum(locked.copy(protected = true)) }
        }
        message = error.message ?: "操作失败，请稍后重试"
    }

    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            activeActions++
            busy = true
            try { block() } catch (error: Exception) {
                if (error is CancellationException) throw error
                showError(error)
            } finally { activeActions--; busy = activeActions > 0 }
        }
    }

    fun bind(url: String, invite: String, name: String) = action {
        val parsed = url.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("请输入有效的 HTTPS 地址")
        require(parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.query == null && parsed.fragment == null && parsed.encodedPath == "/") {
            "服务器地址需要使用 HTTPS，可包含端口，不要包含路径或账号密码"
        }
        pauseUploads()
        uploadJob?.join()
        monitor?.cancel(); inbox.cancel(); cleanupJob?.cancel(); lockJob?.cancel()
        api.resetConnection()
        bindingEpoch++
        visibilityEpoch++
        version = -1
        bound = false; ready = false; serverConnected = false
        parallelChunkSize = 0L; parallelUploads = 1; parallelWindow = 1
        albumPasswordEnabled = false; albumPrivacySupported = false; albumSessionUnlocked = false
        cancelAlbumUnlock(); clearSelection(); clearImages()
        currentAlbum = null; viewing = null; albums = emptyList(); media = emptyList()
        albumMore = false; mediaMore = false; organizingAlbums = false
        inbox.resetBinding()
        deviceName = ""; signature = ""; signatureVersion = -1L; used = 0; quota = 0; message = null
        vault.token = ""
        vault.url = parsed.toString().trimEnd('/')
        val result = api.obj("/api/bind", "POST", json("invite" to invite.trim(), "name" to name.trim().ifBlank { "我们的手机" }))
        vault.token = result.getString("token")
        MorningReminder.enable(getApplication())
        bound = true
        MessagePush.restore(getApplication())
        resume()
    }

    fun resume() {
        foreground = true
        if (!bound) return
        monitor?.cancel()
        monitor = viewModelScope.launch {
            sessionChecks++
            try {
                lockJob?.join()
                try { checkSession() } catch (error: Exception) { showError(error) }
            } finally { sessionChecks-- }
            while (foreground && bound) {
                delay(5000)
                try { checkSession() } catch (error: Exception) { showError(error) }
            }
        }
    }

    fun background() {
        visibilityEpoch++
        inbox.clearVisible()
        foreground = false
        serverConnected = false
        monitor?.cancel()
        cancelAlbumUnlock()
        albumSessionUnlocked = false
        if (currentAlbum?.protected == true) currentAlbum = null
        if (!uploading) api.client.dispatcher.cancelAll()
        clearImages()
        ready = false
        viewing = null
        clearSelection()
        media = emptyList()
        albums = emptyList()
        organizingAlbums = false
        albumsLoading = true; mediaLoading = true
        if (!uploading) lockSession()
    }

    private fun lockSession() {
        api.unlockToken = ""
        albumSessionUnlocked = false
        if (bound) lockJob = viewModelScope.launch { runCatching { api.obj("/api/lock", "POST") } }
    }

    private suspend fun checkSession() {
        sessionChecks++
        try {
            val requestedToken = api.unlockToken
            val result = try {
                api.obj("/api/session").also { serverConnected = true }
            } catch (error: Exception) {
                if (error !is CancellationException) serverConnected = false
                throw error
            }
            val next = result.getInt("version")
            if (next < version) return
            albumPasswordEnabled = result.optBoolean("album_password_enabled")
            albumPrivacySupported = result.optBoolean("album_privacy")
            deviceName = result.getString("device_name")
            if (result.has("signature_version")) {
                if (result.optBoolean("signature_import_allowed")) {
                    val legacy = getApplication<Application>().getSharedPreferences("personal_profile", Context.MODE_PRIVATE)
                        .getString("signature", "").orEmpty()
                    applySignature(api.obj("/api/profile", "PUT", json("signature" to legacy, "initialize" to true)))
                } else applySignature(result)
            }
            if ((version != -1 && next != version) || (requestedToken.isNotEmpty() && requestedToken == api.unlockToken && !result.optBoolean("album_unlocked"))) {
                clearImages()
                visibilityEpoch++
                albumSessionUnlocked = false
                api.unlockToken = ""; viewing = null; ready = false; media = emptyList(); currentAlbum = null
                pauseUploads()
            }
            version = next
            val wasReady = ready
            ready = foreground
            if (ready && !wasReady) { refreshAll(); if (inboxOpen) loadLetters() }
            startAutomaticUploads()
            cleanRemovedUploads()
        } finally { sessionChecks-- }
    }

    fun unlockAlbum(password: String) = action {
        val epoch = visibilityEpoch
        lockJob?.join()
        val result = api.obj("/api/unlock", "POST", json("password" to password))
        if (!foreground || epoch != visibilityEpoch) {
            if (uploading) api.unlockToken = result.getString("unlock_token")
            else api.obj("/api/lock", "POST")
            return@action
        }
        api.unlockToken = result.getString("unlock_token")
        albumSessionUnlocked = api.unlockToken.isNotEmpty()
        version = result.getInt("version")
        ready = foreground
        if (ready) {
            val nextAction = afterAlbumUnlock
            cancelAlbumUnlock()
            nextAction?.invoke()
        }
        startAutomaticUploads()
    }

    fun cancelAlbumUnlock() { albumToUnlock = null; afterAlbumUnlock = null }
    private fun requestAlbumAccess(album: Album, proceed: () -> Unit) {
        if (album.protected && !albumSessionUnlocked) {
            albumToUnlock = album; afterAlbumUnlock = proceed
        } else proceed()
    }

    fun changeAlbumPassword(old: String, new: String, done: () -> Unit) = action {
        api.obj("/api/password", "PUT", json("current_password" to old, "new_password" to new))
        checkSession(); done()
    }

    fun refresh() = action { refreshAll() }
    private suspend fun refreshAll() = coroutineScope {
        launch { loadAlbums(false) }
        launch {
            val storage = api.obj("/api/storage")
            used = storage.getLong("used"); quota = storage.getLong("quota"); maxFile = storage.getLong("max_file")
            deferredUploadHash = storage.optBoolean("deferred_sha256", false)
            uploadChunkSize = storage.optLong("chunk_size", 4L * 1024 * 1024).coerceIn(1L, 16L * 1024 * 1024)
            parallelChunkSize = storage.optLong("parallel_chunk_size", 0L)
            parallelUploads = storage.optInt("parallel_uploads", 1).coerceIn(1, 3)
            parallelWindow = storage.optInt("parallel_window", parallelUploads).coerceIn(parallelUploads, 12)
        }
        if (currentAlbum != null) launch { loadMedia(false) }
        Unit
    }
    private suspend fun loadAlbums(more: Boolean) {
        val epoch = visibilityEpoch
        albumsLoading = true; albumsError = false
        try {
            if (!more) albumRevision = api.obj("/api/albums/order").getLong("revision")
            val result = api.array("/api/albums?live=true&offset=${if (more) albums.size else 0}&limit=30&revision=$albumRevision")
            if (!ready || epoch != visibilityEpoch) return
            val list = (0 until result.length()).map { Album.parse(result.getJSONObject(it)) }
            albums = if (more) (albums + list).distinctBy { it.id } else list
            albumMore = list.size == 30
            currentAlbum?.let { selected -> albums.find { it.id == selected.id }?.let { currentAlbum = it } }
        } catch (error: Exception) {
            if (more && error is ApiError && error.status == 409 && epoch == visibilityEpoch) {
                loadAlbums(false)
                message = "相册排列已更新，请重新查看"
                return
            }
            if (epoch == visibilityEpoch) albumsError = true
            throw error
        } finally { if (epoch == visibilityEpoch) albumsLoading = false }
    }
    fun moreAlbums() { if (!albumsLoading && !busy) action { loadAlbums(true) } }
    private suspend fun loadAllAlbums() {
        loadAlbums(false)
        while (albumMore && ready) loadAlbums(true)
    }
    fun organizeAlbums() = action {
        loadAllAlbums()
        if (ready) organizingAlbums = true
    }
    fun finishOrganizingAlbums() { if (!busy) organizingAlbums = false }
    fun positionAlbum(album: Album, pinned: Boolean, beforeId: String?) {
        if (busy || !ready) return
        val previous = albums
        val remaining = albums.filter { it.id != album.id }.toMutableList()
        val index = if (beforeId != null) remaining.indexOfFirst { it.id == beforeId }
            else if (pinned) remaining.indexOfFirst { !it.pinned }.let { if (it < 0) remaining.size else it } else remaining.size
        remaining.add(index.coerceAtLeast(0), album.copy(pinned = pinned))
        albums = remaining
        action {
            val epoch = visibilityEpoch
            try {
                api.obj("/api/albums/${album.id}/position", "PUT", json("pinned" to pinned, "before_id" to beforeId, "revision" to albumRevision))
                if (epoch == visibilityEpoch && ready) {
                    if (organizingAlbums) loadAllAlbums() else loadAlbums(false)
                }
            } catch (error: Exception) {
                if (epoch == visibilityEpoch && ready) {
                    albums = previous
                    if (error is ApiError && error.status == 409) {
                        if (organizingAlbums) loadAllAlbums() else loadAlbums(false)
                    }
                }
                throw error
            }
        }
    }
    fun toggleAlbumPin(album: Album) = positionAlbum(album, !album.pinned,
        if (album.pinned) albums.firstOrNull { !it.pinned }?.id else null)
    fun openAlbum(album: Album) = requestAlbumAccess(album) { clearSelection(); currentAlbum = album; media = emptyList(); mediaLoading = true; mediaError = false; viewing = null; action { loadMedia(false) } }
    fun closeAlbum() { clearSelection(); currentAlbum = null; media = emptyList(); viewing = null; action { refreshAll() } }
    private suspend fun loadMedia(more: Boolean) {
        val selected = currentAlbum ?: return
        val epoch = visibilityEpoch
        mediaLoading = true; mediaError = false
        try {
            val cursor = if (more) media.lastOrNull()?.cursor.orEmpty() else ""
            val page = if (cursor.isNotEmpty()) "before=${Uri.encode(cursor)}"
                else "offset=${if (more) media.size else 0}"
            val result = api.array("/api/albums/${selected.id}/media?live=true&$page&limit=30")
            if (!ready || epoch != visibilityEpoch || currentAlbum?.id != selected.id) return
            val list = (0 until result.length()).map { Media.parse(result.getJSONObject(it)) }
            media = if (more) (media + list).distinctBy { it.id } else list
            mediaMore = list.size == 30
        } catch (error: Exception) {
            if (epoch == visibilityEpoch && currentAlbum?.id == selected.id) mediaError = true
            throw error
        } finally { if (epoch == visibilityEpoch && currentAlbum?.id == selected.id) mediaLoading = false }
    }
    fun moreMedia() { if (!mediaLoading && mediaMore) action { loadMedia(true) } }
    fun createAlbum(name: String) = saveAlbum(null, name, false, "") {}
    fun saveAlbum(album: Album?, name: String, protected: Boolean, password: String, done: () -> Unit) = action {
        check(albumPrivacySupported) { "请先更新服务器以使用相册加密" }
        val epoch = visibilityEpoch
        if (protected && !albumSessionUnlocked) {
            if (!albumPasswordEnabled) {
                api.obj("/api/password", "PUT", json("new_password" to password))
                albumPasswordEnabled = true
            }
            lockJob?.join()
            val result = api.obj("/api/unlock", "POST", json("password" to password))
            if (!foreground || epoch != visibilityEpoch) { api.obj("/api/lock", "POST"); return@action }
            api.unlockToken = result.getString("unlock_token")
            albumSessionUnlocked = api.unlockToken.isNotEmpty()
            version = result.getInt("version")
        }
        api.obj(if (album == null) "/api/albums" else "/api/albums/${album.id}", if (album == null) "POST" else "PATCH",
            json("name" to name, "protected" to protected))
        clearImages()
        checkSession(); refreshAll(); done()
    }
    fun deleteAlbum() = action {
        val selected = currentAlbum ?: return@action
        api.request("/api/albums/${selected.id}", "DELETE"); currentAlbum = null; refreshAll()
    }
    fun deleteMedia(item: Media) = action {
        api.request("/api/media/${item.id}?live=true", "DELETE"); clearImages(); viewing = null; refreshAll()
    }
    fun moveMedia(item: Media, destination: Album) = requestAlbumAccess(destination) { action {
        api.obj("/api/media/${item.id}?live=true", "PATCH", json("album_id" to destination.id)); viewing = null; refreshAll()
    } }
    fun beginSelection() { selecting = true }
    fun clearSelection() { selecting = false; selectedMediaIds = emptySet() }
    fun toggleSelection(id: String) {
        if (managingMedia) return
        selecting = true
        selectedMediaIds = if (id in selectedMediaIds) selectedMediaIds - id else selectedMediaIds + id
    }
    fun selectLoadedMedia() {
        if (managingMedia) return
        val ids = media.map { it.id }.toSet()
        selectedMediaIds = if (selectedMediaIds.containsAll(ids)) selectedMediaIds - ids else selectedMediaIds + ids
    }
    fun deleteSelectedMedia() = manageSelectedMedia(null)
    fun moveSelectedMedia(destination: Album) = requestAlbumAccess(destination) { manageSelectedMedia(destination) }
    private fun manageSelectedMedia(destination: Album?) {
        if (managingMedia || !ready || selectedMediaIds.isEmpty()) return
        val ids = selectedMediaIds.toList()
        val epoch = visibilityEpoch
        val operation = if (destination == null) "删除" else "移动"
        managingMedia = true
        action {
            var completed = 0
            try {
                for (id in ids) {
                    check(ready && foreground && epoch == visibilityEpoch) { "操作已暂停，请解锁后重试" }
                    if (destination == null) api.request("/api/media/$id?live=true", "DELETE")
                    else api.obj("/api/media/$id?live=true", "PATCH", json("album_id" to destination.id))
                    completed++
                    selectedMediaIds = selectedMediaIds - id
                    media = media.filterNot { it.id == id }
                }
                clearSelection()
                message = "已${operation} $completed 项回忆"
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                showError(error)
                message = "已${operation} $completed 项，剩余 ${ids.size - completed} 项未完成：${error.message ?: "请重试"}"
            } finally {
                if (destination == null && completed > 0) clearImages()
                try { if (ready) refreshAll() }
                finally { managingMedia = false }
            }
        }
    }

    fun setCover(item: Media) = action {
        api.obj("/api/albums/${item.albumId}/cover", "PUT", json("media_id" to item.id)); refreshAll(); message = "相册封面已更新"
    }

    fun enqueue(uris: List<Uri>, albumId: String) = action {
        val limit = maxFile
        val epoch = bindingEpoch
        val resolver = getApplication<Application>().contentResolver
        val newGrants = mutableSetOf<Uri>()
        var accepted = false
        try {
            val (selected, errors) = withContext(Dispatchers.IO) {
                val existingGrants = resolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }.toSet()
                val selected = mutableListOf<UploadTask>()
                val errors = mutableListOf<String>()
                for (uri in uris) {
                    coroutineContext.ensureActive()
                    var name = "回忆"
                    try {
                        var size = -1L
                        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                name = cursor.getString(0) ?: "回忆"
                                if (!cursor.isNull(1)) size = cursor.getLong(1)
                            }
                        }
                        if (size <= 0) { errors += "$name 无法读取大小，请先保存到手机再选择"; continue }
                        if (size > limit) { errors += "$name 超过单文件限制 ${formatSize(limit)}"; continue }
                        val mime = resolver.getType(uri) ?: "application/octet-stream"
                        // Reject invalid metadata before retaining access to the source.
                        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        if (uri !in existingGrants) newGrants += uri
                        selected += UploadTask(UUID.randomUUID().toString(), uri.toString(), albumId, name, mime, size)
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { errors += "$name 无法读取，请重新选择文件" }
                }
                selected to errors
            }
            coroutineContext.ensureActive()
            if (epoch != bindingEpoch) throw CancellationException("设备绑定已更换")
            tasks = tasks + selected
            accepted = true
            if (errors.isNotEmpty()) message = "已添加 ${selected.size} 项，${errors.size} 项未添加：${errors.take(3).joinToString("；")}"
            saveQueue()
            automaticUploads.addAll(selected.map { it.id })
            startAutomaticUploads()
        } finally {
            if (!accepted) {
                val retained = tasks.map { it.uri }.toSet()
                withContext(Dispatchers.IO + NonCancellable) {
                    for (uri in newGrants) if (uri.toString() !in retained) runCatching {
                        resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
            }
        }
    }

    fun pauseUploads() {
        automaticUploads.clear()
        priorityUploadId = null
        progressEpoch.incrementAndGet()
        uploadJob?.cancel()
    }

    fun uploadNow(id: String) = resumeUploads(setOf(id), id)
    fun continueUploads(ids: Set<String>) = resumeUploads(ids)
    fun startUploads() = continueUploads(tasks.filter { it.status != "已完成" }.map { it.id }.toSet())

    private fun resumeUploads(ids: Set<String>, priority: String? = null) {
        if (!bound || !foreground) {
            uploadNotice = "请返回应用并连接服务器后继续上传"
            return
        }
        uploadNotice = "正在准备继续上传…"
        viewModelScope.launch {
            try {
                if (!ready) checkSession()
                check(ready) { "尚未连接服务器，请稍后继续上传" }
                val selected = tasks.filter { it.id in ids && it.status != "已完成" && it.id != activeUploadId }
                if (selected.isEmpty()) { uploadNotice = "所选任务已完成或正在上传"; return@launch }
                val locked = albums.firstOrNull { it.protected && selected.any { task -> task.album == it.id } }
                if (!albumSessionUnlocked && locked != null) {
                    uploadNotice = "请先解锁加密相册，再继续上传"
                    requestAlbumAccess(locked) { resumeUploads(ids, priority) }
                    return@launch
                }
                val selectedIds = selected.map { it.id }.toSet()
                automaticUploads.addAll(selectedIds)
                tasks = tasks.map { if (it.id in selectedIds) it.copy(status = "等待上传", error = "") else it }
                if (priority != null && priority in selectedIds) {
                    tasks = prioritizeUploadTasks(tasks, priority, activeUploadId)
                    priorityUploadId = priority
                    activeUploadId?.let { automaticUploads += it }
                    progressEpoch.incrementAndGet()
                    currentUploadJob?.cancel()
                }
                uploadNotice = "已安排 ${selected.size} 项继续上传"
                saveQueue()
                startAutomaticUploads()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                uploadNotice = error.message ?: "无法开始上传，请检查网络后重试"
                showError(error)
            }
        }
    }

    internal fun uploadServiceDestroyed(runId: Long) {
        if (runId == uploadRunId) pauseUploads()
    }

    private fun startAutomaticUploads() {
        automaticUploads.retainAll(tasks.filter { it.status != "已完成" }.map { it.id }.toSet())
        if (!ready || !foreground || uploading) return
        val next = tasks.firstOrNull { it.id in automaticUploads && it.status != "已完成" } ?: return
        if (!albumSessionUnlocked && albums.firstOrNull { it.id == next.album }?.protected == true) {
            uploadNotice = "请解锁加密相册后继续上传"
            return
        }
        uploadRunId++
        try { getApplication<Application>().startForegroundService(Intent(getApplication(), UploadService::class.java).putExtra(UploadService.RUN_ID, uploadRunId)) }
        catch (error: Exception) { automaticUploads.clear(); uploadNotice = error.message; showError(error); return }
        uploading = true
        uploadJob = viewModelScope.launch {
            try {
                while (bound) {
                    coroutineContext.ensureActive()
                    val item = tasks.firstOrNull { it.id == priorityUploadId && it.status != "已完成" }
                        ?: tasks.firstOrNull { it.id in automaticUploads && it.status != "已完成" } ?: break
                    priorityUploadId = null
                    automaticUploads.remove(item.id)
                    activeUploadId = item.id
                    try {
                        supervisorScope {
                            val transfer = async<Unit> {
                                val session = api.unlockToken
                                try { uploadRecovering(item) }
                                catch (error: ApiError) {
                                    if (error.status != 423 || api.unlockToken.isEmpty() || api.unlockToken == session) throw error
                                    tasks.find { it.id == item.id }?.let { uploadRecovering(it) }
                                }
                            }
                            currentUploadJob = transfer
                            transfer.await()
                        }
                    } catch (error: CancellationException) {
                        progressEpoch.incrementAndGet()
                        withContext(NonCancellable) {
                            tasks.find { it.id == item.id && it.status != "已完成" }?.let {
                                updateTask(it.copy(status = "已暂停", sentOffset = null, error = ""))
                            }
                        }
                        if (!coroutineContext.isActive) throw error
                        if (tasks.any { it.id == item.id }) {
                            if (priorityUploadId == null) throw error
                            automaticUploads += item.id
                        }
                    } catch (error: Exception) {
                        progressEpoch.incrementAndGet()
                        val detail = when (error) {
                            is java.io.FileNotFoundException, is SecurityException -> "原文件无法读取，请重新选择文件"
                            is java.io.IOException -> if (error is ApiError) error.message else "网络连接中断，请检查网络后继续上传"
                            else -> error.message ?: "上传失败，请重试"
                        }
                        tasks.find { it.id == item.id }?.let { updateTask(it.copy(status = "上传失败", sentOffset = null, error = detail)) }
                        uploadNotice = detail
                        if (error is ApiError && error.status in listOf(401, 423, 507)) {
                            automaticUploads.clear(); priorityUploadId = null; showError(error); break
                        }
                    } finally {
                        progressEpoch.incrementAndGet()
                        currentUploadJob = null
                        activeUploadId = null
                    }
                }
                if (ready) refreshAll()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { uploadNotice = error.message; showError(error) }
            finally {
                uploading = false
                if (!foreground) lockSession()
                startAutomaticUploads()
                if (!uploading) getApplication<Application>().stopService(Intent(getApplication(), UploadService::class.java))
                cleanRemovedUploads()
            }
        }
    }

    fun removeTask(item: UploadTask) = removeTasks(setOf(item.id))

    fun removeTasks(ids: Set<String>) {
        val removed = tasks.filter { it.id in ids }
        if (removed.isEmpty()) return
        val transfer = currentUploadJob?.takeIf { activeUploadId in ids }
        pendingRemovals = pendingRemovals + removed.filter { it.status != "已完成" }
        tasks = tasks.filterNot { it.id in ids }
        automaticUploads.removeAll(ids)
        if (priorityUploadId in ids) priorityUploadId = null
        if (transfer != null) { progressEpoch.incrementAndGet(); transfer.cancel() }
        if (uploading) UploadService.update(getApplication(), tasks, activeUploadId)
        uploadNotice = "已移除 ${removed.size} 项上传任务"
        viewModelScope.launch {
            try {
                saveQueue()
                transfer?.join()
                for (item in removed) if (tasks.none { it.uri == item.uri }) {
                    runCatching {
                        getApplication<Application>().contentResolver.releasePersistableUriPermission(Uri.parse(item.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                cleanRemovedUploads()
            } catch (error: Exception) { if (error !is CancellationException) { uploadNotice = error.message; showError(error) } }
        }
    }

    private fun cleanRemovedUploads() {
        if (!ready || !foreground || pendingRemovals.isEmpty() || cleanupJob?.isActive == true) return
        cleanupJob = viewModelScope.launch {
            for (item in pendingRemovals.toList()) {
                // Wait until the cancelled create/chunk request has stopped before cleaning its session.
                if (activeUploadId == item.id) continue
                try {
                    val remote = item.remote.ifEmpty {
                        api.obj("/api/uploads", "POST", json("request_id" to item.requestId, "album_id" to item.album,
                            "name" to item.name, "mime" to item.mime, "size" to item.size).apply {
                            if (item.hash.isNotEmpty()) put("sha256", item.hash)
                        }).getString("id")
                    }
                    try { api.request("/api/uploads/$remote", "DELETE") }
                    catch (error: ApiError) {
                        if (error.status != 409) throw error
                        // Removing a queue entry must never delete already saved album content.
                        if (api.obj("/api/uploads/$remote").getString("status") != "complete") continue
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (error !is ApiError || error.status != 404) continue
                }
                pendingRemovals = pendingRemovals.filterNot { it.requestId == item.requestId }
                saveQueue()
            }
        }
    }

}

fun formatSize(bytes: Long): String = if (bytes >= 1024L * 1024 * 1024) "%.1f GB".format(bytes / (1024.0 * 1024 * 1024)) else "%.1f MB".format(bytes / (1024.0 * 1024))
