package org.knifefish.dependency.helper.services.ecosystem

import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.Ecosystem

class NpmDependencyEcosystemSupport : DependencyEcosystemSupport {
    override val ecosystem: Ecosystem = Ecosystem.NPM

    override fun upgradedVersionValue(oldVersion: String, newVersion: String): String? {
        val value = oldVersion.trim()
        if (value.isBlank()) return null
        val unsupportedPrefixes = listOf("workspace:", "file:", "link:", "git+", "github:", "gitlab:", "bitbucket:", "http://", "https://", "npm:")
        if (unsupportedPrefixes.any { prefix -> value.startsWith(prefix, ignoreCase = true) }) return null
        if ((value.startsWith("^") || value.startsWith("~")) && value.length > 1) {
            val suffix = value.substring(1).trim()
            if (suffix.isNotEmpty() && !suffix.contains(" ")) {
                return "${value.first()}$newVersion"
            }
        }
        return if (value.contains(" ") || value.contains("||")) null else newVersion
    }

    override fun hasRecommendedUpgrade(dependency: DependencyCoordinate, latestStable: String?): Boolean {
        val latest = latestStable?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val current = dependency.version.trim()
        if (current == latest) return false
        val normalizedCurrent = current.removePrefix("^").removePrefix("~").trim()
        return normalizedCurrent != latest
    }

    override fun replaceVersionInDeclaration(
        dependency: DependencyCoordinate,
        declaration: String,
        newVersion: String,
    ): String? {
        val oldVersion = dependency.declaredVersion ?: dependency.version
        val replacementVersion = upgradedVersionValue(oldVersion, newVersion) ?: return null
        val key = "\"${dependency.name}\""
        val keyStart = declaration.indexOf(key)
        if (keyStart < 0) return null
        val colon = declaration.indexOf(':', keyStart + key.length)
        if (colon < 0) return null
        val valueQuoteStart = declaration.indexOf('"', colon + 1)
        if (valueQuoteStart < 0) return null
        val valueStart = valueQuoteStart + 1
        val valueEnd = declaration.indexOf('"', valueStart)
        if (valueEnd <= valueStart) return null
        return declaration.replaceRange(valueStart, valueEnd, replacementVersion)
    }

}
