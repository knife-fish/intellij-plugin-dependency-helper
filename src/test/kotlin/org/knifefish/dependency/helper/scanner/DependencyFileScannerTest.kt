package org.knifefish.dependency.helper.scanner

import com.intellij.testFramework.LightVirtualFile
import junit.framework.TestCase.*
import org.junit.Test
import org.knifefish.dependency.helper.services.enrichGradleDependencies

class DependencyFileScannerTest {

    private val scanner = DependencyFileScanner()

    @Test
    fun scansMavenDependencies() {
        val file = LightVirtualFile("pom.xml", """
            <project>
              <dependencies>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <version>5.10.2</version>
                </dependency>
              </dependencies>
            </project>
        """.trimIndent())

        val dependencies = scanner.scan(file, file.content.toString())

        assertEquals(1, dependencies.size)
        assertEquals("org.junit.jupiter", dependencies.first().group)
        assertEquals("junit-jupiter", dependencies.first().name)
        assertEquals("5.10.2", dependencies.first().version)
    }

    @Test
    fun scansMavenDependenciesWithoutDeclaredVersion() {
        val file = LightVirtualFile("pom.xml", """
            <project>
              <dependencies>
                <dependency>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-starter-web</artifactId>
                </dependency>
              </dependencies>
            </project>
        """.trimIndent())

        val dependencies = scanner.scan(file, file.content.toString())

        assertEquals(1, dependencies.size)
        assertEquals("spring-boot-starter-web", dependencies.first().name)
        assertEquals("", dependencies.first().version)
        assertEquals(null, dependencies.first().declaredVersion)
    }

    @Test
    fun scansPackageJsonDependencies() {
        val file = LightVirtualFile("package.json", """
            {
              "dependencies": {
                "react": "^18.3.1"
              },
              "devDependencies": {
                "vite": "5.4.1"
              },
              "overrides": {
                "lodash": "4.17.21"
              },
              "resolutions": {
                "chalk": "5.3.0"
              },
              "pnpm": {
                "overrides": {
                  "debug": "4.3.7"
                }
              }
            }
        """.trimIndent())

        val dependencies = scanner.scan(file, file.content.toString())

        assertTrue(dependencies.size >= 5)
        val react = dependencies.firstOrNull { it.name == "react" } ?: error("react not found")
        assertEquals("^18.3.1", react.version)
        assertTrue(file.content.toString().substring(react.inspectionRange.startOffset, react.inspectionRange.endOffset).contains("\"react\": \"^18.3.1\""))
        assertTrue(dependencies.any { it.name == "vite" && it.version == "5.4.1" })
        assertTrue(dependencies.any { it.name == "lodash" && it.scope == "overrides" })
        assertTrue(dependencies.any { it.name == "chalk" && it.scope == "resolutions" })
        assertTrue(dependencies.any { it.name == "debug" && it.scope == "pnpm.overrides" })
    }

    @Test
    fun scansCargoDependencies() {
        val file = LightVirtualFile("Cargo.toml", """
            [dependencies]
            serde = "1.0.216"
            tokio = { version = "1.42.0", features = ["full"] }

            [dev-dependencies]
            tempfile = "3.14.0"

            [target.'cfg(unix)'.dependencies]
            nix = "0.29.0"
        """.trimIndent())

        val dependencies = scanner.scan(file, file.content.toString())

        assertEquals(4, dependencies.size)
        assertTrue(dependencies.none { it.name == "dependencies" || it.name == "dev-dependencies" || it.name == "build-dependencies" })
        assertTrue(dependencies.any { it.name == "serde" })
        assertTrue(dependencies.any { it.name == "tokio" && it.version == "1.42.0" })
        assertTrue(dependencies.any { it.name == "tempfile" && it.scope == "dev-dependencies" })
        assertTrue(dependencies.any { it.name == "nix" && it.scope == "target-dependencies" })
    }

    @Test
    fun scansPyprojectOptionalAndPoetryGroupDependencies() {
        val file = LightVirtualFile("pyproject.toml", """
            [project]
            dependencies = [
              "fastapi>=0.115.0",
            ]

            [project.optional-dependencies]
            dev = [
              "pytest>=8.3.0",
            ]

            [tool.poetry.group.docs.dependencies]
            mkdocs = "^1.6.1"
        """.trimIndent())

        val dependencies = scanner.scan(file, file.content.toString())
        assertTrue(dependencies.any { it.name == "fastapi" && it.version == "0.115.0" })
        assertTrue(dependencies.any { it.name == "pytest" && it.version == "8.3.0" && it.scope == "optional-dev" })
        assertTrue(dependencies.any { it.name == "mkdocs" && it.version == "^1.6.1" && it.scope == "poetry-group" })
    }

    @Test
    fun scansGradleDependenciesWithAndWithoutDeclaredVersion() {
        val text = """
            plugins {
                kotlin("jvm") version "2.2.21"
                id("io.ktor.plugin") version "3.3.2"
            }

            dependencies {
                implementation("io.ktor:ktor-server-core")
                implementation("ch.qos.logback:logback-classic:${'$'}logback_version")
                testImplementation("org.jetbrains.kotlin:kotlin-test-junit:${'$'}kotlin_version")
            }
        """.trimIndent()
        val file = LightVirtualFile("build.gradle.kts", text)

        val dependencies = scanner.scan(file, text)

        assertEquals(3, dependencies.size)
        val ktor = dependencies.first { it.name == "ktor-server-core" }
        assertEquals("", ktor.version)
        assertNull(ktor.declaredVersion)
        assertNull(ktor.versionRange)

        val logback = dependencies.first { it.name == "logback-classic" }
        assertEquals("${'$'}logback_version", logback.declaredVersion)
    }

    @Test
    fun enrichesGradleDependenciesFromPropertiesAndPlugins() {
        val text = """
            val kotlin_version: String by project
            val logback_version: String by project

            plugins {
                kotlin("jvm") version "2.2.21"
                id("io.ktor.plugin") version "3.3.2"
            }

            dependencies {
                implementation("io.ktor:ktor-server-core")
                implementation("ch.qos.logback:logback-classic:${'$'}logback_version")
                testImplementation("org.jetbrains.kotlin:kotlin-test-junit:${'$'}kotlin_version")
            }
        """.trimIndent()
        val file = LightVirtualFile("build.gradle.kts", text)
        val scanned = scanner.scan(file, text)

        val enriched = enrichGradleDependencies(
            buildText = text,
            gradlePropertiesText = """
                kotlin_version=2.2.21
                logback_version=1.5.18
            """.trimIndent(),
            versionCatalogs = emptyMap(),
            dependencies = scanned,
        )

        assertEquals("3.3.2", enriched.first { it.name == "ktor-server-core" }.version)
        assertEquals("1.5.18", enriched.first { it.name == "logback-classic" }.version)
        assertEquals("2.2.21", enriched.first { it.name == "kotlin-test-junit" }.version)
    }

    @Test
    fun scansAndEnrichesGradleVersionCatalogAliases() {
        val text = """
            dependencies {
                implementation(libs.logback.classic)
                testImplementation(libs.kotlin.test.junit)
            }
        """.trimIndent()
        val file = LightVirtualFile("build.gradle.kts", text)

        val scanned = scanner.scan(file, text)

        assertEquals(2, scanned.size)
        assertEquals("libs.logback.classic", scanned.first { it.name == "classic" }.declaredVersion)

        val enriched = enrichGradleDependencies(
            buildText = text,
            gradlePropertiesText = null,
            versionCatalogs = mapOf(
                "libs" to org.knifefish.dependency.helper.services.GradleVersionCatalogSource(
                    "libs",
                    file,
                    """
                        [versions]
                        logback = "1.5.18"
                        kotlin = "2.2.21"

                        [libraries]
                        logback-classic = { module = "ch.qos.logback:logback-classic", version.ref = "logback" }
                        kotlin-test-junit = { group = "org.jetbrains.kotlin", name = "kotlin-test-junit", version.ref = "kotlin" }
                    """.trimIndent(),
                ),
            ),
            dependencies = scanned,
        )

        val logback = enriched.first { it.declaredVersion == "libs.logback.classic" }
        assertEquals("ch.qos.logback", logback.group)
        assertEquals("logback-classic", logback.name)
        assertEquals("1.5.18", logback.version)

        val kotlinTest = enriched.first { it.declaredVersion == "libs.kotlin.test.junit" }
        assertEquals("org.jetbrains.kotlin", kotlinTest.group)
        assertEquals("kotlin-test-junit", kotlinTest.name)
        assertEquals("2.2.21", kotlinTest.version)
    }

    @Test
    fun enrichesGradleBundlesAndCustomCatalogs() {
        val text = """
            dependencies {
                implementation(toolset.bundles.ktor)
            }
        """.trimIndent()
        val file = LightVirtualFile("build.gradle.kts", text)
        val scanned = scanner.scan(file, text)

        val enriched = enrichGradleDependencies(
            buildText = text,
            gradlePropertiesText = null,
            versionCatalogs = mapOf(
                "toolset" to org.knifefish.dependency.helper.services.GradleVersionCatalogSource(
                    "toolset",
                    file,
                    """
                        [versions]
                        ktor = "3.3.2"

                        [libraries]
                        ktor-server-core = { module = "io.ktor:ktor-server-core", version.ref = "ktor" }
                        ktor-server-netty = { module = "io.ktor:ktor-server-netty", version.ref = "ktor" }

                        [bundles]
                        ktor = ["ktor-server-core", "ktor-server-netty"]
                    """.trimIndent(),
                ),
            ),
            dependencies = scanned,
        )

        assertEquals(2, enriched.size)
        assertTrue(enriched.any { it.group == "io.ktor" && it.name == "ktor-server-core" && it.version == "3.3.2" })
        assertTrue(enriched.any { it.group == "io.ktor" && it.name == "ktor-server-netty" && it.version == "3.3.2" })
    }

    @Test
    fun enrichesGradlePluginAliasesFromVersionCatalogs() {
        val text = """
            plugins {
                alias(libs.plugins.kotlin.jvm)
                alias(ktorLibs.plugins.ktor)
            }
        """.trimIndent()
        val file = LightVirtualFile("build.gradle.kts", text)
        val scanned = scanner.scan(file, text)

        assertEquals(2, scanned.size)

        val enriched = enrichGradleDependencies(
            buildText = text,
            gradlePropertiesText = null,
            versionCatalogs = mapOf(
                "libs" to org.knifefish.dependency.helper.services.GradleVersionCatalogSource(
                    "libs",
                    file,
                    """
                        [versions]
                        kotlin = "2.2.21"

                        [plugins]
                        kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
                    """.trimIndent(),
                ),
                "ktorLibs" to org.knifefish.dependency.helper.services.GradleVersionCatalogSource(
                    "ktorLibs",
                    file,
                    """
                        [versions]
                        ktor = "3.3.2"

                        [plugins]
                        ktor = { id = "io.ktor.plugin", version.ref = "ktor" }
                    """.trimIndent(),
                ),
            ),
            dependencies = scanned,
        )

        val kotlinPlugin = enriched.first { it.declaredVersion == "libs.plugins.kotlin.jvm" }
        assertNull(kotlinPlugin.group)
        assertEquals("org.jetbrains.kotlin.jvm", kotlinPlugin.name)
        assertEquals("2.2.21", kotlinPlugin.version)

        val ktorPlugin = enriched.first { it.declaredVersion == "ktorLibs.plugins.ktor" }
        assertNull(ktorPlugin.group)
        assertEquals("io.ktor.plugin", ktorPlugin.name)
        assertEquals("3.3.2", ktorPlugin.version)
    }
}
