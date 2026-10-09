package cc.novelia.app.data.webdav

import android.util.AtomicFile
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import cc.novelia.app.NoveliaApplication
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** 前台、手动和 Worker 共用入口。网络请求期间不持有业务存储锁。 */
class WebDavSyncManager(private val app: NoveliaApplication) {
    private val mutex = Mutex()
    private val file = File(app.filesDir, "webdav-state.json")
    private val atomic = AtomicFile(file)
    private val wireJson = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = false }
    private val manifestReader = WebDavManifestReader(wireJson)
    private val automaticPolicy = WebDavAutomaticPolicy(SystemClock::elapsedRealtime)
    private val cooldownPreferences = app.getSharedPreferences("webdav-request-cooldown", Context.MODE_PRIVATE)
    private val requestCooldown = WebDavRequestCooldown(System::currentTimeMillis,
        readUntil = { cooldownPreferences.getLong(it, 0L) },
        writeUntil = { account, until ->
            check(cooldownPreferences.edit().putLong(account, until).commit()) { "无法保存 WebDAV 请求冷却时间" }
        })
    @Volatile private var runtimeError: String? = null
    @Volatile private var runtime = readRuntime()
    private val mutableStatus = MutableStateFlow(WebDavSyncStatus(error = runtimeError))
    val status: StateFlow<WebDavSyncStatus> = mutableStatus.asStateFlow()

    /** 首次连接、新增同步项或重新读取状态后，界面必须先展示合并预览。 */
    fun needsConfirmation(): Boolean {
        val binding = app.webDavConfig.config.value
        return binding.datasetId == null || runtimeError != null || runtime.datasetId != binding.datasetId ||
            binding.selected.any { needsJoin(it, binding) }
    }

    suspend fun testConnection() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val binding = app.webDavConfig.config.value
            running(recordSuccess = false) {
                client(binding).testConnection()
                current(binding)
            }
        }
    }

    /** 用户确认后回到首次连接流程；不改本机资料，也不直接创建或覆盖远端文件。 */
    suspend fun prepareReconnect(recovery: WebDavRecovery) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if(mutableStatus.value.recovery != recovery) throw WebDavConfigChangedException()
            running(recordSuccess = false) {
                app.webDavConfig.clearDatasetBinding(recovery)
                runtime = WebDavRuntimeState()
                runtimeError = null
                writeRuntime()
                mutableStatus.update { WebDavSyncStatus(running = it.running, lastAttemptAt = it.lastAttemptAt) }
                refreshPendingStatus()
            }
        }
    }

    suspend fun preview(): WebDavPreview = withContext(Dispatchers.IO) {
        mutex.withLock {
            val binding = app.webDavConfig.config.value
            running(recordSuccess = false) {
                require(binding.selected.isNotEmpty()) { "请至少选择一项同步内容" }
                ready()
                val client = client(binding)
                // 已绑定目录丢失时先交给用户处理，不在查看资料时悄悄重建。
                if(!binding.bound) client.ensureDirectory()
                val manifest = manifest(client, binding)
                val id = manifest?.datasetId ?: UUID.randomUUID().toString()
                val items = binding.selected.sortedBy { it.ordinal }.map { domain ->
                    checkDomainReadable(domain)
                    val remote = readDocument(client, domain, id, useCache = false)
                    current(binding)
                    val local = document(domain, id, seed = true, binding.syncDevicePreferences)
                    val merged = combineDomain(domain, local, remote?.document, needsJoin(domain, binding) || binding.datasetId == null,
                        BootstrapPreference.REMOTE_SETTINGS)
                    validateApplication(domain, merged, binding.syncDevicePreferences)
                    WebDavPreviewItem(domain, WebDavMerge.materialize(local).size,
                        remote?.let { WebDavMerge.materialize(it.document).size } ?: 0, WebDavMerge.conflicts(merged).size)
                }
                WebDavPreview(manifest?.datasetId, manifest != null, items)
            }
        }
    }

    /** 首次加入或新增同步项必须由预览页面调用。连接结果发生变化时重新读取，不使用旧预览覆盖远端。 */
    suspend fun connect(preference: BootstrapPreference) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val binding = app.webDavConfig.config.value
            running {
                ready()
                require(binding.selected.isNotEmpty()) { "请至少选择一项同步内容" }
                val client = client(binding)
                client.testConnection()
                current(binding)
                var manifest = manifest(client, binding)
                if(manifest == null) {
                    val proposed = WebDavManifest(datasetId = UUID.randomUUID().toString())
                    try { client.put(MANIFEST, wireJson.encodeToString(proposed).toByteArray(Charsets.UTF_8), createOnly = true) }
                    catch(error: WebDavException) { if(error.failure != WebDavFailure.CONFLICT) throw error }
                    current(binding)
                    manifest = requireNotNull(manifest(client, binding)) { "无法读取同步目录标识，请重试" }
                }
                val id = manifest.datasetId
                if(binding.datasetId == null || runtime.datasetId != id || runtimeError != null) runtime = WebDavRuntimeState(datasetId = id)
                runtimeError = null
                app.webDavConfig.withBinding(binding.generation) { prepareReplicas(id, binding.selected, binding.syncDevicePreferences) }
                // 先持久化绑定；若后续某个类型失败，已完成类型仍然有效，未完成类型可再次预览加入。
                current(binding)
                app.webDavConfig.bindDataset(binding.generation, id)
                synchronizeDomains(binding.copy(datasetId = id, enabled = true), client, joining = true, preference = preference)
                automaticPolicy.completedFullSync(binding)
            }
        }
    }

    suspend fun synchronize(manual: Boolean = true, expectedGeneration: Long? = null) = withContext(Dispatchers.IO) {
        mutex.withLock {
            expectedGeneration?.let(app.webDavConfig::ensureCurrent)
            val binding = app.webDavConfig.config.value
            if(!binding.enabled || (!manual && !binding.automatic) || binding.selected.isEmpty()) return@withLock
            if(!manual) mutableStatus.value.recovery?.takeIf { it.matches(binding) }?.let {
                throw WebDavRecoveryRequiredException(it)
            }
            // 等待另一轮同步时，网络和配置都可能改变；必须以取得锁后的状态再次判断。
            if(!manual && !automaticNetworkAvailable(binding)) return@withLock
            // 锁内重新规划，前台、Worker 和轮询排队后不会重复做同一轮全量检查。
            val domains = if(manual) binding.selected else automaticPolicy.domains(binding, pendingDomains(binding))
            if(domains.isEmpty()) return@withLock
            running {
                ready()
                require(runtimeError == null) { runtimeError ?: "同步状态无法读取" }
                val id = requireNotNull(binding.datasetId) { "请先预览并加入同步目录" }
                require(runtime.datasetId == id) { "同步状态需要重新确认，请先预览并加入同步目录" }
                val client = client(binding)
                if(!manual) requireAutomaticNetwork(binding)
                requireNotNull(manifest(client, binding)) { "已连接的同步目录标识消失，请检查服务或目录" }
                current(binding)
                app.webDavConfig.withBinding(binding.generation) { prepareReplicas(id, binding.selected, binding.syncDevicePreferences) }
                synchronizeDomains(binding, client, joining = false, preference = BootstrapPreference.REMOTE_SETTINGS,
                    automatic = !manual, domains = domains)
                if(manual) automaticPolicy.completedFullSync(binding)
            }
        }
    }

    suspend fun resolveConflict(conflict: SyncConflict, candidateIndex: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val binding = app.webDavConfig.config.value
            val id = requireNotNull(binding.datasetId) { "请先连接同步目录" }
            require(conflict.domain in binding.selected) { "该同步项已关闭" }
            app.webDavConfig.withBinding(binding.generation) { app.store.updateFromWebDav { state ->
                val replica = state.syncReplica
                val doc = requireNotNull(replica.documents[conflict.domain]) { "冲突已变化，请重新同步" }
                require(doc.datasetId == id && WebDavMerge.conflicts(doc).any { it == conflict }) { "冲突已变化，请重新同步" }
                val counter = maxOf(replica.clock, doc.context.values.maxOrNull() ?: 0) + 1
                val resolved = WebDavMerge.resolve(doc, conflict.recordKey, conflict.field, candidateIndex, replica.deviceId, counter)
                val next = replica.copy(clock = counter, documents = replica.documents + (conflict.domain to resolved))
                WebDavProjection.applyLibrary(state, next.documents, setOf(conflict.domain), binding.syncDevicePreferences).copy(syncReplica = next)
            } }
            app.store.flush()
            refreshConflicts()
        }
        synchronize(manual = true)
    }

    private suspend fun synchronizeDomains(binding: WebDavConfig, client: WebDavClient, joining: Boolean, preference: BootstrapPreference,
        automatic: Boolean = false, domains: Set<SyncDomain> = binding.selected) {
        val id = requireNotNull(binding.datasetId)
        val errors = linkedMapOf<SyncDomain, String>()
        for(domain in domains.sortedBy { it.ordinal }) {
            currentCoroutineContext().ensureActive()
            current(binding)
            if(automatic) requireAutomaticNetwork(binding)
            try {
                checkDomainReadable(domain)
                require(joining || !needsJoin(domain, binding)) { "此项或设备偏好尚未加入同步，请先预览并合并" }
                syncDomain(binding, client, domain, id, joining && needsJoin(domain, binding), preference, automatic)
                val now = System.currentTimeMillis()
                mutableStatus.update { it.copy(domains = it.domains + (domain to WebDavDomainStatus(lastSuccessAt = now))) }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(changed: WebDavConfigChangedException) { throw changed }
            catch(error: Exception) {
                val message = error.message ?: "同步未完成，请稍后重试"
                errors[domain] = message
                mutableStatus.update { it.copy(domains = it.domains +
                    (domain to WebDavDomainStatus(it.domains[domain]?.lastSuccessAt ?: 0, message, true, retryWebDavFailure(error)))) }
                if(error is WebDavException && error.failure == WebDavFailure.RATE_LIMITED) {
                    refreshPendingStatus()
                    throw error
                }
            }
        }
        refreshPendingStatus()
        if(errors.isNotEmpty()) error(errors.entries.joinToString("；") { "${it.key.title}：${it.value}" })
    }

    private suspend fun syncDomain(binding: WebDavConfig, client: WebDavClient, domain: SyncDomain, id: String,
        initialJoin: Boolean, preference: BootstrapPreference, automatic: Boolean) {
        val result = exchangeWebDavDocument(
            readRemote = { fresh ->
                current(binding)
                if(automatic) requireAutomaticNetwork(binding)
                readDocument(client, domain, id, useCache = !fresh).also {
                    if(it == null && domain in runtime.joined) error("已同步的数据文件消失，请检查目录；本机数据已保留")
                }
            },
            captureLocal = {
                val captured = document(domain, id, seed = false, binding.syncDevicePreferences)
                // 每次条件提交前保证设备时钟及对应操作已落盘，重启不能复用已上传版本。
                if(domain == SyncDomain.KEYWORDS) app.keywords.flush() else app.store.flush()
                captured
            },
            combine = { local, remote -> combineDomain(domain, local, remote, initialJoin, preference) },
            validate = { WebDavMerge.validate(it, id); validateApplication(domain, it, binding.syncDevicePreferences) },
            upload = { merged, etag ->
                reserveClock(binding, domain, merged)
                current(binding)
                if(automatic) requireAutomaticNetwork(binding)
                client.put(domain.fileName, wireJson.encodeToString(merged).toByteArray(Charsets.UTF_8),
                    etag = etag, createOnly = etag == null)
            },
            ensureCurrent = { current(binding) },
        )
        // 当前本机可能已在网络请求期间继续编辑。应用阶段再次合并，只确认本次上传的版本。
        applyDomain(binding, domain, result.document, replaceCaptured = domain == SyncDomain.KEYWORDS || initialJoin && domain == SyncDomain.SETTINGS,
            captured = result.captured)
        current(binding)
        runtime = runtime.copy(joined = runtime.joined + domain,
            syncDevicePreferences = if(domain == SyncDomain.SETTINGS) binding.syncDevicePreferences else runtime.syncDevicePreferences,
            cached = runtime.cached + (domain to WebDavCachedDomain(result.etag, result.document, System.currentTimeMillis())))
        writeRuntime()
    }

    private suspend fun ready() {
        app.initialization.await()
        require(app.store.recoveryIssue.value == null) { "请先恢复受保护的本地资料，再进行同步" }
        app.store.flush()
    }

    private fun checkDomainReadable(domain: SyncDomain) {
        if(domain == SyncDomain.KEYWORDS) require(app.keywords.syncReadError.value == null) { "标签库读取失败，请先从备份恢复标签库" }
    }

    private fun client(binding: WebDavConfig): WebDavClient {
        val account = webDavAccountKey(binding)
        return WebDavClient(binding, app.webDavConfig.password(binding),
            beforeRequest = { requestCooldown.check(account) },
            onRateLimit = { requestCooldown.record(account, it) })
    }
    private fun current(binding: WebDavConfig) = app.webDavConfig.ensureCurrent(binding.generation)
    private fun automaticNetworkAvailable(binding: WebDavConfig): Boolean {
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (!binding.wifiOnly || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }
    private fun requireAutomaticNetwork(binding: WebDavConfig) {
        if(!automaticNetworkAvailable(binding)) throw WebDavException(WebDavFailure.NETWORK,
            if(binding.wifiOnly) "自动同步等待非计费网络，本机修改已保留" else "自动同步等待网络连接，本机修改已保留")
    }
    private fun needsJoin(domain: SyncDomain, binding: WebDavConfig) = domain !in runtime.joined ||
        domain == SyncDomain.SETTINGS && binding.syncDevicePreferences && !runtime.syncDevicePreferences
    private fun combineDomain(domain: SyncDomain, local: SyncDocument, remote: SyncDocument?, initialJoin: Boolean,
        preference: BootstrapPreference): SyncDocument {
        if(remote == null) return local
        val replica = if(domain == SyncDomain.KEYWORDS) app.keywords.state.value.syncReplica else app.store.state.value.syncReplica
        // 选择版本须位于该设备所有类型的逻辑时钟之后。
        val observed = if(replica.clock > (local.context[replica.deviceId] ?: 0))
            local.copy(context = local.context + (replica.deviceId to replica.clock)) else local
        return when {
            domain == SyncDomain.KEYWORDS -> WebDavMerge.bootstrapKeywords(observed, remote, replica.deviceId)
            initialJoin && domain == SyncDomain.SETTINGS -> WebDavMerge.bootstrapSettings(observed, remote, replica.deviceId,
                preference == BootstrapPreference.LOCAL_SETTINGS)
            else -> WebDavMerge.merge(local, remote)
        }
    }

    private suspend fun manifest(client: WebDavClient, binding: WebDavConfig): WebDavManifest? {
        val result = manifestReader.read(client, binding)
        current(binding)
        mutableStatus.update { if(it.recovery?.matches(binding) == true) it.copy(recovery = null) else it }
        return result
    }

    private suspend fun readDocument(client: WebDavClient, domain: SyncDomain, id: String, useCache: Boolean = true): WebDavCachedDomain? {
        val cached = runtime.cached[domain]?.takeIf { useCache && it.document.datasetId == id }
        val resource = client.get(domain.fileName, cached?.etag) ?: return null
        if(resource.notModified) return requireNotNull(cached) { "缺少同步文件缓存，请重试" }
        val document = wireJson.decodeFromString<SyncDocument>(checkedWebDavJson(resource.data))
        require(document.domain == domain) { "同步文件的数据类型不一致" }
        WebDavMerge.validate(document, id)
        return WebDavCachedDomain(WebDavClient.requireStrongEtag(resource.etag), document)
    }

    private fun prepareReplicas(id: String, selected: Set<SyncDomain>, includeDevices: Boolean) {
        app.store.updateFromWebDav { state ->
            val old = state.syncReplica
            val reset = old.documents.values.any { it.datasetId != "unbound" && it.datasetId != id }
            var next = if(reset) SyncReplica(deviceId = old.deviceId, clock = old.clock) else old
            val missing = SyncDomain.entries.toSet() - SyncDomain.KEYWORDS - next.documents.keys
            if(missing.isNotEmpty()) next = next.track(emptyMap(), WebDavProjection.library(state, missing, includeDevices), missing)
            if(includeDevices && SyncDomain.SETTINGS in selected) next = withDevicePreferences(next, state)
            state.copy(syncReplica = next.bind(id))
        }
        if(SyncDomain.KEYWORDS !in selected || app.keywords.syncReadError.value != null) return
        app.keywords.applyRemote { library ->
            val old = library.syncReplica
            val reset = old.documents.values.any { it.datasetId != "unbound" && it.datasetId != id }
            var next = if(reset) SyncReplica(deviceId = old.deviceId, clock = old.clock) else old
            if(SyncDomain.KEYWORDS !in next.documents) next = next.track(emptyMap(), WebDavProjection.keywords(library))
            library.copy(syncReplica = next.bind(id))
        }
    }

    private fun document(domain: SyncDomain, id: String, seed: Boolean, includeDevices: Boolean): SyncDocument {
        var replica = if(domain == SyncDomain.KEYWORDS) app.keywords.state.value.syncReplica else app.store.state.value.syncReplica
        if(seed && domain == SyncDomain.SETTINGS && includeDevices && domain in replica.documents) replica = withDevicePreferences(replica, app.store.state.value)
        val existing = replica.documents[domain]
        if(existing != null && (existing.datasetId == id || existing.datasetId == "unbound")) return existing.copy(datasetId = id)
        require(seed)
        return SyncReplica(deviceId = replica.deviceId, clock = replica.clock).track(emptyMap(),
            mapOf(domain to projection(domain, includeDevices)), setOf(domain)).documents.getValue(domain).copy(datasetId = id)
    }

    private fun projection(domain: SyncDomain, includeDevices: Boolean) = if(domain == SyncDomain.KEYWORDS) WebDavProjection.keywords(app.keywords.state.value)[domain].orEmpty()
        else WebDavProjection.library(app.store.state.value, setOf(domain), includeDevices)[domain].orEmpty()

    private fun withDevicePreferences(replica: SyncReplica, state: cc.novelia.app.data.model.LibraryState): SyncReplica {
        val before = WebDavMerge.materialize(replica.documents.getValue(SyncDomain.SETTINGS))
        val physical = WebDavProjection.library(state, setOf(SyncDomain.SETTINGS), true).getValue(SyncDomain.SETTINGS)
        val after = before.toMutableMap()
        physical.forEach { (key, fields) ->
            val devices = fields["devicePreferences"] ?: return@forEach
            val existing = before[key] ?: return@forEach
            if(existing["devicePreferences"] != devices) after[key] = JsonObject(existing + ("devicePreferences" to devices))
        }
        return replica.track(mapOf(SyncDomain.SETTINGS to before), mapOf(SyncDomain.SETTINGS to after), setOf(SyncDomain.SETTINGS))
    }

    private fun validateApplication(domain: SyncDomain, document: SyncDocument, includeDevices: Boolean) {
        if(domain == SyncDomain.KEYWORDS) WebDavProjection.applyKeywords(app.keywords.state.value, document, app.store.state.value.keywordLimit)
        else WebDavProjection.applyLibrary(app.store.state.value, mapOf(domain to document), setOf(domain), includeDevices)
    }

    private suspend fun applyDomain(binding: WebDavConfig, domain: SyncDomain, document: SyncDocument, replaceCaptured: Boolean, captured: SyncDocument) {
        if(domain == SyncDomain.KEYWORDS) {
            app.webDavConfig.withBinding(binding.generation) { app.keywords.applyRemote { library ->
                val replica = replaceAndRebase(library.syncReplica.bind(document.datasetId), document, captured)
                WebDavProjection.applyKeywords(library, replica.documents.getValue(domain), app.store.state.value.keywordLimit).copy(syncReplica = replica)
            } }
            app.keywords.flush()
        } else {
            app.webDavConfig.withBinding(binding.generation) { app.store.updateFromWebDav { state ->
                val bound = state.syncReplica.bind(document.datasetId)
                val replica = if(replaceCaptured) replaceAndRebase(bound, document, captured) else bound.merge(document)
                WebDavProjection.applyLibrary(state, replica.documents, setOf(domain), binding.syncDevicePreferences).copy(syncReplica = replica)
            } }
            app.store.flush()
        }
    }

    /** 初始化选择也会产生新版本；先只保留时钟，上传成功后再落地业务值。 */
    private suspend fun reserveClock(binding: WebDavConfig, domain: SyncDomain, document: SyncDocument) {
        app.webDavConfig.withBinding(binding.generation) {
            if(domain == SyncDomain.KEYWORDS) app.keywords.applyRemote { library ->
                library.copy(syncReplica = reserveWebDavClock(library.syncReplica, document))
            } else app.store.updateFromWebDav { state ->
                state.copy(syncReplica = reserveWebDavClock(state.syncReplica, document))
            }
        }
        if(domain == SyncDomain.KEYWORDS) app.keywords.flush() else app.store.flush()
    }

    private fun replaceAndRebase(replica: SyncReplica, document: SyncDocument, captured: SyncDocument): SyncReplica {
        val current = replica.documents[document.domain]
        var next = replica.copy(clock = maxOf(replica.clock, document.context[replica.deviceId] ?: 0),
            documents = replica.documents + (document.domain to document))
        if(current != null && current != captured) {
            val base = WebDavMerge.materialize(document)
            val adjusted = rebaseDocumentChanges(base, captured, current)
            next = next.track(mapOf(document.domain to base), mapOf(document.domain to adjusted), setOf(document.domain))
        }
        return next
    }

    fun hasPending(): Boolean = pendingDomains(app.webDavConfig.config.value).isNotEmpty()

    private fun pendingDomains(binding: WebDavConfig): Set<SyncDomain> =
        binding.selected.filterTo(mutableSetOf()) { domain ->
            val replica = if(domain == SyncDomain.KEYWORDS) app.keywords.state.value.syncReplica else app.store.state.value.syncReplica
            val local = replica.documents[domain] ?: return@filterTo true
            runtime.cached[domain]?.document != local
        }

    fun refreshPendingStatus() {
        val binding = app.webDavConfig.config.value
        val cache = runtime
        val pending = binding.selected.associateWith { domain ->
            val replica = if(domain == SyncDomain.KEYWORDS) app.keywords.state.value.syncReplica else app.store.state.value.syncReplica
            val local = replica.documents[domain]
            val remote = cache.cached[domain]?.document
            val count = if(local == null) projection(domain, binding.syncDevicePreferences).size else
                (local.records.keys + remote?.records.orEmpty().keys).count { local.records[it] != remote?.records?.get(it) }
            (local == null || local != remote) to count
        }
        mutableStatus.update { state -> state.copy(domains = pending.mapValues { (domain, counts) ->
            val old = state.domains[domain] ?: WebDavDomainStatus(lastSuccessAt = cache.cached[domain]?.lastSuccessAt ?: 0)
            old.copy(pending = counts.first, pendingCount = counts.second)
        }) }
        refreshConflicts()
    }

    private fun refreshConflicts() {
        val selected = app.webDavConfig.config.value.selected
        val conflicts = app.store.state.value.syncReplica.documents.filterKeys { it in selected }.values.flatMap(WebDavMerge::conflicts)
        mutableStatus.update { it.copy(conflicts = conflicts) }
    }

    private suspend fun <T> running(recordSuccess: Boolean = true, block: suspend () -> T): T {
        mutableStatus.update { it.copy(running = true, lastAttemptAt = System.currentTimeMillis(), error = null,
            domains = it.domains.mapValues { (_, value) -> value.copy(retryable = false) }) }
        try {
            return block().also { if(recordSuccess) mutableStatus.update { it.copy(lastSuccessAt = System.currentTimeMillis()) } }
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(error: Exception) {
            mutableStatus.update { it.copy(error = error.message ?: "同步未完成，请稍后重试",
                recovery = (error as? WebDavRecoveryRequiredException)?.recovery ?: it.recovery) }
            throw error
        } finally { mutableStatus.update { it.copy(running = false) } }
    }

    private fun readRuntime(): WebDavRuntimeState = try {
        if(!file.exists() && !File(file.path + ".bak").exists()) WebDavRuntimeState()
        else atomic.openRead().use { stream ->
            require(stream.channel.size() <= 128L * 1024 * 1024) { "同步缓存超过限制" }
            wireJson.decodeFromString<WebDavRuntimeState>(checkedWebDavJson(stream.readBytes(), 128 * 1024 * 1024)).also { state ->
                require(state.datasetId == null || WebDavMerge.validId(state.datasetId))
                require(state.joined.all { it in state.cached } && (state.cached.isEmpty() || state.datasetId != null))
                state.cached.forEach { (domain, cached) ->
                    require(domain == cached.document.domain && cached.lastSuccessAt >= 0)
                    WebDavClient.requireStrongEtag(cached.etag)
                    WebDavMerge.validate(cached.document, state.datasetId)
                }
            }
        }
    } catch(_: Exception) {
        runtimeError = "同步状态无法读取，请先预览并重新确认同步内容"
        WebDavRuntimeState()
    }

    private fun writeRuntime() {
        val bytes = wireJson.encodeToString(runtime).toByteArray(Charsets.UTF_8)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch(error: Exception) { atomic.failWrite(stream); throw error }
    }

    private companion object { const val MANIFEST = "manifest.json" }
}
