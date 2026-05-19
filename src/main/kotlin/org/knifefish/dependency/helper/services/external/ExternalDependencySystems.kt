package org.knifefish.dependency.helper.services.external

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.knifefish.dependency.helper.model.DependencyCoordinate

/**
 * 外部依赖系统调度器。
 * 按文件类型选择合适的适配器并转发调用。
 */
internal class ExternalDependencySystems(private val project: Project) {

    // 扩展点可能在运行时变化，按需读取而不是缓存，避免拿到旧实例。
    private val systems: List<ExternalDependencySystem>
        get() = ExternalDependencySystem.EP_NAME.extensionList

    /** 判断是否存在可处理该文件的外部系统适配器。 */
    fun supports(file: VirtualFile): Boolean = systems.any { it.supports(file) }

    /** 扫描文件依赖。 */
    fun scan(file: VirtualFile): List<DependencyCoordinate> {
        // 关键变量：本次实际命中的适配器。
        val system = systems.firstOrNull { it.supports(file) } ?: return emptyList()
        return system.scan(project, file)
    }

    /** 对已有依赖做补充信息增强。 */
    fun enrich(file: VirtualFile, dependencies: List<DependencyCoordinate>): List<DependencyCoordinate> {
        // 关键变量：本次增强所使用的适配器。
        val system = systems.firstOrNull { it.supports(file) } ?: return dependencies
        return system.enrich(project, file, dependencies)
    }

    /** 刷新外部系统模型。 */
    fun refresh(file: VirtualFile, afterRefresh: (() -> Unit)? = null): Boolean {
        // 关键变量：负责触发刷新动作的适配器。
        val system = systems.firstOrNull { it.supports(file) } ?: return false
        return system.refresh(project, file, afterRefresh)
    }

    /** 执行依赖升级。 */
    fun upgrade(dependency: DependencyCoordinate, newVersion: String): Boolean {
        // 关键变量：按依赖生态选择升级执行器。
        val system = systems.firstOrNull { it.ecosystem == dependency.ecosystem } ?: return false
        return system.upgrade(project, dependency, newVersion)
    }
}
