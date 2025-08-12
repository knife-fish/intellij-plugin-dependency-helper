package org.knifefish.dependency.helper.model

import com.intellij.openapi.vfs.VirtualFile

enum class Ecosystem(val displayName: String) {
    MAVEN("Maven"),
    GRADLE("Gradle"),
    NPM("Node.js"),
    PYTHON("Python"),
    RUST("Rust");

    companion object {
        fun fromDisplayName(name: String): Ecosystem? = entries.firstOrNull { it.displayName == name }
    }
}

data class TextRangeMarker(
    val startOffset: Int,
    val endOffset: Int,
)

data class DependencyCoordinate(
    val ecosystem: Ecosystem,
    val group: String?,
    val name: String,
    val version: String,
    val declaredVersion: String? = version,
    val scope: String?,
    val file: VirtualFile,
    val declarationText: String,
    val lineNumber: Int,
    val versionRange: TextRangeMarker?,
    val displayRange: TextRangeMarker = versionRange ?: TextRangeMarker(0, 0),
    val inspectionRange: TextRangeMarker = displayRange,
) {
    val displayName: String
        get() = if (group.isNullOrBlank()) name else "$group:$name"

    val key: String
        get() = "${ecosystem.name}:${group.orEmpty()}:$name"

    val usesManagedVersion: Boolean
        get() = ecosystem == Ecosystem.MAVEN && declaredVersion == null
}

data class RepositorySpec(
    val ecosystem: Ecosystem,
    val url: String,
    val source: String,
    val supportsSearch: Boolean,
)

data class VersionInfo(
    val latestStable: String?,
    val latestAvailable: String?,
    val repositoryUrl: String?,
    val publishedAt: String? = null,
    val status: LookupStatus,
    val message: String? = null,
) {
    val isUpgradeAvailable: Boolean
        get() = latestStable != null && latestStable != latestAvailable && latestAvailable != null
}

enum class LookupStatus {
    OK,
    NOT_FOUND,
    UNAUTHORIZED,
    RATE_LIMITED,
    ERROR,
}

enum class LatestVersionPolicy(val displayName: String) {
    RELEASE_ONLY("Release only"),
    INCLUDE_PRERELEASE("Include snapshot/prerelease");

    override fun toString(): String = displayName
}

data class PackageSearchResult(
    val ecosystem: Ecosystem,
    val group: String?,
    val name: String,
    val latestVersion: String?,
    val description: String?,
    val repositoryUrl: String?,
) {
    val displayName: String
        get() = if (group.isNullOrBlank()) name else "$group:$name"
}

data class DependencySnapshot(
    val dependencies: List<DependencyCoordinate>,
    val repositories: Map<Ecosystem, List<RepositorySpec>>,
) {
    fun conflicts(): Map<String, List<DependencyCoordinate>> =
        dependencies.groupBy { it.key }.filterValues { values -> values.map { it.version }.distinct().size > 1 }
}

data class DependencyLookupResult(
    val dependency: DependencyCoordinate,
    val versionInfo: VersionInfo,
)

data class MavenDependencyNodeView(
    val ownerProjectName: String,
    val ownerProjectFile: VirtualFile,
    val groupId: String,
    val artifactId: String,
    val version: String,
    val scope: String?,
    val packaging: String?,
    val path: List<String>,
    val sourceDependency: DependencyCoordinate?,
    val children: MutableList<MavenDependencyNodeView> = mutableListOf(),
) {
    val key: String
        get() = "$groupId:$artifactId"

    val displayName: String
        get() = "$groupId:$artifactId:$version"

    val isTestScope: Boolean
        get() = scope.equals("test", ignoreCase = true)
}
