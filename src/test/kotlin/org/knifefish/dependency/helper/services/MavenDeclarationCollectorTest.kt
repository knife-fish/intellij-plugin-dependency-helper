package org.knifefish.dependency.helper.services

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MavenDeclarationCollectorTest : BasePlatformTestCase() {

    fun testCollectsDependenciesAndPluginsFromSameModel() {
        val psiFile = myFixture.configureByText(
            "pom.xml",
            """
            <project>
              <dependencies>
                <dependency>
                  <groupId>org.example</groupId>
                  <artifactId>example-api</artifactId>
                  <version>1.0.0</version>
                </dependency>
              </dependencies>
              <build>
                <plugins>
                  <plugin>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.2.5</version>
                  </plugin>
                </plugins>
              </build>
            </project>
            """.trimIndent(),
        )

        val declarations = MavenDeclarationCollector.collect(project, psiFile.virtualFile, includePlugins = true)

        assertEquals(2, declarations.size)
        assertEquals("example-api", declarations[0].name)
        assertEquals("maven-surefire-plugin", declarations[1].name)
        assertEquals(MavenDeclarationCollector.PLUGIN_SCOPE, declarations[1].scope)
    }

    fun testScansBuildPluginsWithDefaultGroupId() {
        val psiFile = myFixture.configureByText(
            "pom.xml",
            """
            <project>
              <build>
                <plugins>
                  <plugin>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.12.1</version>
                  </plugin>
                </plugins>
              </build>
            </project>
            """.trimIndent(),
        )

        val plugins = MavenDeclarationCollector.collectPlugins(project, psiFile.virtualFile)

        assertEquals(1, plugins.size)
        assertEquals("org.apache.maven.plugins", plugins.single().group)
        assertEquals("maven-compiler-plugin", plugins.single().name)
        assertEquals("3.12.1", plugins.single().version)
        assertEquals("3.12.1", plugins.single().declaredVersion)
        assertEquals(MavenDeclarationCollector.PLUGIN_SCOPE, plugins.single().scope)
    }

    fun testScansPluginManagementAndResolvesProperties() {
        val psiFile = myFixture.configureByText(
            "pom.xml",
            """
            <project>
              <properties>
                <shade.version>3.5.1</shade.version>
              </properties>
              <build>
                <pluginManagement>
                  <plugins>
                    <plugin>
                      <groupId>org.apache.maven.plugins</groupId>
                      <artifactId>maven-shade-plugin</artifactId>
                      <version>${'$'}{shade.version}</version>
                    </plugin>
                  </plugins>
                </pluginManagement>
              </build>
            </project>
            """.trimIndent(),
        )

        val plugins = MavenDeclarationCollector.collectPlugins(project, psiFile.virtualFile)

        assertEquals(1, plugins.size)
        assertEquals("maven-shade-plugin", plugins.single().name)
        assertEquals("3.5.1", plugins.single().version)
        assertEquals("${'$'}{shade.version}", plugins.single().declaredVersion)
        assertEquals("pluginManagement", plugins.single().scope)
    }

    fun testScansPluginWithoutVersionWhenManagedByPluginManagement() {
        val psiFile = myFixture.configureByText(
            "pom.xml",
            """
            <project>
              <build>
                <pluginManagement>
                  <plugins>
                    <plugin>
                      <artifactId>maven-compiler-plugin</artifactId>
                      <version>3.12.1</version>
                    </plugin>
                  </plugins>
                </pluginManagement>
                <plugins>
                  <plugin>
                    <artifactId>maven-compiler-plugin</artifactId>
                  </plugin>
                </plugins>
              </build>
            </project>
            """.trimIndent(),
        )

        val plugins = MavenDeclarationCollector.collectPlugins(project, psiFile.virtualFile)
        val declaration = plugins.single { it.scope == MavenDeclarationCollector.PLUGIN_SCOPE }

        assertEquals("org.apache.maven.plugins", declaration.group)
        assertEquals("maven-compiler-plugin", declaration.name)
        assertEquals("3.12.1", declaration.version)
        assertEquals("3.12.1", declaration.declaredVersion)
        assertEquals(null, declaration.versionRange)
        assertEquals(declaration.displayRange.startOffset, declaration.displayRange.endOffset)
    }
}
