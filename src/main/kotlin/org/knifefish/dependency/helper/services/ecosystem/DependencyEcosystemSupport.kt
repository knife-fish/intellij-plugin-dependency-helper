package org.knifefish.dependency.helper.services.ecosystem

import com.intellij.openapi.extensions.ExtensionPointName
import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.Ecosystem
import org.knifefish.dependency.helper.model.PackageSearchResult

interface DependencyEcosystemSupport {
    val ecosystem: Ecosystem

    fun upgradedVersionValue(oldVersion: String, newVersion: String): String? = newVersion

    fun hasRecommendedUpgrade(dependency: DependencyCoordinate, latestStable: String?): Boolean {
        val latest = latestStable?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return dependency.version.trim() != latest
    }

    fun replaceVersionInDeclaration(
        dependency: DependencyCoordinate,
        declaration: String,
        newVersion: String,
    ): String?

    fun buildDependencyInsertion(
        fileName: String,
        result: PackageSearchResult,
        version: String,
        text: String,
    ): DependencyInsertion?

    companion object {
        val EP_NAME: ExtensionPointName<DependencyEcosystemSupport> =
            ExtensionPointName.create("org.knifefish.dependency.helper.ecosystemSupport")
    }
}
