package com.ljyh.mei.source.update

import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.api.SourceStatus
import com.ljyh.mei.source.script.InstalledPlugin
import com.ljyh.mei.source.script.PluginConfigField
import com.ljyh.mei.source.script.PluginManifest
import com.ljyh.mei.source.script.PluginPermissions
import com.ljyh.mei.source.script.PluginInstallJournal
import com.ljyh.mei.source.script.PluginInstallTransaction
import com.ljyh.mei.source.script.ScriptNetworkPolicy
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SourceUpdatesTest {
    private val listing = SourceMarketListing("org.meilox.fixture", "fixture.music", "Fixture",
        "https://github.com/Owner/Fixture", "dist/fixture.meisource", 2)
    private val entry = SourceMarketEntry("org.meilox.fixture", "fixture.music", "Fixture", "1.2.3",
        "https://github.com/Owner/Fixture", "https://raw.githubusercontent.com/Owner/Fixture/${"a".repeat(40)}/dist/fixture.meisource", "b".repeat(64), 2)

    private fun json(value: SourceMarketListing = listing): String = """{"schemaVersion":2,"sources":[{
        "packageId":"${value.packageId}","providerId":"${value.providerId}","name":"${value.name}",
        "repository":"${value.repository}","packagePath":"${value.packagePath}","apiVersion":${value.apiVersion}}]}"""

    @Test
    fun registryAcceptsDynamicOwnedPackagesAndRejectsAmbiguousOrExecutableMetadata() {
        assertEquals(listOf(listing), SourceMarketIndex.parse(json()))
        assertTrue(SourceMarketIndex.parse("""{"schemaVersion":2,"sources":[]}""").isEmpty())
        SourceMarketIndex.validate(entry.copy(packageUrl = entry.packageUrl.replace("/" + "a".repeat(40) + "/", "/HEAD/")))
        try { SourceMarketIndex.validate(entry.copy(packageUrl = entry.packageUrl.replace("/" + "a".repeat(40) + "/", "/main/"))); fail("Accepted arbitrary mutable branch") }
        catch (_: IllegalArgumentException) { }
        val invalid = listOf(
            json().replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"schemaVersion\":2"),
            json().replace("\"providerId\":", "\"unexpected\":\"value\",\"providerId\":"),
            json().replace("\"apiVersion\":2", "\"apiVersion\":\"2\""),
            json().replace("\"name\":\"Fixture\"", "\"name\":\"Fixture\",\"name\":\"Other\""),
            json().replace("}]}", "},${json().substringAfter("[").substringBeforeLast("]")}]}") ,
            json(listing.copy(packagePath = "main")),
            json(listing.copy(packagePath = "../fixture.meisource")),
            json(listing.copy(packagePath = "dist/fixture.txt")),
            json(listing.copy(repository = "https://github.com/Owner/Fixture.git")),
            json(listing.copy(packagePath = "dist/../fixture.meisource")),
            json(listing.copy(apiVersion = 3)),
            json().replace("\"schemaVersion\":2", "\"schemaVersion\":1"),
            json() + " true",
            " ".repeat(SourceMarketIndex.MAX_BYTES) + json(),
        )
        invalid.forEachIndexed { index, value ->
            try { SourceMarketIndex.parse(value); fail("Accepted invalid registry case $index") }
            catch (_: SourceException) { }
        }
        val other = json(listing.copy(packageId = "org.meilox.other")).substringAfter("[").substringBeforeLast("]")
        try { SourceMarketIndex.parse(json().replace("}]}", "},$other]}")); fail("Duplicate provider identity") }
        catch (_: SourceException) { }
    }

    @Test
    fun compatibilityListingsPinBytesAndKeepIdentityOnlyWithinTheSameScriptOrigin() {
        val listing = CompatibilityMarketEntry(
            "musicfree.fixture", "Fixture", "1.2.3", "musicfree", "https://github.com/Owner/Fixture",
            "a".repeat(40), "dist/fixture.js", "b".repeat(64),
        )
        fun index(vararg items: CompatibilityMarketEntry): String = """{"schemaVersion":1,"scripts":${
            com.google.gson.Gson().toJson(items)
        }}"""

        assertEquals(listOf(listing), CompatibilityMarketIndex.parse(index(listing)))
        assertEquals("https://raw.githubusercontent.com/Owner/Fixture/${listing.revision}/dist/fixture.js", listing.url)
        assertTrue(listing.packageId.matches(Regex("compat\\.musicfree\\.[a-f0-9]{40}")))
        val updated = listing.copy(version = "2.0.0", revision = "c".repeat(40), sha256 = "d".repeat(64))
        assertEquals(listing.packageId, updated.packageId)
        assertEquals(listing.packageId, listing.copy(repository = "https://github.com/owner/fixture").packageId)
        assertNotEquals(listing.packageId, listing.copy(repository = "https://github.com/Other/Fixture").packageId)
        assertNotEquals(listing.packageId, listing.copy(path = "dist/other.js").packageId)

        val invalid = listOf(
            index(listing.copy(revision = "main")),
            index(listing.copy(sha256 = "0".repeat(64))),
            index(listing.copy(repository = "https://github.com.evil.test/Owner/Fixture")),
            index(listing.copy(path = "dist/../fixture.js")),
            index(listing.copy(path = "dist/fixture.js?trusted=true")),
            index(listing.copy(format = "lx")),
            index(listing, updated),
            index(listing, updated.copy(id = "musicfree.other", repository = "https://github.com/owner/fixture")),
            index(listing).replace("\"id\":", "\"id\":\"musicfree.extra\",\"id\":"),
        )
        invalid.forEach { json ->
            val failure = runCatching { CompatibilityMarketIndex.parse(json) }.exceptionOrNull()
            assertEquals(SourceErrorCode.INVALID_RESPONSE, (failure as? SourceException)?.code)
        }
    }

    @Test
    fun compatibilityUpdatesMatchInstalledMarketAndKnownFileImportsAcrossVersions() {
        val bundled = CompatibilityMarketEntry("lx.fixture", "Fixture", "1.0.0", "lx",
            "https://github.com/Owner/Fixture", "a".repeat(40), "fixture.js", "b".repeat(64))
        val next = bundled.copy(version = "1.1.0", sha256 = "c".repeat(64))
        fun item(id: String, version: String = bundled.version, remote: List<CompatibilityMarketEntry> = listOf(next)): CompatibilityMarketItem {
            val manifest = PluginManifest(id = id, name = bundled.name, author = "Fixture", version = version,
                apiVersion = 2, capabilities = emptySet(), compatibility = bundled.format, compatibilityUpdateId = id)
            val installed = InstalledPlugin(manifest, false, SourceStatus.DISABLED, null, null, "1")
            return compatibilityMarketItems(listOf(bundled), remote, listOf(installed)).single()
        }

        for (id in listOf(bundled.packageId, bundled.manualPackageId)) {
            val update = item(id)
            assertEquals(id, update.installedPackageId)
            assertEquals("1.0.0", update.installedVersion)
            assertEquals(next, update.entry)
            assertTrue(update.updateAvailable)
            assertFalse(item(id, "1.1.0").updateAvailable)
            assertFalse(item(id, "1.2.0").updateAvailable)
        }
        assertFalse(item("compat.lx." + "d".repeat(40)).updateAvailable)
        assertFalse(item(bundled.packageId, remote = listOf(next.copy(path = "moved.js"))).updateAvailable)
        assertTrue(item(bundled.packageId, "0.9.0", remote = emptyList()).updateAvailable)
    }

    @Test
    fun historicalMarketInstallsKeepTheirOriginAfterBothCatalogDigestsChange() {
        val legacy = listOf(
            "navidrome" to "2f1e930e417a72e2a0c870bde45359c442ad139bcf37bfe444df5141fd0cdeb3",
            "audiomack" to "1b1f49fc1e0aa5933c3b45ce999d975e4f7fc5f69df2a487dd87830555cf01d5",
        )
        for ((source, digest) in legacy) {
            val bundled = CompatibilityMarketEntry("musicfree.$source", source, "2.0.0", "musicfree",
                "https://github.com/maotoumao/MusicFreePlugins", "a".repeat(40), "dist/$source/index.js", "b".repeat(64))
            val latest = bundled.copy(version = "3.0.0", sha256 = "c".repeat(64))
            assertEquals("A fresh file import recognizes the published bytes", bundled.packageId,
                compatibilityFileUpdateId(bundled.format, digest))
            fun item(id: String, installedDigest: String): CompatibilityMarketItem {
                val manifest = PluginManifest(id = id, name = source, author = "Fixture", version = "1.0.0", apiVersion = 2,
                    capabilities = emptySet(), compatibility = bundled.format,
                    compatibilityUpdateId = compatibilityFileUpdateId(bundled.format, installedDigest, legacyInstallId = id))
                val installed = InstalledPlugin(manifest, true, SourceStatus.READY, null, null, "1")
                return compatibilityMarketItems(listOf(bundled), listOf(latest), listOf(installed)).single()
            }
            for (id in listOf(bundled.packageId, "compat.musicfree.${digest.take(40)}")) {
                val match = item(id, digest)
                assertEquals(id, match.installedPackageId)
                assertTrue(match.updateAvailable)
            }
            assertNull(item(bundled.packageId, digest.take(40) + "0".repeat(24)).installedPackageId)
        }
    }

    @Test
    fun onlyTheCanonicalPublisherAndPackagePathCanBeOfficial() {
        val qq = SourceMarketEntry(
            "org.meilox.qqmusic", "qqmusic", "QQ Music", "0.4.0",
            "https://github.com/MeiloX/MeiloX-QQMusic-Source",
            "https://raw.githubusercontent.com/MeiloX/MeiloX-QQMusic-Source/${"a".repeat(40)}/qq-music.meisource",
            "b".repeat(64), 2,
        )
        SourceMarketIndex.validate(qq)
        assertTrue(qq.isOfficial)
        val legacy = SourceMarketListing(qq.packageId, qq.providerId, qq.name,
            "https://github.com/NEORUAA/MeiloX-QQMusic-Source", qq.packagePath, qq.apiVersion)
        assertEquals(qq.repository, SourceMarketIndex.parse(json(legacy)).single().repository)
        assertEquals(qq.repository, qq.canonicalOrigin(legacy.repository))
        assertNull(qq.canonicalOrigin(null))
        assertEquals("https://github.com/Other/Source", qq.canonicalOrigin("https://github.com/Other/Source"))
        assertEquals(legacy.repository, qq.copy(packagePath = "other.meisource").canonicalOrigin(legacy.repository))
        val other = legacy.copy(packagePath = "other.meisource")
        assertEquals(other, SourceMarketIndex.parse(json(other)).single())
        assertFalse(qq.copy(repository = "https://github.com/Another/MeiloX-QQMusic-Source").isOfficial)
        assertFalse(qq.copy(providerId = "qqmusic.other").isOfficial)
        assertFalse(qq.copy(packagePath = "dist/qq-music.meisource").isOfficial)
        assertFalse(qq.copy(packageId = "org.meilox.qqmusic.fork").isOfficial)
    }

    @Test
    fun semanticVersionsNeverDowngradeAndOrderPrereleaseWithoutNumericOverflow() {
        val versions = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.10.0", "2.0.0")
        versions.zipWithNext().forEach { (old, next) -> assertTrue("$next after $old", isNewer(next, old)); assertFalse(isNewer(old, next)) }
        assertEquals(0, SourceVersion.parse("1.2.3+build.1")!!.compareTo(SourceVersion.parse("1.2.3+other")!!))
        assertTrue(isNewer("999999999999999999999.0.0", "999999999999999999998.0.0"))
        listOf("1", "01.0.0", "1.2.3-01", "1.2.3-", "1.2.3+", "v1.2.3", "1.2.3.4").forEach { assertNull(it, SourceVersion.parse(it)) }
        assertFalse(isNewer("1.2.3", "legacy-version"))
        assertFalse(isNewer("1.2.3+build", "1.2.3"))
    }

    @Test
    fun automaticConsentRequiresTheSameOriginAndNoExpandedAuthority() {
        val previous = PluginManifest("org.meilox.fixture", "fixture.music", "Fixture", "Tests", "1.0.0", 2,
            capabilities = setOf("catalog.track"), permissions = PluginPermissions(network = listOf("api.example.com")),
            config = listOf(PluginConfigField("cookie", "Cookie", secret = true, required = true)))
        val next = previous.copy(version = "1.1.0")
        assertTrue(sourceUpdateReviewReasons(previous, next, entry.repository, entry.repository).isEmpty())
        assertEquals(setOf(SourceUpdateReviewReason.UNKNOWN_ORIGIN), sourceUpdateReviewReasons(previous, next, null, entry.repository))
        assertEquals(setOf(SourceUpdateReviewReason.REPOSITORY_CHANGED), sourceUpdateReviewReasons(previous, next, "https://github.com/Other/Repo", entry.repository))
        val expanded = next.copy(
            permissions = PluginPermissions(listOf("api.example.com", "new.example.com"), storage = true, nativeSdks = setOf("netease.urs"), effects = setOf("web.open")),
            capabilities = next.capabilities + "links.resolve", linkHosts = setOf("new.example.com"),
            sessionKeys = setOf("account"), sessionCookieNames = mapOf("account" to setOf("MUSIC_U")),
            config = next.config + PluginConfigField("account", "Account", required = true))
        assertEquals(setOf(SourceUpdateReviewReason.NETWORK, SourceUpdateReviewReason.STORAGE, SourceUpdateReviewReason.NATIVE_SDK,
            SourceUpdateReviewReason.EFFECTS, SourceUpdateReviewReason.LINKS, SourceUpdateReviewReason.SESSION,
            SourceUpdateReviewReason.CAPABILITIES, SourceUpdateReviewReason.REQUIRED_CONFIGURATION),
            sourceUpdateReviewReasons(previous, expanded, entry.repository, entry.repository))
        val reduced = expanded.copy(permissions = previous.permissions, sessionKeys = expanded.sessionKeys, sessionCookieNames = expanded.sessionCookieNames,
            capabilities = previous.capabilities, linkHosts = emptySet(), config = previous.config)
        assertTrue(sourceUpdateReviewReasons(expanded, reduced, entry.repository, entry.repository).isEmpty())
        assertEquals(setOf(SourceUpdateReviewReason.SESSION), sourceUpdateReviewReasons(expanded,
            expanded.copy(sessionKeys = emptySet(), sessionCookieNames = emptyMap()), entry.repository, entry.repository))
        val wildcard = next.copy(permissions = next.permissions.copy(network = listOf("*.example.com")))
        assertEquals(setOf(SourceUpdateReviewReason.NETWORK),
            sourceUpdateReviewReasons(previous, wildcard, entry.repository, entry.repository))
        assertTrue(sourceUpdateReviewReasons(wildcard, wildcard.copy(version = "1.2.0"),
            entry.repository, entry.repository).isEmpty())
    }

    @Test
    fun processInterruptionKeepsOnlyCompletePayloadsAndTheirOriginalEnabledState() {
        fun fingerprint(directory: File): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(File(directory, "index.js").readBytes()).joinToString("") { "%02x".format(it) }
        for (phase in 0..3) {
            val root = kotlin.io.path.createTempDirectory("source-update-recovery-").toFile()
            try {
                val destination = File(root, entry.packageId).apply { mkdirs() }
                File(destination, "index.js").writeText("previous")
                File(destination, "state.json").writeText("disabled")
                val stage = File(root, ".source-stage-${entry.packageId}-${UUID.randomUUID()}").apply { mkdirs() }
                File(stage, "index.js").writeText("next")
                File(stage, "state.json").writeText("disabled-next-revision")
                val backup = File(root, ".${entry.packageId}-${UUID.randomUUID()}")
                PluginInstallJournal.write(root, PluginInstallTransaction(entry.packageId, stage.name, backup.name, fingerprint(stage), fingerprint(destination)))
                if (phase >= 1) assertTrue(destination.renameTo(backup))
                if (phase == 2) { destination.mkdirs(); File(destination, "index.js").writeText("incomplete") }
                if (phase == 3) assertTrue(stage.renameTo(destination))
                assertTrue(PluginInstallJournal.recover(root, ::fingerprint).isEmpty())
                assertEquals(if (phase == 3) "next" else "previous", File(destination, "index.js").readText())
                assertEquals(if (phase == 3) "disabled-next-revision" else "disabled", File(destination, "state.json").readText())
                assertFalse(backup.exists())
                assertFalse(stage.exists())
                assertTrue("Recovery must be idempotent", PluginInstallJournal.recover(root, ::fingerprint).isEmpty())
            } finally { root.deleteRecursively() }
        }
    }

    @Test
    fun pausedSourcesAndRestoreReservationsDeferAutomaticReplacementWithoutBlockingTheUi() = runTest {
        val guard = SourceUpdatePlaybackGuard()
        guard.updateSources(setOf("origin.music", "actual.music"))
        for (provider in listOf("origin.music", "actual.music")) {
            try { guard.installIfIdle(provider) { fail("An owned source cannot be replaced") }; fail("Expected deferred update") }
            catch (_: SourceUpdateDeferred) { }
        }
        assertEquals("unrelated", guard.installIfIdle("other.music") { "unrelated" })
        var restoration: kotlinx.coroutines.Deferred<Unit>? = null
        guard.release()
        guard.installIfIdle("other.music") {
            restoration = guard.beginRestore()
            assertFalse("A new service awaits the already reserved transaction", restoration!!.isCompleted)
        }
        assertTrue(restoration!!.isCompleted)
        try { guard.installIfIdle("other.music") { fail("Restoration owns its boundary") }; fail("Expected restore deferral") }
        catch (_: SourceUpdateDeferred) { }
        guard.finishRestore(emptySet())
        assertEquals("ready", guard.installIfIdle("other.music") { "ready" })
        val resolvingStarted = CompletableDeferred<Unit>()
        val ongoing = launch(start = CoroutineStart.UNDISPATCHED) {
            guard.withResolution({ setOf("origin.music", "fallback.music") }) {
                resolvingStarted.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        resolvingStarted.await()
        for (provider in listOf("origin.music", "fallback.music")) {
            try { guard.installIfIdle(provider) { fail("A pending resolver owns every possible source") }; fail("Expected resolving deferral") }
            catch (_: SourceUpdateDeferred) { }
        }
        ongoing.cancelAndJoin()
        assertEquals("released", guard.installIfIdle("origin.music") { "released" })
        var calls = 0
        var waiting: kotlinx.coroutines.Job? = null
        guard.installIfIdle("origin.music") {
            waiting = launch(start = CoroutineStart.UNDISPATCHED) {
                guard.withResolution({ setOf("origin.music") }) {
                    calls++
                    try { guard.installIfIdle("origin.music") { fail("Resolution acquired its lease atomically") }; fail("Expected resolving deferral") }
                    catch (_: SourceUpdateDeferred) { }
                }
            }
            assertEquals(0, calls)
        }
        advanceUntilIdle()
        waiting!!.join()
        assertEquals("Waiting never replays an operation", 1, calls)
    }

    @Test
    fun partialRefreshRetainsOnlyMatchingTransientFailuresForManualRetry() = runTest {
        val other = listing.copy(packageId = "org.meilox.other", providerId = "other.music")
        val otherEntry = entry.copy(packageId = other.packageId, providerId = other.providerId)
        val renamed = listing.copy(name = "Renamed")
        val items = listOf(renamed, other)
        for (code in listOf(SourceErrorCode.TIMEOUT, SourceErrorCode.NETWORK)) {
            val refreshed = resolveMarketListings(items, listOf(entry)) {
                if (it == renamed) throw SourceException(code)
                otherEntry
            }
            assertEquals(listOf(entry.copy(name = "Renamed"), otherEntry), refreshed.entries)
            assertEquals(setOf(entry.packageId), refreshed.unresolved)
        }
        val changed = listOf(listing.copy(packageId = other.packageId), listing.copy(providerId = other.providerId),
            listing.copy(repository = other.repository + "-moved"), listing.copy(packagePath = "other.meisource"), listing.copy(apiVersion = 1))
        for (item in changed) {
            assertTrue(resolveMarketListings(listOf(item), listOf(entry)) { throw SourceException(SourceErrorCode.NETWORK) }.entries.isEmpty())
        }
        for (code in listOf(SourceErrorCode.INVALID_RESPONSE, SourceErrorCode.NOT_FOUND, SourceErrorCode.PERMISSION_DENIED)) {
            assertTrue(resolveMarketListings(listOf(listing), listOf(entry)) { throw SourceException(code) }.entries.isEmpty())
        }
        val removed = resolveMarketListings(emptyList(), listOf(entry)) { error("Delisted package") }
        assertTrue(removed.entries.isEmpty())
    }

    @Test
    fun githubFileFallbackKeepsTheRepositoryPathRevisionAndResponseChecks() = runTest {
        val calls = mutableListOf<okhttp3.Request>()
        val policy = ScriptNetworkPolicy(marketHosts)
        var rawStatus = 503
        val client = policy.marketClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            calls += request
            val available = request.url.host == "api.github.com"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (available) 200 else rawStatus).message("Fixture")
                .body((if (available) json() else "").toResponseBody()).build()
        }.build()
        assertNull(client.proxy)
        java.net.ProxySelector.getDefault()?.let { assertSame(it, client.proxySelector) }
        assertEquals(json(), SourceMarketTransport(policy, client).index(null).json)
        assertEquals(listOf(SourceMarketIndex.URL,
            "https://api.github.com/repos/MeiloX/MeiloX-Source-Registry/contents/index.json?ref=main"),
            calls.map { it.url.toString() })
        assertEquals("application/vnd.github.raw+json", calls.last().header("Accept"))
        rawStatus = 404
        val compatibility = okhttp3.Request.Builder().url(CompatibilityMarketIndex.URL).build()
        assertEquals(200, policy.readMarketFile(client, compatibility) { response, _ ->
            if (response.code == 404) null else response.code
        })
        assertEquals("/repos/MeiloX/MeiloX-Source-Registry/contents/compatibility.json", calls.last().url.encodedPath)

        val before = "https://api.github.com/repos/Owner/Source/commits?path=source.meisource&per_page=1".toHttpUrl()
        val moved = "https://api.github.com/repositories/1234/commits?path=source.meisource&per_page=1".toHttpUrl()
        assertTrue(isGitHubRepositoryRedirect(before, moved))
        assertFalse(isGitHubRepositoryRedirect(before, moved.newBuilder().host("another.example").build()))
        assertFalse(isGitHubRepositoryRedirect(before, moved.newBuilder().encodedPath("/repositories/1234/issues").build()))
        assertFalse(isGitHubRepositoryRedirect(before, moved.newBuilder().query("path=other.meisource").build()))
    }

    @Test
    fun marketTransportPinsCommitFallsBackOnTransientFailuresAndVerifiesChecksum() = runTest {
        fun archiveBytes(version: String, padding: Int = 0): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(
                    """{"id":"${listing.packageId}","providerId":"${listing.providerId}","version":"$version","apiVersion":${listing.apiVersion}}"""
                        .toByteArray(Charsets.UTF_8),
                )
                if (padding > 0) zip.write(ByteArray(padding) { ' '.code.toByte() })
                zip.closeEntry()
            }
            return out.toByteArray()
        }

        val sha = "a".repeat(40)
        val packageV1 = archiveBytes("1.2.3")
        val packageV2 = archiveBytes("1.2.4")
        var apiCode: Int? = 200
        var apiBody = """[{"sha":"$sha"}]"""
        var rawBytes = packageV1
        val seenRawUrls = mutableListOf<String>()
        val policy = ScriptNetworkPolicy(listOf("raw.githubusercontent.com", "api.github.com"))
        val client = policy.client.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                when (request.url.host) {
                    "api.github.com" -> {
                        val code = apiCode ?: throw java.net.SocketTimeoutException()
                        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                            .code(code).message(if (code == 200) "OK" else "Error")
                            .body(apiBody.toResponseBody("application/json".toMediaType())).build()
                    }
                    "raw.githubusercontent.com" -> {
                        seenRawUrls += url
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(rawBytes.toResponseBody("application/octet-stream".toMediaType()))
                            .build()
                    }
                    else -> error("Unexpected host: ${request.url.host}")
                }
            }
            .build()
        val transport = SourceMarketTransport(policy, client)

        val pinned = transport.resolve(listing)
        assertEquals("https://raw.githubusercontent.com/Owner/Fixture/$sha/${listing.packagePath}", pinned.packageUrl)
        assertEquals("1.2.3", pinned.version)
        rawBytes = archiveBytes("1.2.3", padding = 64 * 1024)
        assertTrue(rawBytes.size < 64 * 1024)
        val oversized = runCatching { transport.resolve(listing) }.exceptionOrNull() as SourceException
        assertEquals(SourceErrorCode.INVALID_RESPONSE, oversized.code)
        rawBytes = packageV1

        for (transient in listOf(403, 408, 429, 503, null)) {
            apiCode = transient
            apiBody = """{"message":"temporarily unavailable"}"""
            val fallback = transport.resolve(listing)
            assertEquals("https://raw.githubusercontent.com/Owner/Fixture/HEAD/${listing.packagePath}", fallback.packageUrl)
            val destination = File.createTempFile("source-market-test-", ".meisource")
            try {
                rawBytes = packageV2
                try {
                    transport.download(fallback, destination)
                    fail("HEAD payload drift must fail checksum verification")
                } catch (error: SourceException) {
                    assertEquals(SourceErrorCode.INVALID_RESPONSE, error.code)
                    assertFalse(destination.exists())
                }
                rawBytes = packageV1
                transport.download(fallback, destination)
                assertTrue(packageV1.contentEquals(destination.readBytes()))
            } finally {
                destination.delete()
            }
        }

        val failureCodes = mapOf(200 to SourceErrorCode.INVALID_RESPONSE, 400 to SourceErrorCode.INVALID_RESPONSE,
            404 to SourceErrorCode.NOT_FOUND, 410 to SourceErrorCode.NOT_FOUND)
        for ((failureCode, expected) in failureCodes) {
            apiCode = failureCode
            apiBody = """{"message":"error"}"""
            val rawCountBefore = seenRawUrls.size
            try {
                transport.resolve(listing)
                fail("HTTP $failureCode must fail closed without falling back to HEAD")
            } catch (error: SourceException) {
                assertEquals(expected, error.code)
                assertEquals(rawCountBefore, seenRawUrls.size)
            }
        }
    }
}
