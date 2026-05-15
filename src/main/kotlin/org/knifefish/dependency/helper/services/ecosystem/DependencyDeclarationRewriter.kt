package org.knifefish.dependency.helper.services.ecosystem

import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.Ecosystem

internal object DependencyDeclarationRewriter {

    fun replaceVersionInDeclaration(
        dependency: DependencyCoordinate,
        declaration: String,
        newVersion: String,
    ): String? {
        return when (dependency.ecosystem) {
            Ecosystem.MAVEN -> replaceMavenVersion(declaration, newVersion)
            Ecosystem.GRADLE -> replaceGradleVersion((dependency.declaredVersion ?: dependency.version), declaration, newVersion)
            Ecosystem.NPM, Ecosystem.RUST ->
                ecosystemSupport(dependency.ecosystem)
                    ?.replaceVersionInDeclaration(dependency, declaration, newVersion)
        }
    }

    private fun replaceMavenVersion(declaration: String, newVersion: String): String? {
        val open = declaration.indexOf("<version>", ignoreCase = true)
        if (open < 0) return null
        val valueStart = open + "<version>".length
        val close = declaration.indexOf("</version>", valueStart, ignoreCase = true)
        if (close <= valueStart) return null
        return declaration.replaceRange(valueStart, close, newVersion)
    }

    private fun replaceGradleVersion(oldVersion: String, declaration: String, newVersion: String): String? {
        if (oldVersion.isBlank()) return null
        val oldPos = declaration.indexOf(oldVersion)
        if (oldPos < 0) return null
        val before = declaration.substring(0, oldPos)
        val afterPos = oldPos + oldVersion.length
        val hasColonPrefix = before.lastIndexOf(':') >= before.lastIndexOf('"') || before.lastIndexOf(':') >= before.lastIndexOf('\'')
        if (!hasColonPrefix) return null
        if (afterPos >= declaration.length) return null
        val tail = declaration[afterPos]
        if (tail != '"' && tail != '\'') return null
        return declaration.replaceRange(oldPos, afterPos, newVersion)
    }
}

internal fun npmUpgradedVersionValue(oldVersion: String, newVersion: String): String? {
    val support = ecosystemSupport(Ecosystem.NPM)
    return support?.upgradedVersionValue(oldVersion, newVersion)
}

internal fun hasRecommendedUpgrade(dependency: DependencyCoordinate, latestStable: String?): Boolean {
    val latest = latestStable?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val support = ecosystemSupport(dependency.ecosystem)
    return support?.hasRecommendedUpgrade(dependency, latest) ?: (dependency.version.trim() != latest)
}

private fun ecosystemSupport(ecosystem: Ecosystem): DependencyEcosystemSupport? {
    val extensions = runCatching { DependencyEcosystemSupport.EP_NAME.extensionList }.getOrDefault(emptyList())
    return extensions.firstOrNull { it.ecosystem == ecosystem }
        ?: when (ecosystem) {
            Ecosystem.NPM -> NpmDependencyEcosystemSupport()
            Ecosystem.RUST -> RustDependencyEcosystemSupport()
            else -> null
        }
}
