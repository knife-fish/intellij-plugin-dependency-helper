package org.knifefish.dependency.helper.services.ecosystem

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.knifefish.dependency.helper.model.*
import org.knifefish.dependency.helper.util.VersionComparator
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class NpmPackageIndexSupport : PackageIndexEcosystemSupport {
    override val ecosystem: Ecosystem = Ecosystem.NPM

    override fun findLatestVersion(
        dependency: DependencyCoordinate,
        repositories: List<RepositorySpec>,
        context: PackageIndexContext,
    ): VersionInfo {
        val ordered = repositories.ifEmpty { listOf(Ecosystem.NPM.defaultRepository) }
        var unauthorized: VersionInfo? = null
        ordered.forEach { repository ->
            val result = fetchLatest(repository, dependency.name, context)
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
        val repository = repositories.firstOrNull() ?: Ecosystem.NPM.defaultRepository
        if (!repository.url.contains("npmjs.org")) {
            val exact = fetchLatest(repository, query, context)
            return if (exact.status == LookupStatus.OK) {
                listOf(PackageSearchResult(Ecosystem.NPM, null, query, exact.latestStable, "Private registry exact match", repository.url))
            } else {
                emptyList()
            }
        }
        val url = "${repository.url.trimEnd('/')}/-/v1/search?text=${URLEncoder.encode(query, StandardCharsets.UTF_8)}&size=20"
        return runCatching {
            context.getJson<NpmSearchResponse>(url).objects.mapNotNull { item ->
                val pkg = item.pkg ?: return@mapNotNull null
                PackageSearchResult(
                    ecosystem = Ecosystem.NPM,
                    group = null,
                    name = pkg.name ?: return@mapNotNull null,
                    latestVersion = pkg.version,
                    description = pkg.description,
                    repositoryUrl = repository.url,
                )
            }
        }.getOrElse {
            thisLogger().warn("NPM search failed", it)
            emptyList()
        }
    }

    override fun availableVersions(
        group: String?,
        name: String,
        repositories: List<RepositorySpec>,
        context: PackageIndexContext,
    ): List<String> {
        val ordered = repositories.ifEmpty { listOf(Ecosystem.NPM.defaultRepository) }
        ordered.forEach { repository ->
            val versions = runCatching {
                val url = "${repository.url.trimEnd('/')}/${encodeNpmPackage(name)}"
                context.getJson<NpmPackageMetadataResponse>(url).versions.keys
                    .sortedWith(VersionComparator.DESCENDING)
                    .take(30)
            }.getOrElse {
                thisLogger().warn("Version list lookup failed for ${repository.url}", it)
                emptyList()
            }
            if (versions.isNotEmpty()) return versions
        }
        return emptyList()
    }

    private fun fetchLatest(repository: RepositorySpec, packageName: String, context: PackageIndexContext): VersionInfo {
        val url = "${repository.url.trimEnd('/')}/${encodeNpmPackage(packageName)}"
        return runCatching {
            val root = context.getJson<NpmPackageMetadataResponse>(url)
            val latestStable = root.distTags.latest
            val latestAvailable = root.versions.keys.maxWithOrNull(VersionComparator::compare) ?: latestStable
            val publishedAt = root.time[latestAvailable ?: latestStable ?: ""]
            VersionInfo(latestStable, latestAvailable, repository.url, publishedAt, LookupStatus.OK)
        }.getOrElse { error -> context.mapFailure(repository.url, error) }
    }

    private fun encodeNpmPackage(value: String): String =
        value.split("/").joinToString("/") { URLEncoder.encode(it, StandardCharsets.UTF_8) }

}

@Serializable
private data class NpmSearchResponse(
    val objects: List<NpmSearchObject> = emptyList(),
)

@Serializable
private data class NpmSearchObject(
    @SerialName("package")
    val pkg: NpmSearchPackage? = null,
)

@Serializable
private data class NpmSearchPackage(
    val name: String? = null,
    val version: String? = null,
    val description: String? = null,
)

@Serializable
private data class NpmPackageMetadataResponse(
    @SerialName("dist-tags")
    val distTags: NpmDistTags = NpmDistTags(),
    val versions: Map<String, NpmVersionMetadata> = emptyMap(),
    val time: Map<String, String> = emptyMap(),
)

@Serializable
private data class NpmDistTags(
    val latest: String? = null,
)

@Serializable
private data class NpmVersionMetadata(
    val version: String? = null,
)
