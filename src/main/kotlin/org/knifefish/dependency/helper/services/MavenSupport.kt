package org.knifefish.dependency.helper.services

import com.intellij.openapi.vfs.VirtualFile
import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.MavenDependencyNodeView

interface MavenSupport {

    fun analyze(): List<MavenDependencyNodeView>

    fun toDependencyCoordinate(view: MavenDependencyNodeView): DependencyCoordinate

    fun jumpToSource(view: MavenDependencyNodeView): Boolean

    fun exclude(view: MavenDependencyNodeView): Boolean

    fun enrichDependencies(file: VirtualFile, dependencies: List<DependencyCoordinate>): List<DependencyCoordinate>

    fun refreshMavenProject(file: VirtualFile, afterRefresh: (() -> Unit)? = null)

    fun upgradeManagedDependency(dependency: DependencyCoordinate, latestVersion: String): Boolean

    fun resolveManagedUpgradeOptions(dependency: DependencyCoordinate): List<ManagedUpgradeOption>

    fun executeManagedUpgradeTarget(target: ManagedUpgradeTarget, latestVersion: String): Boolean
}
