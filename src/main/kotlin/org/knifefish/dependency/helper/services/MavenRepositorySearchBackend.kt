package org.knifefish.dependency.helper.services

import java.net.URI

sealed interface MavenRepositorySearchBackend {
    data object CENTRAL : MavenRepositorySearchBackend
    data class NEXUS(val baseUrl: String, val repositoryKey: String) : MavenRepositorySearchBackend
    data class ARTIFACTORY(val baseUrl: String, val repositoryKey: String?) : MavenRepositorySearchBackend
    data object UNKNOWN : MavenRepositorySearchBackend

    companion object {
        fun from(url: String): MavenRepositorySearchBackend {
            val normalized = url.trimEnd('/')
            if (normalized.contains("search.maven.org") || normalized.contains("repo1.maven.org") || normalized.contains("repo.maven.apache.org")) {
                return CENTRAL
            }
            val nexusParsed = parseNexusRepository(normalized)
            if (nexusParsed != null) return NEXUS(nexusParsed.first, nexusParsed.second)
            val artifactoryParsed = parseArtifactoryRepository(normalized)
            if (artifactoryParsed != null) return ARTIFACTORY(artifactoryParsed.first, artifactoryParsed.second)
            return UNKNOWN
        }

        private fun parseNexusRepository(url: String): Pair<String, String>? {
            val uri = runCatching { URI.create(url) }.getOrNull() ?: return null
            val parts = uri.path.split('/').filter(String::isNotBlank)
            val repoIndex = parts.indexOf("repository")
            if (repoIndex < 0 || repoIndex + 1 >= parts.size) return null
            val key = parts[repoIndex + 1]
            if (key.isBlank()) return null
            val basePath = parts.take(repoIndex).joinToString("/", prefix = "/")
            val base = "${uri.scheme}://${uri.authority}$basePath".trimEnd('/')
            return base to key
        }

        private fun parseArtifactoryRepository(url: String): Pair<String, String?>? {
            val uri = runCatching { URI.create(url) }.getOrNull() ?: return null
            val parts = uri.path.split('/').filter(String::isNotBlank)
            val artIndex = parts.indexOf("artifactory")
            if (artIndex < 0) return null
            val basePath = parts.take(artIndex + 1).joinToString("/", prefix = "/")
            val key = parts.getOrNull(artIndex + 1)?.takeIf(String::isNotBlank)
            val base = "${uri.scheme}://${uri.authority}$basePath".trimEnd('/')
            return base to key
        }
    }
}
