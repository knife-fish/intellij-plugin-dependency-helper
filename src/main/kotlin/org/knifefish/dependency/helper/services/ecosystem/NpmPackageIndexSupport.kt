package org.knifefish.dependency.helper.services.ecosystem

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
