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
                "react": "18.3.1"
              },
              "devDependencies": {
                "vite": "5.4.1"
              }
            }
        """.trimIndent())

        val dependencies = scanner.scan(file, file.content.toString())

        assertEquals(2, dependencies.size)
        assertTrue(dependencies.any { it.name == "react" && it.version == "18.3.1" })
        assertTrue(dependencies.any { it.name == "vite" && it.version == "5.4.1" })
    }

    @Test
    fun scansCargoDependencies() {
        val file = LightVirtualFile("Cargo.toml", """
            [dependencies]
            serde = "1.0.216"
            tokio = { version = "1.42.0", features = ["full"] }
        """.trimIndent())

        val dependencies = scanner.scan(file, file.content.toString())

        assertEquals(2, dependencies.size)
        assertTrue(dependencies.any { it.name == "serde" })
        assertTrue(dependencies.any { it.name == "tokio" && it.version == "1.42.0" })
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
            dependencies = scanned,
        )

        assertEquals("3.3.2", enriched.first { it.name == "ktor-server-core" }.version)
        assertEquals("1.5.18", enriched.first { it.name == "logback-classic" }.version)
        assertEquals("2.2.21", enriched.first { it.name == "kotlin-test-junit" }.version)
    }
}
