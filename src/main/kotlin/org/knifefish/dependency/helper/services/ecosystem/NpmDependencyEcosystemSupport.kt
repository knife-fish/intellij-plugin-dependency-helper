package org.knifefish.dependency.helper.services.ecosystem

import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.Ecosystem
import org.knifefish.dependency.helper.model.PackageSearchResult

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

    override fun buildDependencyInsertion(
        fileName: String,
        result: PackageSearchResult,
        version: String,
        text: String,
    ): DependencyInsertion? {
        if (fileName != "package.json") return null
        val keyToken = "\"dependencies\""
        val keyIndex = text.indexOf(keyToken)
        if (keyIndex >= 0) {
            val objectStart = text.indexOf('{', keyIndex + keyToken.length)
            if (objectStart < 0) return null
            return DependencyInsertion(objectStart + 1, "\n    \"${result.name}\": \"$version\",")
        }
        val rootOpen = text.indexOf('{')
        val rootClose = text.lastIndexOf('}')
        if (rootOpen < 0) return null
        val hasOtherFields = rootClose > rootOpen + 1 && text.substring(rootOpen + 1, rootClose).trim().isNotEmpty()
        val suffix = if (hasOtherFields) "," else ""
        val insertText = "\n  \"dependencies\": {\n    \"${result.name}\": \"$version\"\n  }$suffix"
        return DependencyInsertion(rootOpen + 1, insertText)
    }
}
