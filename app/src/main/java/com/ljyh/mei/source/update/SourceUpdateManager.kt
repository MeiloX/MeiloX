package com.ljyh.mei.source.update

import android.content.Context
import android.net.Uri
import com.ljyh.mei.R
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.core.ProviderRegistry
import com.ljyh.mei.source.script.InstalledPlugin
import com.ljyh.mei.source.script.PluginUpdateSnapshot
import com.ljyh.mei.source.script.PreparedPlugin
import com.ljyh.mei.source.script.ScriptPluginManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PreparedSourceUpdate internal constructor(
    val plugin: PreparedPlugin,
    val entry: SourceMarketEntry,
    val reviewReasons: List<SourceUpdateReviewReason>,
    internal val expected: PluginUpdateSnapshot?,
)

/** Startup checks never participate in provider health or block registry initialization. */
@Singleton
class SourceUpdateManager internal constructor(
    private val context: Context,
    private val plugins: ScriptPluginManager,
    private val registry: ProviderRegistry,
    private val transport: SourceUpdateTransport,
    backgroundScope: CoroutineScope? = null,
    private val compatibilityMarket: CompatibilityMarket = CompatibilityMarket(context),
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        plugins: ScriptPluginManager,
        registry: ProviderRegistry,
        transport: SourceMarketTransport,
        compatibilityMarket: CompatibilityMarket,
    ) : this(context, plugins, registry, transport, compatibilityMarket = compatibilityMarket, backgroundScope = null)

    private val scope = backgroundScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(SourceUpdateState())
    val state: StateFlow<SourceUpdateState> = mutableState.asStateFlow()
    private val settings by lazy { context.getSharedPreferences("source-update-settings", Context.MODE_PRIVATE) }
    private var settingsLoaded = false
    private val requestedAutoUpdates = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
    private var entries = emptyList<SourceMarketEntry>()
    private var cachedJson: String? = null
    private var etag: String? = null
    private val reviewNeeded = mutableSetOf<String>()

    init {
        scope.launch {
            plugins.installed.collect {
                mutex.withLock {
                    try { publishItems() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* A damaged package cannot fail other update entries. */ }
                }
            }
        }
    }

    suspend fun checkOnStartup(): List<SourcePluginUpdate> = withContext(Dispatchers.IO) {
        registry.awaitInitialized()
        val installed = plugins.installed.value
        val compatibility = async {
            if (installed.none { it.manifest.compatibility != null }) return@async emptyList()
            val bundled = try { compatibilityMarket.bundled() } catch (_: Exception) { emptyList() }
            val remote = try { compatibilityMarket.refresh().orEmpty() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emptyList() }
            compatibilityMarketItems(bundled, remote, plugins.installed.value).mapNotNull { item ->
                val previous = item.installedVersion ?: return@mapNotNull null
                if (item.updateAvailable) SourcePluginUpdate(item.entry.name, previous, item.entry.version, automaticallyUpdated = false)
                else null
            }
        }
        val native = if (installed.any { it.manifest.compatibility == null }) checkNow() else emptyList()
        native + compatibility.await()
    }

    suspend fun setAutoUpdates(enabled: Boolean) {
        // Turning updates off takes effect even while a download is being cancelled or completed.
        requestedAutoUpdates.set(enabled)
        mutableState.update { it.copy(autoUpdates = enabled) }
        withContext(Dispatchers.IO) {
            mutex.withLock {
                settings.edit().putBoolean("autoUpdates", enabled).commit()
                settingsLoaded = true
            }
        }
    }

    /** Browsing the market refreshes data only, including when automatic updates are enabled. */
    suspend fun refreshMarket(): Boolean = withContext(Dispatchers.IO) {
        registry.awaitInitialized()
        mutex.withLock {
            loadSettings()
            mutableState.update { it.copy(checking = true, message = null) }
            try {
                val unresolved = fetchIndex()
                publishItems()
                if (unresolved.isNotEmpty()) mutableState.update { it.copy(message = context.getString(R.string.source_market_partial)) }
                unresolved.isEmpty()
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.update { it.copy(message = "暂时无法连接音源市场") }
                false
            }
            finally { mutableState.update { it.copy(checking = false, busyPackageId = null) } }
        }
    }

    suspend fun checkNow(): List<SourcePluginUpdate> = withContext(Dispatchers.IO) {
        registry.awaitInitialized()
        mutex.withLock {
            loadSettings()
            mutableState.update { it.copy(checking = true, message = null) }
            val updated = mutableMapOf<String, SourcePluginUpdate>()
            var failures = 0
            var deferred = 0
            reviewNeeded.clear()
            try {
                val unresolved = fetchIndex().toMutableSet()
                failures += unresolved.size
                // Iterate only the installed snapshot. A removed source is never reinstalled by a check.
                val installedIds = plugins.installed.value.map { it.manifest.id }.toSet()
                entries.filter { it.packageId in installedIds && it.packageId !in unresolved }.forEach { entry ->
                    var prepared: PreparedSourceUpdate? = null
                    try {
                        val current = plugins.updateSnapshot(entry.packageId) ?: return@forEach
                        if (current.manifest.providerId != entry.providerId) return@forEach
                        val oldVersion = SourceVersion.parse(current.manifest.version) ?: return@forEach
                        val version = SourceVersion.parse(entry.version) ?: return@forEach
                        if (version < oldVersion) return@forEach
                        if (version == oldVersion) {
                            // Existing file imports become trusted only after exact same-version payload verification.
                            if (current.repository == null) {
                                prepared = prepare(entry, current)
                                if (prepared!!.plugin.fingerprint == current.fingerprint && entry.matches(current.manifest)) {
                                    plugins.adoptMarketOrigin(entry.packageId, current, entry.repository)
                                }
                            }
                            return@forEach
                        }
                        if (!mutableState.value.autoUpdates || entry.canonicalOrigin(current.repository) != entry.repository) {
                            reviewNeeded += entry.packageId
                            return@forEach
                        }
                        prepared = prepare(entry, current)
                        if (prepared!!.reviewReasons.isNotEmpty()) {
                            reviewNeeded += entry.packageId
                        } else if (mutableState.value.autoUpdates) {
                            val installed = plugins.installMarket(prepared!!.plugin, entry, current, automatic = true,
                                authorized = { mutableState.value.autoUpdates })
                            updated[entry.packageId] = SourcePluginUpdate(entry.name, current.manifest.version,
                                installed.manifest.version, automaticallyUpdated = true)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: SourceUpdateDeferred) { deferred++ }
                    catch (_: Exception) {
                        unresolved += entry.packageId
                        failures++
                    }
                    finally {
                        prepared?.let { value -> withContext(NonCancellable) { plugins.discardPrepared(value.plugin) } }
                        mutableState.update { it.copy(busyPackageId = null) }
                    }
                }
                publishItems()
                mutableState.update { it.copy(message = when {
                    failures > 0 -> "部分音源暂时无法检查更新"
                    updated.isNotEmpty() -> "已更新 ${updated.size} 个音源"
                    reviewNeeded.isNotEmpty() -> "有 ${reviewNeeded.size} 个更新需要确认"
                    deferred > 0 -> "正在使用的音源将在下次启动时更新"
                    else -> "已是最新版本"
                }) }
                mutableState.value.items.mapNotNull { item ->
                    val previous = item.installedVersion ?: return@mapNotNull null
                    if (item.entry.packageId in unresolved) return@mapNotNull null
                    updated[item.entry.packageId]?.takeIf { it.version == previous } ?:
                        if (item.updateAvailable) SourcePluginUpdate(item.entry.name, previous, item.entry.version, automaticallyUpdated = false)
                        else null
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.update { it.copy(message = "暂时无法连接音源市场") }
                updated.values.toList()
            }
            finally { mutableState.update { it.copy(checking = false, busyPackageId = null) } }
        }
    }

    suspend fun prepareMarketInstall(packageId: String): PreparedSourceUpdate = prepareForReview(packageId, update = false)
    suspend fun prepareUpdate(packageId: String): PreparedSourceUpdate = prepareForReview(packageId, update = true)

    private suspend fun prepareForReview(packageId: String, update: Boolean): PreparedSourceUpdate {
        var prepared: PreparedSourceUpdate? = null
        try { return withContext(Dispatchers.IO) {
            registry.awaitInitialized()
            mutex.withLock {
                val entry = entries.firstOrNull { it.packageId == packageId } ?: invalid("请先刷新音源市场")
                val current = plugins.updateSnapshot(packageId)
                if (update != (current != null)) invalid("音源安装状态已改变，请重试")
                if (current != null && !isNewer(entry.version, current.manifest.version)) invalid("音源已是最新版本")
                try { prepare(entry, current).also { prepared = it } }
                finally { mutableState.update { it.copy(busyPackageId = null) } }
            }
        } } catch (error: Exception) {
            prepared?.let { withContext(NonCancellable) { plugins.discardPrepared(it.plugin) } }
            if (error is CancellationException) throw error
            throw SourceException(SourceErrorCode.INVALID_RESPONSE, "无法准备音源安装包，请刷新后重试")
        }
    }

    suspend fun installReviewed(prepared: PreparedSourceUpdate): InstalledPlugin = withContext(Dispatchers.IO) {
        mutex.withLock {
            mutableState.update { it.copy(busyPackageId = prepared.entry.packageId) }
            try {
                plugins.installMarket(prepared.plugin, prepared.entry, prepared.expected, automatic = false).also {
                    reviewNeeded.remove(prepared.entry.packageId)
                    publishItems()
                }
            } finally { mutableState.update { it.copy(busyPackageId = null) } }
        }
    }

    suspend fun discardPrepared(prepared: PreparedSourceUpdate) { plugins.discardPrepared(prepared.plugin) }

    private suspend fun prepare(entry: SourceMarketEntry, current: PluginUpdateSnapshot?): PreparedSourceUpdate {
        mutableState.update { it.copy(busyPackageId = entry.packageId) }
        val archive = File.createTempFile("source-download-", ".meisource", context.cacheDir)
        var inspected: PreparedPlugin? = null
        try {
            transport.download(entry, archive)
            // A fake or future transport cannot bypass package authentication.
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            archive.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > SourceMarketTransport.MAX_PACKAGE_BYTES) invalid("音源安装包过大")
                    digest.update(buffer, 0, count)
                }
            }
            if (digest.digest().joinToString("") { "%02x".format(it) } != entry.sha256) invalid("音源安装包校验失败")
            val plugin = plugins.inspect(Uri.fromFile(archive)).also { inspected = it }
            if (!entry.matches(plugin.manifest)) invalid("音源安装包与市场信息不符")
            return PreparedSourceUpdate(plugin, entry,
                sourceUpdateReviewReasons(current?.manifest, plugin.manifest, entry.canonicalOrigin(current?.repository), entry.repository).toList(), current)
        } catch (error: Exception) {
            inspected?.let { withContext(NonCancellable) { plugins.discardPrepared(it) } }
            throw error
        } finally { archive.delete() }
    }

    private suspend fun fetchIndex(): Set<String> {
        val response = transport.index(etag)
        val json = response.json ?: cachedJson ?: invalid("音源市场缓存已失效")
        val refreshed = resolveMarketListings(SourceMarketIndex.parse(json), entries, transport::resolve)
        entries = refreshed.entries
        cachedJson = json
        etag = response.etag
        return refreshed.unresolved
    }

    private suspend fun publishItems() {
        val values = entries.map { entry ->
            val current = try { plugins.updateSnapshot(entry.packageId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            val update = current != null && current.manifest.providerId == entry.providerId &&
                isNewer(entry.version, current.manifest.version)
            val trusted = entry.canonicalOrigin(current?.repository) == entry.repository
            SourceMarketItem(entry, current?.manifest?.version, current?.enabled, update,
                trusted, update && (entry.packageId in reviewNeeded || !trusted))
        }
        mutableState.update { it.copy(items = values) }
    }

    private fun loadSettings() {
        if (!settingsLoaded) {
            mutableState.update { it.copy(autoUpdates = requestedAutoUpdates.get() ?: settings.getBoolean("autoUpdates", true)) }
            settingsLoaded = true
        }
    }

    private fun invalid(message: String): Nothing = throw SourceException(SourceErrorCode.INVALID_RESPONSE, message)
}

internal data class ResolvedMarketListings(val entries: List<SourceMarketEntry>, val unresolved: Set<String>)

internal suspend fun resolveMarketListings(
    listings: List<SourceMarketListing>,
    previous: List<SourceMarketEntry>,
    resolve: suspend (SourceMarketListing) -> SourceMarketEntry,
): ResolvedMarketListings = coroutineScope {
    val permits = Semaphore(4)
    val results = listings.map { listing ->
        async {
            permits.withPermit {
                try { Result.success(resolve(listing)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Result.failure(error) }
            }
        }
    }.awaitAll()

    val cached = previous.associateBy { it.packageId }
    val unresolved = mutableSetOf<String>()
    val entries = listings.zip(results).mapNotNull { (listing, result) ->
        result.getOrNull() ?: run {
            unresolved += listing.packageId
            val failure = result.exceptionOrNull() as? SourceException
            if (failure?.code != SourceErrorCode.NETWORK && failure?.code != SourceErrorCode.TIMEOUT) return@mapNotNull null
            cached[listing.packageId]?.takeIf {
                listing.providerId == it.providerId && listing.repository == it.repository &&
                    listing.packagePath == it.packagePath && listing.apiVersion == it.apiVersion
            }?.copy(name = listing.name)
        }
    }
    ResolvedMarketListings(entries, unresolved)
}

internal fun isNewer(next: String, current: String): Boolean {
    val from = SourceVersion.parse(current) ?: return false
    val to = SourceVersion.parse(next) ?: return false
    return to > from
}
