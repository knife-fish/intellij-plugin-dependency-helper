package org.knifefish.dependency.helper.services.external

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.Ecosystem

/**
 * 外部生态系统适配接口。
 * 由不同生态（Maven/Gradle/NPM）实现，统一提供扫描、刷新、升级等能力。
 */
internal interface ExternalDependencySystem {
    /** 当前适配器对应的生态类型。 */
    val ecosystem: Ecosystem

    /** 判断当前文件是否由该适配器处理。 */
    fun supports(file: VirtualFile): Boolean = false

    fun supportsAnalyzer(file: VirtualFile): Boolean = false

    /** 扫描文件并返回依赖坐标。 */
    fun scan(project: Project, file: VirtualFile): List<DependencyCoordinate> = emptyList()

    /** 对扫描结果做二次补充（如补齐声明版本、属性引用等）。 */
    fun enrich(project: Project, file: VirtualFile, dependencies: List<DependencyCoordinate>): List<DependencyCoordinate> = dependencies

    /** 将目标依赖升级到新版本。 */
    fun upgrade(project: Project, dependency: DependencyCoordinate, newVersion: String): Boolean = false

    /** 刷新对应外部系统模型（如触发重新导入），完成后回调。 */
    fun refresh(project: Project, file: VirtualFile, afterRefresh: (() -> Unit)? = null): Boolean = false

    companion object {
        /** 外部依赖系统扩展点。 */
        val EP_NAME: ExtensionPointName<ExternalDependencySystem> =
            ExtensionPointName.create("org.knifefish.dependency.helper.externalDependencySystem")
    }
}
