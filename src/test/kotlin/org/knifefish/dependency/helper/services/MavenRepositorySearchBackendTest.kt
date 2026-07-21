package org.knifefish.dependency.helper.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MavenRepositorySearchBackendTest {

    @Test
    fun detectsNexusRepositoryBackend() {
        val backend = MavenRepositorySearchBackend.from("http://nexus.local/repository/maven-public/")

        assertEquals(
            MavenRepositorySearchBackend.NEXUS(
                baseUrl = "http://nexus.local",
                repositoryKey = "maven-public",
            ),
            backend,
        )
    }

    @Test
    fun detectsArtifactoryRepositoryBackend() {
        val backend = MavenRepositorySearchBackend.from("https://repo.local/artifactory/libs-release/")

        assertEquals(
            MavenRepositorySearchBackend.ARTIFACTORY(
                baseUrl = "https://repo.local/artifactory",
                repositoryKey = "libs-release",
            ),
            backend,
        )
    }

    @Test
    fun detectsCentralRepositoryBackend() {
        val backend = MavenRepositorySearchBackend.from("https://repo1.maven.org/maven2/")

        assertTrue(backend === MavenRepositorySearchBackend.CENTRAL)
    }
}
