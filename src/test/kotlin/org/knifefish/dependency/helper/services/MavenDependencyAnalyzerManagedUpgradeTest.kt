package org.knifefish.dependency.helper.services

import com.intellij.testFramework.LightVirtualFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.Ecosystem

class MavenDependencyAnalyzerManagedUpgradeTest {

    @Test
    fun offersParentUpgradeForPluginManagedByParentPluginManagement() {
        val appProject = LightVirtualFile("app-pom.xml", "<project/>")
        val dependency = DependencyCoordinate(
            ecosystem = Ecosystem.MAVEN,
            group = "org.apache.maven.plugins",
            name = "maven-compiler-plugin",
            version = "3.12.1",
            declaredVersion = "3.12.1",
            scope = MavenDeclarationCollector.PLUGIN_SCOPE,
            file = appProject,
            declarationText = "",
            lineNumber = 1,
            versionRange = null,
        )
        val parent = PomReference("com.acme", "build-parent", "1.0.0")

        val targets = collectManagedUpgradeTargets(
            dependency = dependency,
            descriptors = listOf(
                ManagedProjectDescriptor(
                    file = appProject,
                    descriptor = ProjectDescriptor(
                        parent = parent,
                        importedBoms = emptyList(),
                    ),
                ),
            ),
            parentRecursivelyManages = { candidate, managed -> candidate == parent && managed.name == "maven-compiler-plugin" },
            bomRecursivelyManages = { _, _ -> false },
            workspaceParentDelegates = { _, _ -> false },
        )

        assertEquals(1, targets.size)
        val target = targets.single()
        assertEquals(ManagedUpgradeTargetKind.PARENT, target.kind)
        assertEquals(appProject, target.file)
        assertEquals("com.acme", target.groupId)
        assertEquals("build-parent", target.artifactId)
        assertEquals("1.0.0", target.currentVersion)
    }

    @Test
    fun prefersDirectWorkspaceBomOverParentWhenParentOnlyDelegates() {
        val moduleA = LightVirtualFile("moduleA-pom.xml", "<project/>")
        val moduleB = LightVirtualFile("moduleB-pom.xml", "<project/>")
        val dependency = DependencyCoordinate(
            ecosystem = Ecosystem.MAVEN,
            group = "org.springframework.boot",
            name = "spring-boot-starter-web",
            version = "4.0.1",
            declaredVersion = null,
            scope = "compile",
            file = moduleA,
            declarationText = "",
            lineNumber = 1,
            versionRange = null,
        )
        val moduleBParent = PomReference("com.acme", "module-b-parent", "1.0.0")
        val moduleCBom = PomReference("com.acme", "module-c-bom", "1.2.0")

        val targets = collectManagedUpgradeTargets(
            dependency = dependency,
            descriptors = listOf(
                ManagedProjectDescriptor(
                    file = moduleA,
                    descriptor = ProjectDescriptor(
                        parent = moduleBParent,
                        importedBoms = emptyList(),
                    ),
                ),
                ManagedProjectDescriptor(
                    file = moduleB,
                    descriptor = ProjectDescriptor(
                        parent = null,
                        importedBoms = listOf(moduleCBom),
                    ),
                ),
            ),
            parentRecursivelyManages = { parent, _ -> parent == moduleBParent },
            bomRecursivelyManages = { bom, _ -> bom == moduleCBom },
            workspaceParentDelegates = { parent, _ -> parent == moduleBParent },
        )

        assertEquals(1, targets.size)
        val target = targets.single()
        assertEquals(ManagedUpgradeTargetKind.BOM, target.kind)
        assertEquals(moduleB, target.file)
        assertEquals("com.acme", target.groupId)
        assertEquals("module-c-bom", target.artifactId)
        assertEquals("1.2.0", target.currentVersion)
        assertTrue(target.label.contains("module-c-bom"))
    }

    @Test
    fun prefersWorkspaceBomProjectWhenParentAndBomLiveInDifferentWorkspaceProjects() {
        val appProject = LightVirtualFile("app-pom.xml", "<project/>")
        val parentProject = LightVirtualFile("parent-pom.xml", "<project/>")
        val bomProject = LightVirtualFile("bom-pom.xml", "<project/>")
        val dependency = DependencyCoordinate(
            ecosystem = Ecosystem.MAVEN,
            group = "org.springframework.boot",
            name = "spring-boot-starter-json",
            version = "4.0.1",
            declaredVersion = null,
            scope = "compile",
            file = appProject,
            declarationText = "",
            lineNumber = 1,
            versionRange = null,
        )
        val workspaceParent = PomReference("com.acme.workspace", "shared-parent", "2.0.0")
        val workspaceBom = PomReference("com.acme.workspace", "platform-bom", "2.5.0")

        val targets = collectManagedUpgradeTargets(
            dependency = dependency,
            descriptors = listOf(
                ManagedProjectDescriptor(
                    file = appProject,
                    descriptor = ProjectDescriptor(
                        parent = workspaceParent,
                        importedBoms = emptyList(),
                    ),
                ),
                ManagedProjectDescriptor(
                    file = parentProject,
                    descriptor = ProjectDescriptor(
                        parent = null,
                        importedBoms = listOf(workspaceBom),
                    ),
                ),
                ManagedProjectDescriptor(
                    file = bomProject,
                    descriptor = ProjectDescriptor(
                        parent = null,
                        importedBoms = emptyList(),
                    ),
                ),
            ),
            parentRecursivelyManages = { parent, _ -> parent == workspaceParent },
            bomRecursivelyManages = { bom, _ -> bom == workspaceBom },
            workspaceParentDelegates = { parent, _ -> parent == workspaceParent },
        )

        assertEquals(1, targets.size)
        val target = targets.single()
        assertEquals(ManagedUpgradeTargetKind.BOM, target.kind)
        assertEquals(parentProject, target.file)
        assertEquals("com.acme.workspace", target.groupId)
        assertEquals("platform-bom", target.artifactId)
        assertEquals("2.5.0", target.currentVersion)
        assertTrue(target.label.contains("platform-bom"))
    }
}
