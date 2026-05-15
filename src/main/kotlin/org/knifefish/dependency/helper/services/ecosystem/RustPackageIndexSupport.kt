package org.knifefish.dependency.helper.services.ecosystem

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.knifefish.dependency.helper.model.*

class RustPackageIndexSupport : PackageIndexEcosystemSupport {
    override val ecosystem: Ecosystem = Ecosystem.RUST

    override fun findLatestVersion(
        dependency: DependencyCoordinate,
        repositories: List<RepositorySpec>,
        context: PackageIndexContext,
    ): VersionInfo {
        val ordered = repositories.ifEmpty { listOf(context.defaultRepository(Ecosystem.RUST)) }
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
        val url = "https://crates.io/api/v1/crates?page=1&per_page=20&q=${context.encode(query)}"
        return runCatching {
            val response = context.get(url)
            val crates = context.json.parseToJsonElement(response).jsonObject["crates"]?.jsonArray.orEmpty()
            crates.mapNotNull { item ->
                val obj = item.jsonObject
                PackageSearchResult(
                    ecosystem = Ecosystem.RUST,
                    group = null,
                    name = context.content(obj["id"]) ?: return@mapNotNull null,
                    latestVersion = context.content(obj["max_stable_version"]) ?: context.content(obj["max_version"]),
                    description = context.content(obj["description"]),
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
        val ordered = repositories.ifEmpty { listOf(context.defaultRepository(Ecosystem.RUST)) }
        ordered.forEach { repository ->
            val versions = runCatching {
                val url = "${repository.url.trimEnd('/')}/api/v1/crates/${context.encode(name)}/versions"
                val body = context.get(url)
                context.json.parseToJsonElement(body).jsonObject["versions"]?.jsonArray
                    ?.mapNotNull { context.content(it.jsonObject["num"]) }
                    ?.sortedWith(context.descendingVersionComparator)
                    ?.take(30)
                    ?: emptyList()
            }.getOrElse { emptyList() }
            if (versions.isNotEmpty()) return versions
        }
        return emptyList()
    }

    private fun fetchLatest(repository: RepositorySpec, packageName: String, context: PackageIndexContext): VersionInfo {
        val url = "${repository.url.trimEnd('/')}/api/v1/crates/${context.encode(packageName)}"
        return runCatching {
            val body = context.get(url)
            val crate = context.json.parseToJsonElement(body).jsonObject["crate"]?.jsonObject
            val latestStable = context.content(crate?.get("max_stable_version"))
            val latestAvailable = context.content(crate?.get("max_version")) ?: latestStable
            val publishedAt = context.content(crate?.get("updated_at"))
            VersionInfo(latestStable, latestAvailable, repository.url, publishedAt, LookupStatus.OK)
        }.getOrElse { error -> context.mapFailure(repository.url, error) }
    }
}
