package org.knifefish.dependency.helper.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class GradleCatalogSettingsParserTest {

    @Test
    fun parsesSingleLineRemoteCatalogDeclaration() {
        val settings = """
            dependencyResolutionManagement {
                repositories {
                    mavenCentral()
                }
                versionCatalogs {
                    create("ktorLibs").from("io.ktor:ktor-version-catalog:3.4.0")
                }
            }
        """.trimIndent()

        assertEquals(
            mapOf("ktorLibs" to "io.ktor:ktor-version-catalog:3.4.0"),
            parseSettingsCatalogMappings(Paths.get("/project"), settings),
        )
    }

    @Test
    fun parsesBlockCatalogDeclaration() {
        val settings = """
            dependencyResolutionManagement {
                versionCatalogs {
                    create("appLibs") {
                        from(files("gradle/app.versions.toml"))
                    }
                }
            }
        """.trimIndent()

        assertEquals(
            mapOf("appLibs" to "/project/gradle/app.versions.toml"),
            parseSettingsCatalogMappings(Paths.get("/project"), settings),
        )
    }
}
