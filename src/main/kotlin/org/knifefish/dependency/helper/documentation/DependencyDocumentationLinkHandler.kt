package org.knifefish.dependency.helper.documentation

import com.intellij.openapi.application.invokeLater
import com.intellij.platform.backend.documentation.DocumentationLinkHandler
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.LinkResolveResult
import org.knifefish.dependency.helper.services.MavenDependencyAnalyzer
import org.knifefish.dependency.helper.services.dependencyInsightService

class DependencyDocumentationLinkHandler : DocumentationLinkHandler {

    override fun resolveLink(target: DocumentationTarget, link: String): LinkResolveResult? {
        val dependencyTarget = target as? DependencyDocumentationTarget ?: return null
        val normalizedLink = link.removePrefix("psi_element://")
        if (!normalizedLink.startsWith("dependency-helper-upgrade")) {
            return null
        }
        val lookup = dependencyTarget.lookup
        if (lookup.upgradeTriggered) {
            return LinkResolveResult.resolvedTarget(target)
        }
        lookup.upgradeTriggered = true
        val project = lookup.originalElement.project
        val latest = lookup.versionInfo.latestStable ?: return LinkResolveResult.resolvedTarget(target)
        invokeLater {
            val targetId = normalizedLink.substringAfter("dependency-helper-upgrade", "")
                .removePrefix(":")
                .ifBlank { "current" }
            val option = lookup.managedOptions.firstOrNull { it.target.id == targetId }
            if (option != null) {
                project.getService(MavenDependencyAnalyzer::class.java)
                    .executeManagedUpgradeTarget(option.target, option.latestVersion)
            } else {
                project.dependencyInsightService().upgradeDependency(lookup.dependency, latest)
            }
        }
        return LinkResolveResult.resolvedTarget(target)
    }
}
