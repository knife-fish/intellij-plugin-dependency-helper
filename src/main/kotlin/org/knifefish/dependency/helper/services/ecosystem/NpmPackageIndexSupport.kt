package org.knifefish.dependency.helper.services.ecosystem

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.knifefish.dependency.helper.model.*
import org.knifefish.dependency.helper.util.VersionComparator

class NpmPackageIndexSupport : PackageIndexEcosystemSupport {
    override val ecosystem: Ecosystem = Ecosystem.NPM

    override fun findLatestVersion(
        dependency: DependencyCoordinate,
        repositories: List<RepositorySpec>,
        context: PackageIndexContext,
    ): VersionInfo {
        val ordered = repositories.ifEmpty { listOf(context.defaultRepository(Ecosystem.NPM)) }
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
        val repository = repositories.firstOrNull() ?: context.defaultRepository(Ecosystem.NPM)
        if (!repository.url.contains("npmjs.org")) {
            val exact = fetchLatest(repository, query, context)
            return if (exact.status == LookupStatus.OK) {
                listOf(PackageSearchResult(Ecosystem.NPM, null, query, exact.latestStable, "Private registry exact match", repository.url))
            } else {
                emptyList()
            }
        }
        val url = "${repository.url.trimEnd('/')}/-/v1/search?text=${context.encode(query)}&size=20"
        return runCatching {
            val response = context.get(url)
            val objects = context.json.parseToJsonElement(response).jsonObject["objects"]?.jsonArray.orEmpty()
            objects.mapNotNull { item ->
                val pkg = item.jsonObject["package"]?.jsonObject ?: return@mapNotNull null
                PackageSearchResult(
                    ecosystem = Ecosystem.NPM,
                    group = null,
                    name = context.content(pkg["name"]) ?: return@mapNotNull null,
                    latestVersion = context.content(pkg["version"]),
                    description = context.content(pkg["description"]),
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
        val ordered = repositories.ifEmpty { listOf(context.defaultRepository(Ecosystem.NPM)) }
        ordered.forEach { repository ->
            val versions = runCatching {
                val url = "${repository.url.trimEnd('/')}/${context.encodeNpmPackage(name)}"
                val body = context.get(url)
                context.json.parseToJsonElement(body).jsonObject["versions"]?.jsonObject?.keys
                    ?.sortedWith(context.descendingVersionComparator)
                    ?.take(30)
                    ?: emptyList()
            }.getOrElse {
                thisLogger().warn("Version list lookup failed for ${repository.url}", it)
                emptyList()
            }
            if (versions.isNotEmpty()) return versions
        }
        return emptyList()
    }

    private fun fetchLatest(repository: RepositorySpec, packageName: String, context: PackageIndexContext): VersionInfo {
        val url = "${repository.url.trimEnd('/')}/${context.encodeNpmPackage(packageName)}"
        return runCatching {
            val body = context.get(url)
            val root = context.json.parseToJsonElement(body).jsonObject
            val latestStable = context.content(root["dist-tags"]?.jsonObject?.get("latest"))
            val latestAvailable = root["versions"]?.jsonObject?.keys?.maxWithOrNull(VersionComparator::compare) ?: latestStable
            val publishedAt = context.content(root["time"]?.jsonObject?.get(latestAvailable ?: latestStable ?: ""))
            VersionInfo(latestStable, latestAvailable, repository.url, publishedAt, LookupStatus.OK)
        }.getOrElse { error -> context.mapFailure(repository.url, error) }
    }
}
