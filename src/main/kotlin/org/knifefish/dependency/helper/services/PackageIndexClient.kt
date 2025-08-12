package org.knifefish.dependency.helper.services

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.Ecosystem
import org.knifefish.dependency.helper.model.LatestVersionPolicy
import org.knifefish.dependency.helper.model.LookupStatus
import org.knifefish.dependency.helper.model.PackageSearchResult
import org.knifefish.dependency.helper.model.RepositorySpec
import org.knifefish.dependency.helper.model.VersionInfo
import org.knifefish.dependency.helper.util.VersionComparator
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class PackageIndexClient {

    private val httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    fun findLatestVersion(
        dependency: DependencyCoordinate,
        repositories: List<RepositorySpec>,
        policy: LatestVersionPolicy,
    ): VersionInfo {
        val orderedRepositories = repositories.ifEmpty { listOf(defaultRepository(dependency.ecosystem)) }
        var unauthorizedResult: VersionInfo? = null
        orderedRepositories.forEach { repository ->
            val result = when (dependency.ecosystem) {
                Ecosystem.MAVEN, Ecosystem.GRADLE -> fetchLatestMavenVersion(repository, dependency)
                Ecosystem.NPM -> fetchLatestNpmVersion(repository, dependency.name)
                Ecosystem.PYTHON -> fetchLatestPythonVersion(repository, dependency.name)
                Ecosystem.RUST -> fetchLatestRustVersion(repository, dependency.name)
            }
            if (result.status == LookupStatus.OK) {
                return applyPolicy(result, policy)
            }
            if (result.status == LookupStatus.UNAUTHORIZED && unauthorizedResult == null) {
                unauthorizedResult = result
            }
        }
        unauthorizedResult?.let { return applyPolicy(it, policy) }
        return VersionInfo(
            latestStable = null,
            latestAvailable = null,
            repositoryUrl = orderedRepositories.firstOrNull()?.url,
            publishedAt = null,
            status = LookupStatus.NOT_FOUND,
            message = "No repository response",
        )
    }

    fun search(ecosystem: Ecosystem, query: String, repositories: List<RepositorySpec>): List<PackageSearchResult> {
        return when (ecosystem) {
            Ecosystem.MAVEN, Ecosystem.GRADLE -> searchMaven(query, repositories)
            Ecosystem.NPM -> searchNpm(query, repositories)
            Ecosystem.PYTHON -> searchPython(query)
            Ecosystem.RUST -> searchRust(query)
        }
    }

    private fun searchMaven(query: String, repositories: List<RepositorySpec>): List<PackageSearchResult> {
        val candidates = repositories
            .ifEmpty { listOf(defaultRepository(Ecosystem.MAVEN)) }
            .distinctBy { it.url }
        candidates.forEach { repository ->
            val results = when (val backend = MavenRepositorySearchBackend.from(repository.url)) {
                MavenRepositorySearchBackend.CENTRAL -> searchMavenCentral(query)
                is MavenRepositorySearchBackend.NEXUS -> searchNexusMaven(query, repository, backend)
                is MavenRepositorySearchBackend.ARTIFACTORY -> searchArtifactoryMaven(query, repository, backend)
                MavenRepositorySearchBackend.UNKNOWN -> searchExactMavenInRepository(query, repository)
            }
            if (results.isNotEmpty()) {
                return results
            }
        }
        return emptyList()
    }

    private fun searchMavenCentral(query: String): List<PackageSearchResult> {
        val encoded = encode(query)
        val url = "https://search.maven.org/solrsearch/select?q=$encoded&rows=20&wt=json"
        return runCatching {
            val response = get(url)
            val docs = json.parseToJsonElement(response).jsonObject["response"]?.jsonObject?.get("docs")?.jsonArray.orEmpty()
            docs.mapNotNull { item ->
                val obj = item.jsonObject
                val group = content(obj["g"]) ?: return@mapNotNull null
                val artifact = content(obj["a"]) ?: return@mapNotNull null
                PackageSearchResult(Ecosystem.MAVEN, group, artifact, content(obj["latestVersion"]), null, "https://search.maven.org/")
            }
        }.getOrElse {
            thisLogger().warn("Maven search failed", it)
            emptyList()
        }
    }

    private fun searchExactMavenInRepository(
        query: String,
        repository: RepositorySpec,
    ): List<PackageSearchResult> {
        val gav = gavParts(query) ?: return emptyList()
        val info = fetchLatestMavenVersion(repository, gav.first, gav.second)
        return if (info.status == LookupStatus.OK) {
            listOf(
                PackageSearchResult(
                    ecosystem = Ecosystem.MAVEN,
                    group = gav.first,
                    name = gav.second,
                    latestVersion = info.latestStable,
                    description = "Exact match from ${repository.url}",
                    repositoryUrl = repository.url,
                )
            )
        } else {
            emptyList()
        }
    }

    private fun searchNexusMaven(
        query: String,
        repository: RepositorySpec,
        backend: MavenRepositorySearchBackend.NEXUS,
    ): List<PackageSearchResult> {
        val params = mutableListOf(
            "repository=${encode(backend.repositoryKey)}",
            "format=maven2",
        )
        val gav = gavParts(query)
        if (gav != null) {
            params += "group=${encode(gav.first)}"
            params += "name=${encode(gav.second)}"
        } else {
            params += "name=${encode(query)}"
        }
        val url = "${backend.baseUrl}/service/rest/v1/search?${params.joinToString("&")}"
        return runCatching {
            val response = get(url)
            val items = json.parseToJsonElement(response).jsonObject["items"]?.jsonArray.orEmpty()
            aggregateMavenSearchResults(
                items = items.map { it.jsonObject },
                groupSelector = { content(it["group"]) },
                artifactSelector = { content(it["name"]) },
                versionSelector = { content(it["version"]) },
                description = "Nexus search",
                repositoryUrl = repository.url,
            )
        }.getOrElse {
            thisLogger().warn("Nexus Maven search failed for ${repository.url}", it)
            emptyList()
        }
    }

    private fun searchArtifactoryMaven(
        query: String,
        repository: RepositorySpec,
        backend: MavenRepositorySearchBackend.ARTIFACTORY,
    ): List<PackageSearchResult> {
        val gav = gavParts(query)
        return if (gav != null) {
            searchArtifactoryGavc(repository, backend, gav.first, gav.second)
        } else {
            searchArtifactoryArtifactId(repository, backend, query)
        }
    }

    private fun searchArtifactoryGavc(
        repository: RepositorySpec,
        backend: MavenRepositorySearchBackend.ARTIFACTORY,
        groupId: String,
        artifactId: String,
    ): List<PackageSearchResult> {
        val url = buildString {
            append(backend.baseUrl)
            append("/api/search/gavc?g=")
            append(encode(groupId))
            append("&a=")
            append(encode(artifactId))
            append("&specific=true")
            backend.repositoryKey?.let {
                append("&repos=")
                append(encode(it))
            }
        }
        return runCatching {
            val response = get(url)
            val items = parseArtifactoryItems(response)
            val latestVersion = items
                .mapNotNull { content(it["version"]) }
                .maxWithOrNull(VersionComparator::compare)
            if (latestVersion == null) {
                emptyList()
            } else {
                listOf(
                    PackageSearchResult(
                        ecosystem = Ecosystem.MAVEN,
                        group = groupId,
                        name = artifactId,
                        latestVersion = latestVersion,
                        description = "Artifactory GAVC search",
                        repositoryUrl = repository.url,
                    )
                )
            }
        }.getOrElse {
            thisLogger().warn("Artifactory GAVC search failed for ${repository.url}", it)
            emptyList()
        }
    }

    private fun searchArtifactoryArtifactId(
        repository: RepositorySpec,
        backend: MavenRepositorySearchBackend.ARTIFACTORY,
        query: String,
    ): List<PackageSearchResult> {
        val url = buildString {
            append(backend.baseUrl)
            append("/api/search/gavc?a=")
            append(encode(query))
            append("&specific=true")
            backend.repositoryKey?.let {
                append("&repos=")
                append(encode(it))
            }
        }
        return runCatching {
            val response = get(url)
            val items = parseArtifactoryItems(response)
            val grouped = items
                .mapNotNull { item ->
                    val downloadUrl = content(item["downloadUri"]) ?: content(item["downloadUrl"]) ?: return@mapNotNull null
                    val coordinates = parseArtifactoryDownloadUrl(downloadUrl) ?: return@mapNotNull null
                    coordinates to content(item["version"])
                }
                .groupBy({ it.first }, { it.second })
            grouped.map { (coordinates, versions) ->
                PackageSearchResult(
                    ecosystem = Ecosystem.MAVEN,
                    group = coordinates.first,
                    name = coordinates.second,
                    latestVersion = versions.filterNotNull().maxWithOrNull(VersionComparator::compare),
                    description = "Artifactory GAVC search",
                    repositoryUrl = repository.url,
                )
            }.sortedBy { it.displayName }
        }.getOrElse {
            thisLogger().warn("Artifactory artifact search failed for ${repository.url}", it)
            emptyList()
        }
    }

    private fun searchNpm(query: String, repositories: List<RepositorySpec>): List<PackageSearchResult> {
        val repository = repositories.firstOrNull() ?: defaultRepository(Ecosystem.NPM)
        if (!repository.url.contains("npmjs.org")) {
            val exact = fetchLatestNpmVersion(repository, query)
            return if (exact.status == LookupStatus.OK) {
                listOf(PackageSearchResult(Ecosystem.NPM, null, query, exact.latestStable, "Private registry exact match", repository.url))
            } else {
                emptyList()
            }
        }
        val url = "${repository.url.trimEnd('/')}/-/v1/search?text=${encode(query)}&size=20"
        return runCatching {
            val response = get(url)
            val objects = json.parseToJsonElement(response).jsonObject["objects"]?.jsonArray.orEmpty()
            objects.mapNotNull { item ->
                val pkg = item.jsonObject["package"]?.jsonObject ?: return@mapNotNull null
                PackageSearchResult(
                    ecosystem = Ecosystem.NPM,
                    group = null,
                    name = content(pkg["name"]) ?: return@mapNotNull null,
                    latestVersion = content(pkg["version"]),
                    description = content(pkg["description"]),
                    repositoryUrl = repository.url,
                )
            }
        }.getOrElse {
            thisLogger().warn("NPM search failed", it)
            emptyList()
        }
    }

    private fun searchPython(query: String): List<PackageSearchResult> {
        val exact = fetchLatestPythonVersion(defaultRepository(Ecosystem.PYTHON), query)
        return if (exact.status == LookupStatus.OK) {
            listOf(PackageSearchResult(Ecosystem.PYTHON, null, query, exact.latestStable, "Exact match from PyPI", exact.repositoryUrl))
        } else {
            emptyList()
        }
    }

    private fun searchRust(query: String): List<PackageSearchResult> {
        val url = "https://crates.io/api/v1/crates?page=1&per_page=20&q=${encode(query)}"
        return runCatching {
            val response = get(url)
            val crates = json.parseToJsonElement(response).jsonObject["crates"]?.jsonArray.orEmpty()
            crates.mapNotNull { item ->
                val obj = item.jsonObject
                PackageSearchResult(
                    ecosystem = Ecosystem.RUST,
                    group = null,
                    name = content(obj["id"]) ?: return@mapNotNull null,
                    latestVersion = content(obj["max_stable_version"]) ?: content(obj["max_version"]),
                    description = content(obj["description"]),
                    repositoryUrl = "https://crates.io/",
                )
            }
        }.getOrElse {
            thisLogger().warn("Crates search failed", it)
            emptyList()
        }
    }

    private fun fetchLatestMavenVersion(repository: RepositorySpec, dependency: DependencyCoordinate): VersionInfo {
        val groupId = dependency.group ?: return VersionInfo(
            latestStable = null,
            latestAvailable = null,
            repositoryUrl = repository.url,
            publishedAt = null,
            status = LookupStatus.NOT_FOUND,
        )
        return fetchLatestMavenVersion(repository, groupId, dependency.name)
    }

    private fun fetchLatestMavenVersion(repository: RepositorySpec, groupId: String, artifactId: String): VersionInfo {
        val groupPath = groupId.replace('.', '/')
        val metadataUrl = "${repository.url}$groupPath/$artifactId/maven-metadata.xml"
        return runCatching {
            val body = get(metadataUrl)
            val latest = Regex("<latest>([^<]+)</latest>").find(body)?.groupValues?.get(1)
            val release = Regex("<release>([^<]+)</release>").find(body)?.groupValues?.get(1)
            val versions = Regex("<version>([^<]+)</version>").findAll(body).map { it.groupValues[1] }.toList()
            val stable = listOfNotNull(release, latest, VersionComparator.newestStable(versions)).firstOrNull()
            val publishedAt = Regex("<lastUpdated>([^<]+)</lastUpdated>").find(body)?.groupValues?.get(1)?.let(::formatMavenTimestamp)
            VersionInfo(stable, latest ?: stable, repository.url, publishedAt, LookupStatus.OK)
        }.getOrElse { error ->
            mapFailure(repository.url, error)
        }
    }

    private fun fetchLatestNpmVersion(repository: RepositorySpec, packageName: String): VersionInfo {
        val url = "${repository.url.trimEnd('/')}/${encodeNpmPackage(packageName)}"
        return runCatching {
            val body = get(url)
            val root = json.parseToJsonElement(body).jsonObject
            val latestStable = content(root["dist-tags"]?.jsonObject?.get("latest"))
            val latestAvailable = root["versions"]?.jsonObject?.keys
                ?.maxWithOrNull(VersionComparator::compare)
                ?: latestStable
            val publishedAt = content(root["time"]?.jsonObject?.get(latestAvailable ?: latestStable ?: ""))
            VersionInfo(latestStable, latestAvailable, repository.url, publishedAt, LookupStatus.OK)
        }.getOrElse { error ->
            mapFailure(repository.url, error)
        }
    }

    private fun fetchLatestPythonVersion(repository: RepositorySpec, packageName: String): VersionInfo {
        val base = repository.url.trimEnd('/')
        val url = if (base.endsWith("/simple")) "$base/$packageName/" else "$base/pypi/$packageName/json"
        return runCatching {
            val body = get(url)
            val (latestStable, latestAvailable) = when {
                url.endsWith("/json") -> {
                    val root = json.parseToJsonElement(body).jsonObject
                    val infoVersion = content(root["info"]?.jsonObject?.get("version"))
                    val releases = root["releases"]?.jsonObject?.keys.orEmpty().toList()
                    val stable = VersionComparator.newestStable(releases) ?: infoVersion
                    val available = releases.maxWithOrNull(VersionComparator::compare) ?: infoVersion
                    stable to available
                }
                else -> {
                    val versions = Regex("""$packageName-([0-9A-Za-z.\-]+)\.""", RegexOption.IGNORE_CASE)
                        .findAll(body)
                        .map { it.groupValues[1] }
                        .toList()
                    (VersionComparator.newestStable(versions) ?: versions.maxWithOrNull(VersionComparator::compare)) to
                        versions.maxWithOrNull(VersionComparator::compare)
                }
            }
            val publishedAt = if (url.endsWith("/json")) {
                val root = json.parseToJsonElement(body).jsonObject
                val selected = latestAvailable ?: latestStable ?: ""
                root["releases"]?.jsonObject?.get(selected)?.jsonArray?.firstOrNull()?.jsonObject?.let { release ->
                    content(release["upload_time_iso_8601"]) ?: content(release["upload_time"])
                }
            } else {
                null
            }
            VersionInfo(latestStable, latestAvailable, repository.url, publishedAt, LookupStatus.OK)
        }.getOrElse { error ->
            mapFailure(repository.url, error)
        }
    }

    private fun fetchLatestRustVersion(repository: RepositorySpec, packageName: String): VersionInfo {
        val url = "${repository.url.trimEnd('/')}/api/v1/crates/${encode(packageName)}"
        return runCatching {
            val body = get(url)
            val crate = json.parseToJsonElement(body).jsonObject["crate"]?.jsonObject
            val latestStable = content(crate?.get("max_stable_version"))
            val latestAvailable = content(crate?.get("max_version")) ?: latestStable
            val publishedAt = content(crate?.get("updated_at"))
            VersionInfo(latestStable, latestAvailable, repository.url, publishedAt, LookupStatus.OK)
        }.getOrElse { error ->
            mapFailure(repository.url, error)
        }
    }

    private fun applyPolicy(info: VersionInfo, policy: LatestVersionPolicy): VersionInfo {
        val preferred = when (policy) {
            LatestVersionPolicy.RELEASE_ONLY -> info.latestStable ?: info.latestAvailable
            LatestVersionPolicy.INCLUDE_PRERELEASE -> info.latestAvailable ?: info.latestStable
        }
        return info.copy(latestStable = preferred, latestAvailable = info.latestAvailable ?: info.latestStable)
    }

    private fun formatMavenTimestamp(value: String): String {
        return runCatching {
            LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                .atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        }.getOrElse {
            value
        }
    }

    private fun get(url: String): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json, text/plain, */*")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return when (response.statusCode()) {
            in 200..299 -> response.body()
            401, 403 -> throw UnauthorizedException(url)
            429 -> throw RateLimitedException(url)
            else -> throw IllegalStateException("HTTP ${response.statusCode()} for $url")
        }
    }

    private fun mapFailure(repositoryUrl: String, error: Throwable): VersionInfo = when (error) {
        is UnauthorizedException -> VersionInfo(
            latestStable = null,
            latestAvailable = null,
            repositoryUrl = repositoryUrl,
            publishedAt = null,
            status = LookupStatus.UNAUTHORIZED,
            message = "Authentication required",
        )
        is RateLimitedException -> VersionInfo(
            latestStable = null,
            latestAvailable = null,
            repositoryUrl = repositoryUrl,
            publishedAt = null,
            status = LookupStatus.RATE_LIMITED,
            message = "Repository rate limit",
        )
        else -> {
            thisLogger().warn("Package lookup failed for $repositoryUrl", error)
            VersionInfo(
                latestStable = null,
                latestAvailable = null,
                repositoryUrl = repositoryUrl,
                publishedAt = null,
                status = LookupStatus.ERROR,
                message = error.message,
            )
        }
    }

    private fun aggregateMavenSearchResults(
        items: List<JsonObject>,
        groupSelector: (JsonObject) -> String?,
        artifactSelector: (JsonObject) -> String?,
        versionSelector: (JsonObject) -> String?,
        description: String,
        repositoryUrl: String,
    ): List<PackageSearchResult> {
        return items
            .mapNotNull { item ->
                val group = groupSelector(item) ?: return@mapNotNull null
                val artifact = artifactSelector(item) ?: return@mapNotNull null
                Triple(group, artifact, versionSelector(item))
            }
            .groupBy({ it.first to it.second }, { it.third })
            .map { (coordinates, versions) ->
                PackageSearchResult(
                    ecosystem = Ecosystem.MAVEN,
                    group = coordinates.first,
                    name = coordinates.second,
                    latestVersion = versions.filterNotNull().maxWithOrNull(VersionComparator::compare),
                    description = description,
                    repositoryUrl = repositoryUrl,
                )
            }
            .sortedBy { it.displayName }
    }

    private fun parseArtifactoryItems(response: String): List<JsonObject> {
        val element = json.parseToJsonElement(response)
        return when {
            runCatching { element.jsonArray }.isSuccess -> element.jsonArray.map { it.jsonObject }
            else -> element.jsonObject["results"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        }
    }

    private fun parseArtifactoryDownloadUrl(downloadUrl: String): Pair<String, String>? {
        val uri = URI.create(downloadUrl)
        val segments = uri.path.split('/').filter(String::isNotBlank)
        val artifactoryIndex = segments.indexOf("artifactory")
        if (artifactoryIndex == -1 || segments.size <= artifactoryIndex + 4) {
            return null
        }
        val gavSegments = segments.drop(artifactoryIndex + 2)
        if (gavSegments.size < 4) {
            return null
        }
        val artifactId = gavSegments[gavSegments.size - 3]
        val groupSegments = gavSegments.dropLast(3)
        if (artifactId.isBlank() || groupSegments.isEmpty()) {
            return null
        }
        return groupSegments.joinToString(".") to artifactId
    }

    private fun gavParts(query: String): Pair<String, String>? {
        val parts = query.split(':', limit = 2)
        if (parts.size != 2 || parts.any(String::isBlank)) {
            return null
        }
        return parts[0] to parts[1]
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun encodeNpmPackage(value: String): String =
        value.split("/").joinToString("/") { encode(it) }

    private fun content(element: JsonElement?): String? = runCatching { element?.jsonPrimitive?.content }.getOrNull()

    private fun defaultRepository(ecosystem: Ecosystem): RepositorySpec = when (ecosystem) {
        Ecosystem.MAVEN, Ecosystem.GRADLE -> RepositorySpec(ecosystem, "https://repo1.maven.org/maven2/", "default", true)
        Ecosystem.NPM -> RepositorySpec(ecosystem, "https://registry.npmjs.org/", "default", true)
        Ecosystem.PYTHON -> RepositorySpec(ecosystem, "https://pypi.org/", "default", false)
        Ecosystem.RUST -> RepositorySpec(ecosystem, "https://crates.io/", "default", true)
    }

    private class UnauthorizedException(url: String) : IllegalStateException(url)
    private class RateLimitedException(url: String) : IllegalStateException(url)
}

internal sealed interface MavenRepositorySearchBackend {
    data object CENTRAL : MavenRepositorySearchBackend
    data class NEXUS(val baseUrl: String, val repositoryKey: String) : MavenRepositorySearchBackend
    data class ARTIFACTORY(val baseUrl: String, val repositoryKey: String?) : MavenRepositorySearchBackend
    data object UNKNOWN : MavenRepositorySearchBackend

    companion object {
        fun from(url: String): MavenRepositorySearchBackend {
            val normalized = url.trimEnd('/')
            if (normalized.contains("search.maven.org") ||
                normalized.contains("repo1.maven.org") ||
                normalized.contains("repo.maven.apache.org")
            ) {
                return CENTRAL
            }
            val nexusMatch = Regex("""^(https?://.+?)/repository/([^/]+)$""").find(normalized)
            if (nexusMatch != null) {
                return NEXUS(
                    baseUrl = nexusMatch.groupValues[1],
                    repositoryKey = nexusMatch.groupValues[2],
                )
            }
            val artifactoryMatch = Regex("""^(https?://.+?/artifactory)(?:/([^/]+))?$""").find(normalized)
            if (artifactoryMatch != null) {
                return ARTIFACTORY(
                    baseUrl = artifactoryMatch.groupValues[1],
                    repositoryKey = artifactoryMatch.groupValues.getOrNull(2)?.takeIf(String::isNotBlank),
                )
            }
            return UNKNOWN
        }
    }
}
