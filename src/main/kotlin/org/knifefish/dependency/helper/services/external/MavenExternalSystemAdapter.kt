package org.knifefish.dependency.helper.services.external

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.Ecosystem
import org.knifefish.dependency.helper.model.MavenDependencyNodeView
import org.knifefish.dependency.helper.services.MavenSupport

internal class MavenExternalSystemAdapter : ExternalDependencySystem {
    override val ecosystem: Ecosystem = Ecosystem.MAVEN

    override fun supports(file: VirtualFile): Boolean = file.name == "pom.xml"

    override fun supportsAnalyzer(file: VirtualFile): Boolean = supports(file) && ecosystem.supportsAnalyzer

    override fun scan(project: Project, file: VirtualFile): List<DependencyCoordinate> {
        val support = project.getService(MavenSupport::class.java) ?: return emptyList()
        val out = mutableListOf<DependencyCoordinate>()
        support.analyze().forEach { root -> collect(root, file, out) }
        return out
    }

    override fun enrich(project: Project, file: VirtualFile, dependencies: List<DependencyCoordinate>): List<DependencyCoordinate> {
        val support = project.getService(MavenSupport::class.java) ?: return dependencies
        return support.enrichDependencies(file, dependencies)
    }

    override fun refresh(project: Project, file: VirtualFile, afterRefresh: (() -> Unit)?): Boolean {
        val support = project.getService(MavenSupport::class.java) ?: return false
        support.refreshMavenProject(file, afterRefresh)
        return true
    }

    override fun upgrade(project: Project, dependency: DependencyCoordinate, newVersion: String): Boolean {
        if (!dependency.usesManagedVersion) return false
        val support = project.getService(MavenSupport::class.java) ?: return false
        return support.upgradeManagedDependency(dependency, newVersion)
    }

    private fun collect(node: MavenDependencyNodeView, file: VirtualFile, out: MutableList<DependencyCoordinate>) {
        node.sourceDependency?.takeIf { it.file.path == file.path }?.let(out::add)
        node.children.forEach { collect(it, file, out) }
    }
}
