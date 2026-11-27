package org.knifefish.dependency.helper.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MavenRepositorySearchBackendTest {

    @Test
    fun testDetectsNexusRepositoryBackend() {
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
    fun testDetectsArtifactoryRepositoryBackend() {
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
    fun testDetectsCentralRepositoryBackend() {
        val backend = MavenRepositorySearchBackend.from("https://repo1.maven.org/maven2/")

        assertTrue(backend === MavenRepositorySearchBackend.CENTRAL)
    }
}
