package org.knifefish.dependency.helper.services.external

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.Ecosystem
import org.knifefish.dependency.helper.model.MavenDependencyNodeView
import org.knifefish.dependency.helper.services.GradleSupport

/** Gradle 外部系统适配器，负责 Gradle 文件的依赖处理流程。 */
internal class GradleExternalSystemAdapter : ExternalDependencySystem {
    /** 当前适配器生态。 */
    override val ecosystem: Ecosystem = Ecosystem.GRADLE

    /** 支持 build/settings 两类 Gradle 脚本。 */
    override fun supports(file: VirtualFile): Boolean =
        file.name == "build.gradle" ||
            file.name == "build.gradle.kts" ||
            file.name == "settings.gradle" ||
            file.name == "settings.gradle.kts"

    override fun supportsAnalyzer(file: VirtualFile): Boolean = supports(file) && ecosystem.supportsAnalyzer

    /** 调用 GradleSupport 解析依赖树，并提取源依赖。 */
    override fun scan(project: Project, file: VirtualFile): List<DependencyCoordinate> {
        val support = project.getService(GradleSupport::class.java) ?: return emptyList()
        val declared = support.declaredDependencies(file)
        if (declared.isNotEmpty()) {
            return declared
        }
        val out = mutableListOf<DependencyCoordinate>()
        support.analyze(file).forEach { root -> collect(root, file, out) }
        return out
    }

    /** 对 Gradle 依赖做版本/属性/目录等补全。 */
    override fun enrich(project: Project, file: VirtualFile, dependencies: List<DependencyCoordinate>): List<DependencyCoordinate> {
        val support = project.getService(GradleSupport::class.java) ?: return dependencies
        return support.enrichDependencies(file, dependencies)
    }

    /** 触发 Gradle 同步刷新。 */
    override fun refresh(project: Project, file: VirtualFile, afterRefresh: (() -> Unit)?): Boolean {
        val support = project.getService(GradleSupport::class.java) ?: return false
        support.refreshGradleProject(file, afterRefresh)
        return true
    }

    /** 执行 Gradle 依赖升级。 */
    override fun upgrade(project: Project, dependency: DependencyCoordinate, newVersion: String): Boolean {
        val support = project.getService(GradleSupport::class.java) ?: return false
        return support.upgradeDependency(dependency, newVersion)
    }

    /** 深度遍历依赖树，收集属于当前文件的源依赖。 */
    private fun collect(node: MavenDependencyNodeView, file: VirtualFile, out: MutableList<DependencyCoordinate>) {
        node.sourceDependency?.takeIf { it.file.path == file.path }?.let(out::add)
        node.children.forEach { collect(it, file, out) }
    }
}
