package org.knifefish.dependency.helper.services.ecosystem

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.knifefish.dependency.helper.model.*
import org.knifefish.dependency.helper.util.VersionComparator
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class RustPackageIndexSupport : PackageIndexEcosystemSupport {
    override val ecosystem: Ecosystem = Ecosystem.RUST

    override fun findLatestVersion(
        dependency: DependencyCoordinate,
        repositories: List<RepositorySpec>,
        context: PackageIndexContext,
    ): VersionInfo {
        val ordered = repositories.ifEmpty { listOf(Ecosystem.RUST.defaultRepository) }
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
        val url = "https://crates.io/api/v1/crates?page=1&per_page=20&q=${URLEncoder.encode(query, StandardCharsets.UTF_8)}"
        return runCatching {
            context.getJson<CratesSearchResponse>(url).crates.mapNotNull { crate ->
                PackageSearchResult(
                    ecosystem = Ecosystem.RUST,
                    group = null,
                    name = crate.id ?: return@mapNotNull null,
                    latestVersion = crate.maxStableVersion ?: crate.maxVersion,
                    description = crate.description,
                    repositoryUrl = "https://crates.io/",
                )
            }
        }.getOrElse { emptyList() }
    }

    override fun availableVersions(
        group: String?,
        name: String,
        repositories: List<RepositorySpec>,
        context: PackageIndexContext,
    ): List<String> {
        val ordered = repositories.ifEmpty { listOf(Ecosystem.RUST.defaultRepository) }
        ordered.forEach { repository ->
            val versions = runCatching {
                val url = "${repository.url.trimEnd('/')}/api/v1/crates/${URLEncoder.encode(name, StandardCharsets.UTF_8)}/versions"
                context.getJson<CrateVersionsResponse>(url).versions
                    .mapNotNull { it.num }
                    .sortedWith(VersionComparator.DESCENDING)
                    .take(30)
            }.getOrElse { emptyList() }
            if (versions.isNotEmpty()) return versions
        }
        return emptyList()
    }

    private fun fetchLatest(repository: RepositorySpec, packageName: String, context: PackageIndexContext): VersionInfo {
        val url = "${repository.url.trimEnd('/')}/api/v1/crates/${URLEncoder.encode(packageName, StandardCharsets.UTF_8)}/versions"
        return runCatching {
            val crate = context.getJson<CrateVersionsResponse>(url).crate
            val latestStable = crate?.maxStableVersion
            val latestAvailable = crate?.maxVersion ?: latestStable
            val publishedAt = crate?.updatedAt
            VersionInfo(latestStable, latestAvailable, repository.url, publishedAt, LookupStatus.OK)
        }.getOrElse { error -> context.mapFailure(repository.url, error) }
    }
}

@Serializable
private data class CratesSearchResponse(
    val crates: List<CrateSummary> = emptyList(),
)

@Serializable
private data class CrateVersionsResponse(
    val crate: CrateSummary? = null,
    val versions: List<CrateVersion> = emptyList(),
)

@Serializable
private data class CrateSummary(
    val id: String? = null,
    @SerialName("max_stable_version")
    val maxStableVersion: String? = null,
    @SerialName("max_version")
    val maxVersion: String? = null,
    val description: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null,
)

@Serializable
private data class CrateVersion(
    val num: String? = null,
)
