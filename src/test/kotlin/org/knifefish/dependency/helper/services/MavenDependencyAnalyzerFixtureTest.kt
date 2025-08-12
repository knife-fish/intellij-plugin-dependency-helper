package org.knifefish.dependency.helper.services

import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.Ecosystem
import java.nio.file.Path

class MavenDependencyAnalyzerFixtureTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = Path.of("").toAbsolutePath().resolve("src/test/testData").toString()

    fun testResolvesImportedBomVersionFromPomPropertyAndSelectsBomTarget() {
        myFixture.copyDirectoryToProject("multi-modules", "multi-modules")

        val moduleAPom = myFixture.findFileInTempDir("multi-modules/module-a/pom.xml")
        val moduleDependenciesPom = myFixture.findFileInTempDir("multi-modules/module-dependencies/pom.xml")

        val moduleDependenciesXml = PsiManager.getInstance(project).findFile(moduleDependenciesPom) as XmlFile

        val moduleDependenciesDescriptor = parseProjectDescriptor(moduleDependenciesXml)

        assertNotNull(moduleDependenciesDescriptor)
        assertEquals(
            "2.3.12.RELEASE",
            moduleDependenciesDescriptor!!.importedBoms.single { it.artifactId == "spring-boot-dependencies" }.version,
        )

        val dependency = DependencyCoordinate(
            ecosystem = Ecosystem.MAVEN,
            group = "org.springframework.boot",
            name = "spring-boot-starter-logging",
            version = "2.3.12.RELEASE",
            declaredVersion = null,
            scope = "compile",
            file = moduleAPom,
            declarationText = "",
            lineNumber = 1,
            versionRange = null,
        )

        val targets = collectManagedUpgradeTargets(
            dependency = dependency,
            descriptors = listOf(
                ManagedProjectDescriptor(
                    file = moduleAPom,
                    descriptor = parseProjectDescriptor(PsiManager.getInstance(project).findFile(moduleAPom) as XmlFile)!!,
                ),
                ManagedProjectDescriptor(
                    file = moduleDependenciesPom,
                    descriptor = moduleDependenciesDescriptor,
                ),
            ),
            parentRecursivelyManages = { parent, _ ->
                parent.groupId == "com.example.boot" && parent.artifactId == "module-dependencies"
            },
            bomRecursivelyManages = { bom, _ ->
                bom.groupId == "org.springframework.boot" && bom.artifactId == "spring-boot-dependencies"
            },
            workspaceParentDelegates = { parent, _ ->
                parent.groupId == "com.example.boot" && parent.artifactId == "module-dependencies"
            },
        )

        val bomTargets = targets.filter { it.kind == ManagedUpgradeTargetKind.BOM }
        assertEquals(1, bomTargets.size)
        assertEquals(moduleDependenciesPom.path, bomTargets.single().file.path)
        assertEquals("spring-boot-dependencies", bomTargets.single().artifactId)
        assertEquals("2.3.12.RELEASE", bomTargets.single().currentVersion)
        assertTrue(bomTargets.single().label.contains("spring-boot-dependencies"))
    }
}
