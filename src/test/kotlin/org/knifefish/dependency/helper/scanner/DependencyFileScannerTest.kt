package org.knifefish.dependency.helper.scanner

import com.intellij.testFramework.LightVirtualFile
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Test

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
}
