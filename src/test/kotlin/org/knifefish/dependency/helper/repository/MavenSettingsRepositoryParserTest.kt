package org.knifefish.dependency.helper.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MavenSettingsRepositoryParserTest {

    @Test
    fun parsesMirrorAndActiveProfileRepositories() {
        val settings = """
            <settings>
              <mirrors>
                <mirror>
                  <id>corp-mirror</id>
                  <mirrorOf>*</mirrorOf>
                  <url>http://maven.local/repository/maven-public</url>
                </mirror>
              </mirrors>
              <activeProfiles>
                <activeProfile>corp</activeProfile>
              </activeProfiles>
              <profiles>
                <profile>
                  <id>corp</id>
                  <repositories>
                    <repository>
                      <id>releases</id>
                      <url>http://maven.local/repository/releases</url>
                    </repository>
                  </repositories>
                </profile>
              </profiles>
            </settings>
        """.trimIndent()
        val file = Files.createTempFile("settings", ".xml")
        Files.writeString(file, settings)

        val repositories = MavenSettingsRepositoryParser.parse(file)

        assertEquals(
            listOf(
                "http://maven.local/repository/maven-public/",
                "http://maven.local/repository/releases/",
            ),
            repositories,
        )
    }

    @Test
    fun usesActiveByDefaultProfilesWhenNoExplicitActiveProfilesExist() {
        val settings = """
            <settings>
              <profiles>
                <profile>
                  <id>default-repo</id>
                  <activation>
                    <activeByDefault>true</activeByDefault>
                  </activation>
                  <repositories>
                    <repository>
                      <id>default</id>
                      <url>http://maven.local/repository/default</url>
                    </repository>
                  </repositories>
                </profile>
              </profiles>
            </settings>
        """.trimIndent()
        val file = Files.createTempFile("settings-default", ".xml")
        Files.writeString(file, settings)

        val repositories = MavenSettingsRepositoryParser.parse(file)

        assertTrue(repositories.contains("http://maven.local/repository/default/"))
    }

    @Test
    fun pomParserUsesRepositoriesButIgnoresDistributionManagement() {
        val pom = """
            <project>
              <repositories>
                <repository>
                  <id>public</id>
                  <url>http://maven.local/repository/public</url>
                </repository>
              </repositories>
              <distributionManagement>
                <repository>
                  <id>releases</id>
                  <url>http://maven.local/repository/releases</url>
                </repository>
                <snapshotRepository>
                  <id>snapshots</id>
                  <url>http://maven.local/repository/snapshots</url>
                </snapshotRepository>
              </distributionManagement>
            </project>
        """.trimIndent()

        val repositories = MavenPomRepositoryParser.parse(pom)

        assertEquals(listOf("http://maven.local/repository/public/"), repositories)
    }
}
