package org.knifefish.dependency.helper.services.ecosystem

import com.intellij.openapi.extensions.ExtensionPointName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.knifefish.dependency.helper.model.*

interface PackageIndexEcosystemSupport {
    val ecosystem: Ecosystem

    fun findLatestVersion(
        dependency: DependencyCoordinate,
        repositories: List<RepositorySpec>,
        context: PackageIndexContext,
    ): VersionInfo

    fun search(
        query: String,
        repositories: List<RepositorySpec>,
        context: PackageIndexContext,
    ): List<PackageSearchResult>

    fun availableVersions(
        group: String?,
        name: String,
        repositories: List<RepositorySpec>,
        context: PackageIndexContext,
    ): List<String>

    companion object {
        val EP_NAME: ExtensionPointName<PackageIndexEcosystemSupport> =
            ExtensionPointName.create("org.knifefish.dependency.helper.packageIndexSupport")
    }
}

data class PackageIndexContext(
    val json: Json,
    val descendingVersionComparator: Comparator<String>,
    val get: (String) -> String,
    val mapFailure: (String, Throwable) -> VersionInfo,
    val defaultRepository: (Ecosystem) -> RepositorySpec,
    val encode: (String) -> String,
    val encodeNpmPackage: (String) -> String,
    val content: (JsonElement?) -> String?,
)
