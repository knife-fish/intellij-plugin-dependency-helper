package org.knifefish.dependency.helper.services.ecosystem

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.knifefish.dependency.helper.model.*
import org.knifefish.dependency.helper.services.MavenRepositorySearchBackend
import org.knifefish.dependency.helper.util.VersionComparator
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

class MavenPackageIndexSupport : AbstractMavenLikePackageIndexSupport(Ecosystem.MAVEN)
class GradlePackageIndexSupport : AbstractMavenLikePackageIndexSupport(Ecosystem.GRADLE)

abstract class AbstractMavenLikePackageIndexSupport(
    final override val ecosystem: Ecosystem,
) : PackageIndexEcosystemSupport {

    override fun findLatestVersion(
        dependency: DependencyCoordinate,
        repositories: List<RepositorySpec>,
        context: PackageIndexContext,
    ): VersionInfo {
        val ordered = repositories.ifEmpty { listOf(ecosystem.defaultRepository) }
        var unauthorized: VersionInfo? = null
        ordered.forEach { repository ->
            val result = fetchLatestMavenVersion(repository, dependency, context)
            if (result.status == LookupStatus.OK) return result
            if (result.status == LookupStatus.UNAUTHORIZED && unauthorized == null) unauthorized = result
        }
        return unauthorized ?: VersionInfo(null, null, ordered.firstOrNull()?.url, null, LookupStatus.NOT_FOUND, "No repository response")
    }

    override fun search(
        query: String,
        repositories: List<RepositorySpec>,
        context: PackageIndexContext,
    ): List<PackageSearchResult> {
        val candidates = repositories.ifEmpty { listOf(ecosystem.defaultRepository) }.distinctBy { it.url }
        candidates.forEach { repository ->
            val results = when (val backend = MavenRepositorySearchBackend.from(repository.url)) {
                MavenRepositorySearchBackend.CENTRAL -> searchMavenCentral(query, context)
                is MavenRepositorySearchBackend.NEXUS -> searchNexusMaven(query, repository, backend, context)
                is MavenRepositorySearchBackend.ARTIFACTORY -> searchArtifactoryMaven(query, repository, backend, context)
                MavenRepositorySearchBackend.UNKNOWN -> searchExactMavenInRepository(query, repository, context)
            }
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override fun availableVersions(
        group: String?,
        name: String,
        repositories: List<RepositorySpec>,
        context: PackageIndexContext,
    ): List<String> {
        if (group.isNullOrBlank()) return emptyList()
        val ordered = repositories.ifEmpty { listOf(ecosystem.defaultRepository) }
        ordered.forEach { repository ->
            val versions = runCatching {
                val metadataUrl = "${repository.url}${group.replace('.', '/')}/$name/maven-metadata.xml"
                val body = context.get(metadataUrl)
                parseMavenMetadata(body).versions
                    .asSequence()
                    .distinct()
                    .sortedWith(VersionComparator.DESCENDING)
                    .take(30)
                    .toList()
            }.getOrElse {
                thisLogger().warn("Version list lookup failed for ${repository.url}", it)
                emptyList()
            }
            if (versions.isNotEmpty()) return versions
        }
        return emptyList()
    }

    private fun fetchLatestMavenVersion(
        repository: RepositorySpec,
        dependency: DependencyCoordinate,
        context: PackageIndexContext,
    ): VersionInfo {
        val groupId = dependency.group ?: return VersionInfo(null, null, repository.url, null, LookupStatus.NOT_FOUND)
        return fetchLatestMavenVersion(repository, groupId, dependency.name, context)
    }

    private fun fetchLatestMavenVersion(
        repository: RepositorySpec,
        groupId: String,
        artifactId: String,
        context: PackageIndexContext,
    ): VersionInfo {
        val metadataUrl = "${repository.url}${groupId.replace('.', '/')}/$artifactId/maven-metadata.xml"
        return runCatching {
            val body = context.get(metadataUrl)
            val metadata = parseMavenMetadata(body)
            val stable = listOfNotNull(metadata.release, metadata.latest, VersionComparator.newestStable(metadata.versions)).firstOrNull()
            val publishedAt = metadata.lastUpdated?.let(::formatMavenTimestamp)
            VersionInfo(stable, metadata.latest ?: stable, repository.url, publishedAt, LookupStatus.OK)
        }.getOrElse { error -> context.mapFailure(repository.url, error) }
    }

    private fun searchMavenCentral(query: String, context: PackageIndexContext): List<PackageSearchResult> {
        val url = "https://search.maven.org/solrsearch/select?q=${URLEncoder.encode(query, StandardCharsets.UTF_8)}&rows=20&wt=json"
        return runCatching {
            context.getJson<MavenCentralSearchResponse>(url).response.docs.mapNotNull { item ->
                val group = item.group ?: return@mapNotNull null
                val artifact = item.artifact ?: return@mapNotNull null
                PackageSearchResult(ecosystem, group, artifact, item.latestVersion, null, "https://search.maven.org/")
            }
        }.getOrElse {
            thisLogger().warn("Maven search failed", it)
            emptyList()
        }
    }

    private fun searchExactMavenInRepository(
        query: String,
        repository: RepositorySpec,
        context: PackageIndexContext,
    ): List<PackageSearchResult> {
        val gav = gavParts(query) ?: return emptyList()
        val info = fetchLatestMavenVersion(repository, gav.first, gav.second, context)
        return if (info.status == LookupStatus.OK) {
            listOf(PackageSearchResult(ecosystem, gav.first, gav.second, info.latestStable, "Exact match from ${repository.url}", repository.url))
        } else {
            emptyList()
        }
    }

    private fun searchNexusMaven(
        query: String,
        repository: RepositorySpec,
        backend: MavenRepositorySearchBackend.NEXUS,
        context: PackageIndexContext,
    ): List<PackageSearchResult> {
        val params = mutableListOf("repository=${URLEncoder.encode(backend.repositoryKey, StandardCharsets.UTF_8)}", "format=maven2")
        val gav = gavParts(query)
        if (gav != null) {
            params += "group=${URLEncoder.encode(gav.first, StandardCharsets.UTF_8)}"
            params += "name=${URLEncoder.encode(gav.second, StandardCharsets.UTF_8)}"
        } else {
            params += "name=${URLEncoder.encode(query, StandardCharsets.UTF_8)}"
        }
        val url = "${backend.baseUrl}/service/rest/v1/search?${params.joinToString("&")}"
        return runCatching {
            val response = context.getJson<NexusSearchResponse>(url)
            aggregateMavenSearchResults(
                items = response.items,
                groupSelector = NexusSearchItem::group,
                artifactSelector = NexusSearchItem::name,
                versionSelector = NexusSearchItem::version,
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
        context: PackageIndexContext,
    ): List<PackageSearchResult> {
        val gav = gavParts(query)
        return if (gav != null) searchArtifactoryGavc(repository, backend, gav.first, gav.second, context)
        else searchArtifactoryArtifactId(repository, backend, query, context)
    }

    private fun searchArtifactoryGavc(
        repository: RepositorySpec,
        backend: MavenRepositorySearchBackend.ARTIFACTORY,
        groupId: String,
        artifactId: String,
        context: PackageIndexContext,
    ): List<PackageSearchResult> {
        val url = buildString {
            append(backend.baseUrl)
            append("/api/search/gavc?g=")
            append(URLEncoder.encode(groupId, StandardCharsets.UTF_8))
            append("&a=")
            append(URLEncoder.encode(artifactId, StandardCharsets.UTF_8))
            append("&specific=true")
            backend.repositoryKey?.let {
                append("&repos=")
                append(URLEncoder.encode(it, StandardCharsets.UTF_8))
            }
        }
        return runCatching {
            val items = parseArtifactoryItems(context.get(url), context)
            val latest = items.mapNotNull { it.version }.maxWithOrNull(VersionComparator::compare)
            if (latest == null) emptyList() else listOf(PackageSearchResult(ecosystem, groupId, artifactId, latest, "Artifactory GAVC search", repository.url))
        }.getOrElse {
            thisLogger().warn("Artifactory GAVC search failed for ${repository.url}", it)
            emptyList()
        }
    }

    private fun searchArtifactoryArtifactId(
        repository: RepositorySpec,
        backend: MavenRepositorySearchBackend.ARTIFACTORY,
        query: String,
        context: PackageIndexContext,
    ): List<PackageSearchResult> {
        val url = buildString {
            append(backend.baseUrl)
            append("/api/search/gavc?a=")
            append(URLEncoder.encode(query, StandardCharsets.UTF_8))
            append("&specific=true")
            backend.repositoryKey?.let {
                append("&repos=")
                append(URLEncoder.encode(it, StandardCharsets.UTF_8))
            }
        }
        return runCatching {
            val items = parseArtifactoryItems(context.get(url), context)
            val grouped = items.mapNotNull { item ->
                val downloadUrl = item.downloadUri ?: item.downloadUrl ?: return@mapNotNull null
                val coordinates = parseArtifactoryDownloadUrl(downloadUrl) ?: return@mapNotNull null
                coordinates to item.version
            }.groupBy({ it.first }, { it.second })
            grouped.map { (coordinates, versions) ->
                PackageSearchResult(
                    ecosystem = ecosystem,
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

    private fun <T> aggregateMavenSearchResults(
        items: List<T>,
        groupSelector: (T) -> String?,
        artifactSelector: (T) -> String?,
        versionSelector: (T) -> String?,
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
                    ecosystem = ecosystem,
                    group = coordinates.first,
                    name = coordinates.second,
                    latestVersion = versions.filterNotNull().maxWithOrNull(VersionComparator::compare),
                    description = description,
                    repositoryUrl = repositoryUrl,
                )
            }
            .sortedBy { it.displayName }
    }

    private fun parseArtifactoryItems(body: String, context: PackageIndexContext): List<ArtifactorySearchItem> {
        return runCatching { context.json.decodeFromString<List<ArtifactorySearchItem>>(body) }
            .getOrElse { context.json.decodeFromString<ArtifactorySearchResponse>(body).results }
    }

    private fun parseArtifactoryDownloadUrl(downloadUrl: String): Pair<String, String>? {
        val uri = URI.create(downloadUrl)
        val segments = uri.path.split('/').filter(String::isNotBlank)
        val artifactoryIndex = segments.indexOf("artifactory")
        if (artifactoryIndex == -1 || segments.size <= artifactoryIndex + 4) return null
        val gavSegments = segments.drop(artifactoryIndex + 2)
        if (gavSegments.size < 4) return null
        val artifactId = gavSegments[gavSegments.size - 3]
        val groupSegments = gavSegments.dropLast(3)
        if (artifactId.isBlank() || groupSegments.isEmpty()) return null
        return groupSegments.joinToString(".") to artifactId
    }

    private fun gavParts(query: String): Pair<String, String>? {
        val parts = query.split(':', limit = 2)
        if (parts.size != 2 || parts.any(String::isBlank)) return null
        return parts[0] to parts[1]
    }

    private fun formatMavenTimestamp(value: String): String {
        return runCatching {
            LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                .atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        }.getOrElse { value }
    }
}

private data class MavenMetadata(
    val latest: String?,
    val release: String?,
    val versions: List<String>,
    val lastUpdated: String?,
)

@Serializable
private data class MavenCentralSearchResponse(
    val response: MavenCentralResponse = MavenCentralResponse(),
)

@Serializable
private data class MavenCentralResponse(
    val docs: List<MavenCentralDocument> = emptyList(),
)

@Serializable
private data class MavenCentralDocument(
    @SerialName("g")
    val group: String? = null,
    @SerialName("a")
    val artifact: String? = null,
    val latestVersion: String? = null,
)

@Serializable
private data class NexusSearchResponse(
    val items: List<NexusSearchItem> = emptyList(),
)

@Serializable
private data class NexusSearchItem(
    val group: String? = null,
    val name: String? = null,
    val version: String? = null,
)

@Serializable
private data class ArtifactorySearchResponse(
    val results: List<ArtifactorySearchItem> = emptyList(),
)

@Serializable
private data class ArtifactorySearchItem(
    val version: String? = null,
    val downloadUri: String? = null,
    val downloadUrl: String? = null,
)

private fun parseMavenMetadata(xml: String): MavenMetadata {
    val document = runCatching { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream()) }.getOrNull()
    val root = document?.documentElement
    fun first(tag: String): String? {
        val nodes = root?.getElementsByTagName(tag) ?: return null
        if (nodes.length == 0) return null
        return nodes.item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
    }
    val versions = mutableListOf<String>()
    val versionNodes = root?.getElementsByTagName("version")
    if (versionNodes != null) {
        for (i in 0 until versionNodes.length) {
            versionNodes.item(i)?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let(versions::add)
        }
    }
    return MavenMetadata(first("latest"), first("release"), versions, first("lastUpdated"))
}
