package org.knifefish.dependency.helper.services

import com.intellij.openapi.vfs.VirtualFile
import org.knifefish.dependency.helper.model.DependencyCoordinate
import java.nio.file.Path

interface GradleSupport {

    fun enrichDependencies(file: VirtualFile, dependencies: List<DependencyCoordinate>): List<DependencyCoordinate>

    fun upgradeDependency(dependency: DependencyCoordinate, newVersion: String): Boolean

    fun refreshGradleProject(file: VirtualFile, afterRefresh: (() -> Unit)? = null)

    fun resolveMetadataPath(dependency: DependencyCoordinate): Path?
}
