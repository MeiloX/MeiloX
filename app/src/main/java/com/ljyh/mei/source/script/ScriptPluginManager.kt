package com.ljyh.mei.source.script

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ljyh.mei.source.api.ProviderInstanceId
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.api.SourceStatus
import com.ljyh.mei.source.core.ProviderRegistry
import com.ljyh.mei.source.update.CompatibilityMarketEntry
import com.ljyh.mei.source.update.isNewer
import com.ljyh.mei.source.update.compatibilityFileId
import com.ljyh.mei.source.update.compatibilityFileUpdateId
import com.ljyh.mei.source.update.manualPackageId
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.BufferedInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okio.buffer
import okio.source

data class InstalledPlugin(val manifest: PluginManifest, val enabled: Boolean, val status: SourceStatus,
    val diagnostic: String?, val requestedDomain: String?, internal val revision: String)
class PreparedPlugin internal constructor(
    val manifest: PluginManifest,
    internal val directory: File,
    internal val fingerprint: String,
    internal val compatibilityReview: CompatibilityInstallReview? = null,
) {
    val marketRepository: String? get() = compatibilityReview?.repository
}

internal data class PluginUpdateSnapshot(val manifest: PluginManifest, val fingerprint: String, val repository: String?, val enabled: Boolean)
internal data class CompatibilityInstallReview(val expected: PluginUpdateSnapshot?, val repository: String?)
@Keep
private data class PluginMarketOrigin(val repository: String, val fingerprint: String)

@Keep
private data class PluginInstallState(val enabled: Boolean = true, val revision: Long = 0)

/** Package replacement and its reviewed origin share one rollback boundary. */
@Singleton
class ScriptPluginManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: ProviderRegistry,
    private val runtime: ScriptRuntime,
    private val featureExecutor: com.ljyh.mei.source.core.SourceFeatureExecutor =
        com.ljyh.mei.source.core.SourceFeatureExecutor(com.ljyh.mei.source.core.SourceRouter(registry)),
    private val platformEffects: com.ljyh.mei.source.platform.SourcePlatformEffects =
        com.ljyh.mei.source.platform.SourcePlatformEffects(context, registry),
    private val artworkStore: SourceArtworkStore? = null,
    private val playbackUpdates: com.ljyh.mei.source.update.SourceUpdatePlaybackGuard = com.ljyh.mei.source.update.SourceUpdatePlaybackGuard(),
) {
    private val root = File(context.filesDir, "music-sources").apply { mkdirs() }
    private val secrets = PluginSecretStore(context)
    private val mutex get() = installationMutex
    private val revisions = java.util.concurrent.ConcurrentHashMap<String, PluginRevisionGate>()
    private val providers = java.util.concurrent.ConcurrentHashMap<String, ScriptMusicProvider>()
    private val configurations = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    private val stores = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    private val diagnostics = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val mutableInstalled = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val installed: StateFlow<List<InstalledPlugin>> = mutableInstalled
    private val mutableStartupError = MutableStateFlow<String?>(null)
    val startupError: StateFlow<String?> = mutableStartupError
    val runtimeSupported: Boolean get() = runtime.supported

    suspend fun loadInstalled() = withContext(Dispatchers.IO) { mutex.withLock {
        try {
        val incomplete = PluginInstallJournal.recover(root) { fingerprint(it, installed = true) }
        try {
            BundledSourceProvisioner.ensure(context, root, secrets)
            mutableStartupError.value = if (incomplete.isEmpty()) null else "部分音源安装未完成，可重新导入"
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutableStartupError.value = "默认音源恢复失败，可重试或从文件导入"
        }
        root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') && it.name !in incomplete }.forEach { directory ->
            if (providers.containsKey(directory.name)) return@forEach
            try {
                val manifest = PluginManifest.parse(File(directory, "manifest.json").readText())
                check(manifest.id == directory.name && File(directory, manifest.entry).isFile)
                val state = readState(directory)
                changeSource(manifest.id) { gate, revision ->
                    configurations[manifest.id] = secrets.read(manifest.id, "config")
                    stores[manifest.id] = secrets.read(manifest.id, "storage")
                    register(manifest, directory, state, gate, revision)
                }
            } catch (_: Exception) {
                // A damaged installation is retained for removal/re-import. No unrelated state is changed.
                runCatching {
                    val manifest = PluginManifest.parse(File(directory, "manifest.json").readText())
                    changeSource(manifest.id) { gate, revision ->
                        configurations[manifest.id] = emptyMap()
                        stores[manifest.id] = emptyMap()
                        diagnostics[manifest.id] = "Source configuration could not be restored"
                        register(manifest, directory, PluginInstallState(enabled = false), gate, revision)
                    }
                }
            }
        }
        publish()
        } finally { registry.completeInitialization() }
    } }

    suspend fun inspect(uri: Uri): PreparedPlugin {
        return prepareImport {
            val input = context.contentResolver.openInputStream(uri) ?: invalid("Unable to open source package")
            input.use(::inspectPackage)
        }
    }

    suspend fun inspectFile(uri: Uri, replacingId: String? = null): PreparedPlugin = prepareImport {
        val input = context.contentResolver.openInputStream(uri)
            ?: invalid("Unable to open source script")
        BufferedInputStream(input).use {
            val name = if (uri.scheme == "file") uri.lastPathSegment else runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()
            inspectSourceInput(it, replacingId, name)
        }
    }

    suspend fun inspectLink(
        url: String,
        replacingId: String? = null,
    ): PreparedPlugin = prepareImport {
        val file = File.createTempFile("source-link-", ".download", context.cacheDir)
        try {
            try { withTimeout(20_000) { downloadSourceLink(url.trim(), file) } }
            catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw SourceException(SourceErrorCode.TIMEOUT)
            }
            BufferedInputStream(file.inputStream()).use {
                inspectSourceInput(it, replacingId, url.trim().toHttpUrlOrNull()?.pathSegments?.lastOrNull())
            }
        } finally { file.delete() }
    }

    private suspend fun inspectSourceInput(input: BufferedInputStream, replacingId: String?, filename: String?): PreparedPlugin {
        input.mark(4)
        val archive = hasZipHeader(input)
        input.reset()
        if (!archive) return inspectCompatibilityScript(readCompatibilityScript(input), replacingId,
            filename = filename?.takeIf { it.endsWith(".js", ignoreCase = true) }?.dropLast(3)?.trim()?.take(100))
        val prepared = inspectPackage(input)
        if (replacingId != null && prepared.manifest.id != replacingId) {
            prepared.directory.deleteRecursively()
            invalid("The source package belongs to a different installation")
        }
        return prepared
    }

    suspend fun withCompatibilityDomains(prepared: PreparedPlugin, domains: List<String>): PreparedPlugin = prepareImport {
        val manifest = prepared.manifest
        if (manifest.compatibility == null || prepared.fingerprint != fingerprint(prepared.directory)) {
            invalid("Source package changed after review")
        }
        val grants = approvedCompatibilityDomains(domains)
        if (grants == manifest.permissions.network) return@prepareImport prepared
        val updated = manifest.copy(permissions = manifest.permissions.copy(network = grants))
        writeCompatibilityImport(updated, File(prepared.directory, "index.js").readText(), prepared.compatibilityReview)
    }

    private suspend fun prepareImport(prepare: suspend () -> PreparedPlugin): PreparedPlugin {
        var completed: PreparedPlugin? = null
        try {
            return withContext(Dispatchers.IO) { prepare().also { completed = it } }
        } catch (error: Exception) {
            completed?.directory?.deleteRecursively()
            throw error
        }
    }

    private fun inspectPackage(input: InputStream): PreparedPlugin {
        val directory = File(context.cacheDir, "source-import/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            input.use { raw ->
                ZipInputStream(raw).use { zip ->
                    var total = 0L
                    var entries = 0
                    val names = mutableSetOf<String>()
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (++entries > 64) invalid("Source package has too many files")
                        val name = entry.name
                        val limit = packageFileLimit(name)
                        if (!names.add(name) || entry.isDirectory) invalid("Source package contains an unsupported path")
                        val target = File(directory, name).canonicalFile
                        if (!target.path.startsWith(directory.canonicalPath + File.separator)) invalid("Invalid source package path")
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var fileBytes = 0
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                fileBytes += read
                                total += read
                                if (fileBytes > limit || total > 8 * 1024 * 1024) invalid("Source package exceeds size limits")
                                output.write(buffer, 0, read)
                            }
                        }
                        if (name == "LICENSE" || name == "NOTICE") validateLegalText(target.readBytes())
                    }
                }
            }
            val manifestFile = File(directory, "manifest.json")
            if (!manifestFile.isFile || !File(directory, "index.js").isFile) invalid("Source package needs manifest.json and index.js")
            val manifest = PluginManifest.parse(manifestFile.readText())
            if (manifest.compatibility != null) invalid("Import the original compatibility script")
            return PreparedPlugin(manifest, directory, fingerprint(directory))
        } catch (error: Exception) {
            directory.deleteRecursively()
            if (error is SourceException || error is kotlinx.coroutines.CancellationException) throw error
            invalid("Unable to read source package")
        }
    }

    internal suspend fun inspectMarketScript(
        entry: CompatibilityMarketEntry,
        replacing: PluginManifest? = null,
        download: suspend (File) -> Unit,
    ): PreparedPlugin = prepareImport {
        val file = File.createTempFile("source-market-", ".js", context.cacheDir)
        try {
            download(file)
            val source = file.inputStream().use(::readCompatibilityScriptBytes)
            if (MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it) } != entry.sha256) {
                throw SourceException(SourceErrorCode.INVALID_RESPONSE, "Source script checksum did not match")
            }
            val decoded = decodeCompatibilityScript(source)
            val script = decoded.removePrefix("\uFEFF")
            val digest = if (script.length == decoded.length) entry.sha256 else scriptSha256(script)
            inspectCompatibilityScript(script, replacingId = replacing?.id, market = entry,
                expectedMarketInstall = replacing, digest = digest)
        } finally { file.delete() }
    }

    private suspend fun inspectCompatibilityScript(
        script: String, replacingId: String?, market: CompatibilityMarketEntry? = null, filename: String? = null,
        expectedMarketInstall: PluginManifest? = null, digest: String = scriptSha256(script),
    ): PreparedPlugin {
        val (review, unchangedName) = mutex.withLock {
            val selected = when {
                replacingId != null -> providers[replacingId]?.manifest?.takeIf {
                    it.compatibility != null && (market == null || market.format == it.compatibility)
                }
                    ?: invalid("Select an installed compatibility source")
                market != null -> {
                    val manifests = providers.values.associateBy { it.manifest.compatibilityUpdateId }
                    (manifests[market.packageId] ?: manifests[market.manualPackageId] ?:
                        manifests[compatibilityFileId(market.format, digest)])?.manifest
                }
                else -> matchingCompatibilityScriptLocked(script)
            }
            if (expectedMarketInstall != null && selected != expectedMarketInstall) {
                invalid("The installed source changed while preparing its update")
            }
            val identical = selected != null && (replacingId == null && market == null ||
                installedScriptMatches(selected, script, script.toByteArray(Charsets.UTF_8).size.toLong()))
            CompatibilityInstallReview(selected?.let { snapshotLocked(it.id) }, market?.repository) to
                selected?.name?.takeIf { identical }
        }
        val previous = review.expected?.manifest
        if (market != null && previous != null && !isNewer(market.version, previous.version)) {
            throw SourceException(SourceErrorCode.INVALID_RESPONSE, "The source is already up to date")
        }
        val pinnedFormat = market?.format ?: previous?.compatibility
        val firstFormat = pinnedFormat ?: if (LX_MARKERS.containsMatchIn(script)) "lx" else "musicfree"
        val scriptName = unchangedName ?: filename ?: market?.path?.substringAfterLast('/')?.substringBeforeLast('.')
        val params = JsonObject().apply { scriptName?.let { addProperty("scriptName", it) } }
        suspend fun inspect(format: String) = runtime.invokeCompatibility(script, format, "inspect", params, emptyMap())
            .takeIf { it.isJsonObject }?.asJsonObject ?: invalid("Invalid compatibility script")
        val (format, info) = try {
            firstFormat to inspect(firstFormat)
        } catch (error: SourceException) {
            // Computed globals and bundled CommonJS modules need a protocol probe, not a literal marker.
            if (pinnedFormat != null || error.code !in setOf(SourceErrorCode.PLUGIN, SourceErrorCode.INVALID_RESPONSE,
                    SourceErrorCode.UNSUPPORTED)) throw error
            val other = if (firstFormat == "lx") "musicfree" else "lx"
            other to inspect(other)
        }
        val lx = format == "lx"
        val platforms = if (lx) {
            info.getAsJsonArray("platforms")?.map { it.asString }?.intersect(LxPlaybackCatalog.platforms)
                ?.takeIf { it.isNotEmpty() }
                ?: throw SourceException(SourceErrorCode.UNSUPPORTED, "No supported LX playback platforms")
        } else {
            if (info.get("search")?.asBoolean != true || info.get("playback")?.asBoolean != true) {
                throw SourceException(SourceErrorCode.UNSUPPORTED, "The MusicFree plugin must support song search and playback")
            }
            null
        }
        val fileId = compatibilityFileId(format, digest)
        val key = previous?.id ?: newCompatibilityId(format, fileId, market)
        val priorOrigin = previous?.compatibilityUpdateId?.takeIf { unchangedName != null && it != fileId }
        val config = info.get("config")?.takeIf { it.isJsonArray }?.asJsonArray?.map { field ->
            Gson().fromJson(field, PluginConfigField::class.java)
        }.orEmpty()
        val grants = if (previous != null && market == null && replacingId == null) previous.permissions.network
            else (previous?.permissions?.network.orEmpty() + inferredCompatibilityDomains(script)).distinct().take(32)
        val manifest = PluginManifest(
            id = key,
            name = info.get("name")?.asString?.trim()?.takeIf(String::isNotBlank)?.take(100)
                ?: if (lx) "LX Music" else "MusicFree",
            author = info.get("author")?.asString?.trim()?.takeIf(String::isNotBlank)?.take(100) ?: "—",
            version = info.get("version")?.asString?.trim()?.takeIf(String::isNotBlank)?.take(50) ?: "1.0.0",
            apiVersion = 2,
            capabilities = if (lx) setOf("playback.resolve") else setOf("catalog.search", "catalog.track", "playback.resolve"),
            permissions = PluginPermissions(network = grants),
            config = config,
            compatibility = format,
            compatibilityPlatforms = platforms,
            compatibilityUpdateId = market?.packageId ?: priorOrigin ?: compatibilityFileUpdateId(format, digest),
        )
        if (previous != null && (previous.name != manifest.name || previous.author != manifest.author)) {
            throw SourceException(SourceErrorCode.INVALID_RESPONSE, "The script belongs to a different source")
        }
        if (market != null && (market.name != manifest.name || market.version != manifest.version)) {
            throw SourceException(SourceErrorCode.INVALID_RESPONSE, "Source script metadata did not match its market entry")
        }
        return writeCompatibilityImport(manifest, script, review)
    }

    private fun matchingCompatibilityScriptLocked(script: String, format: String? = null): PluginManifest? {
        val length = script.toByteArray(Charsets.UTF_8).size.toLong()
        return providers.values.asSequence().map { it.manifest }.filter {
            it.compatibility != null && (format == null || it.compatibility == format)
        }.firstOrNull { manifest -> installedScriptMatches(manifest, script, length) }
    }

    private fun newCompatibilityId(format: String, fileId: String, market: CompatibilityMarketEntry?): String {
        fun available(id: String) = !providers.containsKey(id) && !File(root, "$id/manifest.json").exists()
        return listOfNotNull(market?.packageId, fileId).firstOrNull(::available)
            ?: generateSequence { compatibilityFileId(format, scriptSha256(UUID.randomUUID().toString())) }.first(::available)
    }

    private fun installedScriptMatches(manifest: PluginManifest, script: String, length: Long): Boolean {
        val installed = File(root, "${manifest.id}/${manifest.entry}")
        return installed.length() == length && runCatching { installed.inputStream().use(::readCompatibilityScript) == script }.getOrDefault(false)
    }

    private fun writeCompatibilityImport(
        manifest: PluginManifest, script: String, review: CompatibilityInstallReview? = null,
    ): PreparedPlugin {
        val json = Gson().toJson(manifest)
        PluginManifest.parse(json)
        val directory = File(context.cacheDir, "source-import/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            File(directory, "manifest.json").writeText(json)
            File(directory, "index.js").writeText(script)
            return PreparedPlugin(manifest, directory, fingerprint(directory), review)
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    private suspend fun downloadSourceLink(value: String, file: File) {
        var url = ScriptNetworkPolicy.compatibilityUrl(value).toHttpUrlOrNull()?.takeIf { value.length <= 8192 && it.isHttps }
            ?: throw SourceException(SourceErrorCode.INVALID_RESPONSE, "Use an HTTPS source link")
        repeat(5) { index ->
            val policy = ScriptNetworkPolicy(listOf(url.host))
            policy.validateUrl(url.toString())
            val client = policy.client.newBuilder().followRedirects(false).followSslRedirects(false).build()
            val next = policy.exchange(client.newCall(Request.Builder().url(url).build()), {}) { response, guard ->
                if (response.isRedirect) {
                    if (index == 4) throw SourceException(SourceErrorCode.NETWORK)
                    return@exchange response.header("Location")?.let(url::resolve)?.takeIf { it.isHttps }
                        ?: throw SourceException(SourceErrorCode.NETWORK)
                }
                if (!response.isSuccessful) throw SourceException(SourceErrorCode.NETWORK)
                val body = response.body
                val limit = 8 * 1024 * 1024
                if (body.contentLength() > limit) invalid("Source download exceeds size limit")
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            guard()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > limit) invalid("Source download exceeds size limit")
                            output.write(buffer, 0, count)
                        }
                    }
                }
                null
            }
            if (next == null) return
            url = next
        }
    }

    suspend fun install(prepared: PreparedPlugin, approved: Boolean): InstalledPlugin = withContext(Dispatchers.IO) { mutex.withLock {
        if (!approved) invalid("Source permissions were not approved")
        prepared.compatibilityReview?.let { review ->
            val manifest = prepared.manifest
            val current = snapshotLocked(manifest.id)
            val expected = review.expected
            if (current == null && expected != null || current != null && expected == null ||
                current != null && expected != null && !samePayload(current, expected)) {
                invalid("Source changed after review")
            }
            if (current == null) {
                val script = File(prepared.directory, manifest.entry).inputStream().use(::readCompatibilityScript)
                if (matchingCompatibilityScriptLocked(script, manifest.compatibility) != null) {
                    invalid("The script was installed while preparing its review")
                }
            }
        }
        installLocked(prepared, repository = null)
    } }

    internal suspend fun updateSnapshot(id: String): PluginUpdateSnapshot? = withContext(Dispatchers.IO) { mutex.withLock { snapshotLocked(id) } }

    internal suspend fun adoptMarketOrigin(id: String, expected: PluginUpdateSnapshot, repository: String): Boolean =
        withContext(Dispatchers.IO) { mutex.withLock {
            val current = snapshotLocked(id) ?: return@withLock false
            if (!samePayload(current, expected) || current.repository != null) return@withLock false
            writeMarketOrigin(File(root, id), repository, current.fingerprint)
            true
        } }

    internal suspend fun installMarket(
        prepared: PreparedPlugin,
        entry: com.ljyh.mei.source.update.SourceMarketEntry,
        expected: PluginUpdateSnapshot?,
        automatic: Boolean,
        authorized: () -> Boolean = { true },
    ): InstalledPlugin = withContext(Dispatchers.IO) { mutex.withLock {
        com.ljyh.mei.source.update.SourceMarketIndex.validate(entry)
        if (!entry.matches(prepared.manifest)) invalid("Market package metadata changed")
        val current = snapshotLocked(entry.packageId)
        if (current == null && expected != null || current != null && expected == null ||
            current != null && expected != null && !samePayload(current, expected)) invalid("Source changed while preparing its update")
        if (current != null) {
            val oldVersion = com.ljyh.mei.source.update.SourceVersion.parse(current.manifest.version)
            val nextVersion = com.ljyh.mei.source.update.SourceVersion.parse(entry.version)
            if (oldVersion == null || nextVersion == null || nextVersion <= oldVersion) invalid("Source update is not newer")
        }
        if (automatic && (current == null || com.ljyh.mei.source.update.sourceUpdateReviewReasons(
                current.manifest, prepared.manifest, entry.canonicalOrigin(current.repository), entry.repository).isNotEmpty())) invalid("Source update needs permission review")
        if (automatic) playbackUpdates.installIfIdle(entry.providerId) {
            if (!authorized()) throw com.ljyh.mei.source.update.SourceUpdateDeferred()
            installLocked(prepared, entry.repository)
        }
        else installLocked(prepared, entry.repository)
    } }

    suspend fun discardPrepared(prepared: PreparedPlugin) = withContext(Dispatchers.IO) { mutex.withLock {
        val parent = File(context.cacheDir, "source-import").canonicalFile
        val directory = prepared.directory.canonicalFile
        if (directory.parentFile == parent && runCatching { UUID.fromString(directory.name) }.isSuccess) directory.deleteRecursively()
        Unit
    } }

    private fun samePayload(a: PluginUpdateSnapshot, b: PluginUpdateSnapshot): Boolean =
        a.fingerprint == b.fingerprint && a.manifest.id == b.manifest.id && a.manifest.providerId == b.manifest.providerId && a.repository == b.repository

    private fun snapshotLocked(id: String): PluginUpdateSnapshot? {
        val provider = providers[id] ?: return null
        val directory = File(root, id)
        val fingerprint = fingerprint(directory, installed = true)
        val origin = runCatching { Gson().fromJson(File(directory, ".market-origin.json").readText(), PluginMarketOrigin::class.java) }.getOrNull()
        return PluginUpdateSnapshot(provider.manifest, fingerprint, origin?.takeIf { it.fingerprint == fingerprint }?.repository, provider.enabled)
    }

    private fun writeMarketOrigin(directory: File, repository: String, fingerprint: String) {
        val temporary = File(directory, ".market-origin.tmp")
        temporary.writeText(Gson().toJson(PluginMarketOrigin(repository, fingerprint)))
        java.nio.file.Files.move(temporary.toPath(), File(directory, ".market-origin.json").toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    private fun installLocked(prepared: PreparedPlugin, repository: String?): InstalledPlugin {
        if (!prepared.directory.isDirectory || prepared.fingerprint != fingerprint(prepared.directory)) invalid("Source package changed after review")
        val manifest = prepared.manifest
        if (providers.values.any { it.manifest.id != manifest.id && it.id == manifest.providerId }) invalid("Another source already uses this provider ID")
        manifest.compatibilityUpdateId?.let { updateId ->
            if (providers.values.any { it.manifest.id != manifest.id && it.manifest.compatibilityUpdateId == updateId }) {
                invalid("The market source is already installed")
            }
        }
        val destination = File(root, manifest.id)
        val previous = providers[manifest.id]
        if (previous != null && previous.id != manifest.providerId) invalid("Updates cannot change provider identity")
        val previousState = if (destination.exists()) readState(destination) else
            PluginInstallState(enabled = manifest.compatibility != "lx")
        val hadDestination = destination.exists()
        val backup = File(root, ".${manifest.id}-${UUID.randomUUID()}")
        val stage = File(root, ".source-stage-${manifest.id}-${UUID.randomUUID()}")
        val transaction = PluginInstallTransaction(manifest.id, stage.name, backup.name, prepared.fingerprint,
            if (hadDestination) fingerprint(destination, installed = true) else null)
        try {
            // Complete every potentially partial copy before changing the visible installation.
            if (!prepared.directory.renameTo(stage)) {
                check(prepared.directory.copyRecursively(stage, overwrite = false))
                prepared.directory.deleteRecursively()
            }
            val state = previousState.copy(revision = previousState.revision + 1)
            writeState(stage, state)
            if (repository != null) writeMarketOrigin(stage, repository, prepared.fingerprint)
            File(destination, "artwork").takeIf(File::isDirectory)?.let { check(it.copyRecursively(File(stage, "artwork"))) }
            PluginInstallJournal.write(root, transaction)
            return changeSource(manifest.id) { gate, revision ->
                var movedPrevious = false
                try {
                    if (hadDestination) {
                        if (!destination.renameTo(backup)) invalid("Unable to update installed source")
                        movedPrevious = true
                    }
                    if (!stage.renameTo(destination)) invalid("Unable to activate source package")
                    configurations[manifest.id] = configurations[manifest.id] ?: secrets.read(manifest.id, "config")
                    stores[manifest.id] = stores[manifest.id] ?: secrets.read(manifest.id, "storage")
                    diagnostics.remove(manifest.id)
                    register(manifest, destination, state, gate, revision)
                    backup.deleteRecursively()
                    PluginInstallJournal.finish(root, manifest.id)
                    publish()
                    mutableInstalled.value.first { it.manifest.id == manifest.id }
                } catch (error: Exception) {
                    if (!hadDestination || movedPrevious) destination.deleteRecursively()
                    if (movedPrevious) check(backup.renameTo(destination))
                    if (previous != null) register(previous.manifest, destination, previousState, gate, revision)
                    publish()
                    PluginInstallJournal.finish(root, manifest.id)
                    if (error is SourceException || error is kotlinx.coroutines.CancellationException) throw error
                    invalid("Unable to install source")
                }
            }
        } finally {
            stage.deleteRecursively()
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) { mutex.withLock {
        val provider = providers[id] ?: invalid("Source is not installed")
        val directory = File(root, id)
        val previousState = readState(directory)
        val state = previousState.copy(enabled = enabled, revision = previousState.revision + 1)
        changeSource(id) { gate, revision ->
            diagnostics.remove(id)
            try {
                writeState(directory, state)
                register(provider.manifest, directory, state, gate, revision)
            } catch (error: Exception) {
                register(provider.manifest, directory, previousState, gate, revision)
                throw error
            } finally { publish() }
        }
    } }

    suspend fun configure(id: String, values: Map<String, String>) = withContext(Dispatchers.IO) { mutex.withLock {
        val previous = providers[id] ?: invalid("Source is not installed")
        val keys = previous.manifest.config.map { it.key }.toSet()
        if (values.keys.any { it !in keys } || values.values.any { it.length > 8192 }) invalid("Invalid source configuration")
        val directory = File(root, id)
        val state = readState(directory).let { it.copy(revision = it.revision + 1) }
        changeSource(id) { gate, revision ->
            try {
                // Clear prior account state before changing credentials, even if a later write fails.
                secrets.write(id, "storage", emptyMap())
                stores[id] = emptyMap()
                secrets.write(id, "config", values)
                if (previous.manifest.compatibility == "musicfree" && values != configurations[id].orEmpty()) {
                    compatibilityTrackDirectory(id).deleteRecursively()
                }
                configurations[id] = values.toMap()
                writeState(directory, state)
                register(previous.manifest, directory, state, gate, revision)
                diagnostics.remove(id)
            } catch (error: Exception) {
                // Reactivate only the current snapshots under a new token; old requests stay revoked.
                register(previous.manifest, directory, state, gate, revision)
                throw error
            } finally { publish() }
        }
    } }

    suspend fun configureCompatibilityDomains(review: InstalledPlugin, domains: List<String>): InstalledPlugin = withContext(Dispatchers.IO) {
        val grants = approvedCompatibilityDomains(domains)
        mutex.withLock {
            val id = review.manifest.id
            val provider = providers[id]
            if (provider == null || provider.manifest !== review.manifest || provider.version != review.revision) {
                throw SourceException(SourceErrorCode.UNAVAILABLE, "Source changed during network permission review")
            }
            val current = provider.manifest.takeIf { it.compatibility != null } ?: invalid("Select an installed compatibility source")
            if (grants == current.permissions.network) return@withLock installed.value.first { it.manifest.id == id }
            val updated = current.copy(permissions = current.permissions.copy(network = grants))
            val source = File(root, "$id/${current.entry}").inputStream().use(::readCompatibilityScript)
            val prepared = writeCompatibilityImport(updated, source)
            try { installLocked(prepared, repository = null) }
            finally { prepared.directory.deleteRecursively() }
        }
    }

    fun configuration(id: String): Map<String, String> = configurations[id].orEmpty().toMap()

    suspend fun uninstall(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val provider = providers[id] ?: invalid("Source is not installed")
        if (id == BundledSourceProvisioner.NETEASE_ID) BundledSourceProvisioner.removed(root)
        changeSource(id) { _, _ ->
            try {
                providers.remove(id)
                registry.unregister(provider.instanceId)
                File(root, id).let { directory ->
                    directory.listFiles().orEmpty().filter { it.name != "artwork" }.forEach { it.deleteRecursively() }
                    if (directory.listFiles().isNullOrEmpty()) directory.delete()
                }
                configurations.remove(id)
                stores.remove(id)
                diagnostics.remove(id)
                secrets.delete(id)
                if (provider.manifest.compatibility == "musicfree") compatibilityTrackDirectory(id).deleteRecursively()
                // Room, playlists, cached audio and downloaded files are intentionally independent.
            } finally { publish() }
        }
    } }

    private fun register(manifest: PluginManifest, directory: File, state: PluginInstallState, gate: PluginRevisionGate, revision: PluginRevisionGate.Revision) {
        // Older compatibility installations kept their original ID even when a file replacement changed the bytes.
        val installedManifest = if (manifest.compatibility != null && manifest.compatibilityUpdateId == null) {
            val updateId = runCatching {
                val script = File(directory, manifest.entry).inputStream().use(::readCompatibilityScript)
                compatibilityFileUpdateId(manifest.compatibility, scriptSha256(script), legacyInstallId = manifest.id)
            }.getOrNull()
            manifest.copy(compatibilityUpdateId = updateId)
        } else manifest
        val provider = ScriptMusicProvider(installedManifest, directory, runtime, state.enabled,
            configurationSnapshot = configurations[manifest.id].orEmpty().toMap(),
            isCurrent = { gate.isCurrent(revision) },
            config = { gate.read(revision) { configurations[manifest.id].orEmpty().toMap() } },
            storage = { gate.read(revision) { stores[manifest.id].orEmpty().toMap() } },
            saveStorage = { values ->
                gate.commit(revision) {
                    secrets.write(manifest.id, "storage", values)
                    stores[manifest.id] = values.toMap()
                }
            },
            onFailure = { code ->
                gate.commit(revision) {
                    diagnostics[manifest.id] = when (code) {
                        SourceErrorCode.INVALID_RESPONSE -> "Source returned invalid data"
                        else -> "Source execution failed; disable or re-enable it to recover"
                    }
                    registry.refresh()
                    publish()
                }
            },
            configurationVersion = state.revision,
            artworkDirectory = File(context.cacheDir, "source-artwork/${manifest.id}"),
            coreExecutor = featureExecutor,
            coreHandler = platformEffects,
            artworkStore = artworkStore,
            compatibilityTracks = if (manifest.compatibility == "musicfree") {
                val configuration = configurations[manifest.id].orEmpty().toSortedMap()
                CompatibilityTrackStore(File(compatibilityTrackDirectory(manifest.id), scriptDigest(Gson().toJson(configuration))))
            } else null,
            commitCompatibilityTracks = { action -> gate.read(revision, action) },
            onDomainRequest = ::publish,
        )
        providers[manifest.id] = provider
        registry.register(provider)
    }

    private fun compatibilityTrackDirectory(id: String) = File(context.noBackupFilesDir, "source-compatibility-tracks/$id")

    private fun <T> changeSource(id: String, action: (PluginRevisionGate, PluginRevisionGate.Revision) -> T): T {
        val gate = revisions.getOrPut(id) { PluginRevisionGate() }
        return gate.change { revision -> action(gate, revision) }
    }

    @Synchronized private fun publish() {
        mutableInstalled.value = providers.values.map {
            InstalledPlugin(it.manifest, it.enabled, it.status, diagnostics[it.manifest.id], it.requestedDomain, it.version)
        }.sortedBy { it.manifest.name }
    }

    private fun readState(directory: File): PluginInstallState = File(directory, "state.json").takeIf(File::isFile)
        ?.let { Gson().fromJson(it.readText(), PluginInstallState::class.java) } ?: PluginInstallState()

    private fun writeState(directory: File, state: PluginInstallState) {
        val temporary = File(directory, "state.tmp")
        temporary.writeText(Gson().toJson(state))
        check(temporary.renameTo(File(directory, "state.json")))
    }

    private fun fingerprint(directory: File, installed: Boolean = false): String {
        val digest = MessageDigest.getInstance("SHA-256")
        directory.walkTopDown().filter(File::isFile).filter { file ->
            val name = file.relativeTo(directory).invariantSeparatorsPath
            !installed || name !in setOf("state.json", "state.tmp", ".market-origin.json", ".market-origin.tmp") && !name.startsWith("artwork/")
        }.sortedBy { it.relativeTo(directory).path }.forEach { file ->
            digest.update(file.relativeTo(directory).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            val fileDigest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; fileDigest.update(buffer, 0, count) }
            }
            digest.update(fileDigest.digest())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun invalid(message: String): Nothing = throw SourceException(SourceErrorCode.PLUGIN, message)

    companion object {
        private val installationMutex = Mutex()
        private val LX_MARKERS = Regex("""\b(?:globalThis|window)\s*\.\s*lx\b|\blx\s*\.\s*(?:on|send|EVENT_NAMES)\b""")
        private val HTTP_LITERAL = Regex("""(?i)https?://([a-z0-9][a-z0-9.-]{2,252})(?=[:/\s`"'?&#]|$)""")
        private const val MAX_COMPATIBILITY_BYTES = 1024 * 1024

        internal fun inferredCompatibilityDomains(script: String): List<String> = HTTP_LITERAL.findAll(script)
            .map { it.groupValues[1].lowercase(java.util.Locale.ROOT) }.distinct()
            .filter { runCatching { ScriptNetworkPolicy.validateDomain(it) }.isSuccess }.take(32).toList()

        private fun compatibilityDomains(domains: List<String>): List<String> =
            domains.map { it.trim().lowercase(java.util.Locale.ROOT) }.filter(String::isNotBlank).distinct().also { grants ->
                if (grants.size > 32) throw SourceException(SourceErrorCode.INVALID_RESPONSE, "Too many source domains")
                grants.forEach(ScriptNetworkPolicy::validateCompatibilityGrant)
            }

        private fun approvedCompatibilityDomains(domains: List<String>): List<String> =
            try { compatibilityDomains(domains) }
            catch (_: SourceException) { throw SourceException(SourceErrorCode.PERMISSION_DENIED) }

        private fun readCompatibilityScript(input: InputStream): String =
            decodeCompatibilityScript(readCompatibilityScriptBytes(input)).removePrefix("\uFEFF")

        private fun readCompatibilityScriptBytes(input: InputStream): ByteArray {
            val bytes = input.source().buffer().use {
                if (it.request(MAX_COMPATIBILITY_BYTES + 1L)) throw SourceException(SourceErrorCode.INVALID_RESPONSE)
                it.readByteArray()
            }
            if (bytes.isEmpty()) throw SourceException(SourceErrorCode.INVALID_RESPONSE)
            return bytes
        }

        private fun decodeCompatibilityScript(bytes: ByteArray): String {
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) {
                throw SourceException(SourceErrorCode.INVALID_RESPONSE, "Source script must use UTF-8")
            }
        }

        private fun hasZipHeader(input: InputStream): Boolean =
            input.read() == 'P'.code && input.read() == 'K'.code && input.read() == 3 && input.read() == 4

        private fun scriptSha256(script: String): String = MessageDigest.getInstance("SHA-256")
            .digest(script.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun scriptDigest(script: String): String = scriptSha256(script).take(40)
        internal fun packageFileLimit(name: String): Int = when {
            name in setOf("manifest.json", "LICENSE", "NOTICE") -> 64 * 1024
            name == "index.js" -> 256 * 1024
            Regex("assets/[A-Za-z0-9._-]+\\.(wav|mp3|flac|m4a|ogg)").matches(name) -> 4 * 1024 * 1024
            else -> throw SourceException(SourceErrorCode.PLUGIN, "Source package contains an unsupported path")
        }

        internal fun validateLegalText(bytes: ByteArray) {
            if (bytes.size > 64 * 1024) throw SourceException(SourceErrorCode.PLUGIN, "Source legal text exceeds size limits")
            val text = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: Exception) {
                throw SourceException(SourceErrorCode.PLUGIN, "Source legal text must be UTF-8 text")
            }
            if (text.any { it.isISOControl() && it !in "\t\r\n\u000c" }) {
                throw SourceException(SourceErrorCode.PLUGIN, "Source legal text must be plain text")
            }
        }
    }
}
