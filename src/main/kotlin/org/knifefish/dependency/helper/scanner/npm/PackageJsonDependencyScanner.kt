package org.knifefish.dependency.helper.scanner.npm

import com.intellij.openapi.vfs.VirtualFile
import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.Ecosystem
import org.knifefish.dependency.helper.model.TextRangeMarker
import org.knifefish.dependency.helper.scanner.DependencyScannerContributor
import org.knifefish.dependency.helper.scanner.createDependency
import org.knifefish.dependency.helper.scanner.lineNumber

internal class PackageJsonDependencyScanner : DependencyScannerContributor {
    override val ecosystem: Ecosystem = Ecosystem.NPM

    override fun scan(file: VirtualFile, text: String): List<DependencyCoordinate> {
        val results = mutableListOf<DependencyCoordinate>()
        listOf("dependencies", "devDependencies", "peerDependencies", "optionalDependencies", "overrides", "resolutions")
            .forEach { section ->
                results += scanSection(text, file, section, section)
            }
        results += scanPnpmOverrides(text, file)
        return deduplicate(results)
    }

    private fun scanSection(
        text: String,
        file: VirtualFile,
        fieldName: String,
        scope: String,
    ): List<DependencyCoordinate> {
        val results = mutableListOf<DependencyCoordinate>()
        val sectionRegex = Regex(""""$fieldName"\s*:\s*\{(.*?)\}""", setOf(RegexOption.DOT_MATCHES_ALL))
        sectionRegex.findAll(text).forEach { sectionMatch ->
            val body = sectionMatch.groupValues[1]
            val sectionOffset = text.indexOf(body, sectionMatch.range.first).takeIf { it >= 0 } ?: sectionMatch.range.first
            val itemRegex = Regex(""""([^"]+)"\s*:\s*"([^"]+)"""")
            itemRegex.findAll(body).forEach { item ->
                val name = item.groupValues[1]
                val version = item.groupValues[2]
                val versionStart = sectionOffset + item.range.first + item.value.lastIndexOf(version)
                results += createDependency(
                    ecosystem = Ecosystem.NPM,
                    group = null,
                    name = name,
                    version = version.trim(),
                    scope = scope,
                    file = file,
                    declaration = item.value.trim(),
                    lineNumber = lineNumber(text, versionStart),
                    range = TextRangeMarker(versionStart, versionStart + version.length),
                    inspectionRange = TextRangeMarker(sectionOffset + item.range.first, sectionOffset + item.range.last + 1),
                )
            }
        }
        return results
    }

    private fun scanPnpmOverrides(text: String, file: VirtualFile): List<DependencyCoordinate> {
        val results = mutableListOf<DependencyCoordinate>()
        val overridesRegex = Regex("(?ms)\"pnpm\"\\s*:\\s*\\{.*?\"overrides\"\\s*:\\s*\\{(.*?)\\}")
        overridesRegex.findAll(text).forEach { match ->
            val body = match.groupValues[1]
            val bodyOffset = text.indexOf(body, match.range.first).takeIf { it >= 0 } ?: match.range.first
            val itemRegex = Regex(""""([^"]+)"\s*:\s*"([^"]+)"""")
            itemRegex.findAll(body).forEach { item ->
                val name = item.groupValues[1]
                val version = item.groupValues[2]
                val versionStart = bodyOffset + item.range.first + item.value.lastIndexOf(version)
                results += createDependency(
                    ecosystem = Ecosystem.NPM,
                    group = null,
                    name = name,
                    version = version.trim(),
                    scope = "pnpm.overrides",
                    file = file,
                    declaration = item.value.trim(),
                    lineNumber = lineNumber(text, versionStart),
                    range = TextRangeMarker(versionStart, versionStart + version.length),
                    inspectionRange = TextRangeMarker(bodyOffset + item.range.first, bodyOffset + item.range.last + 1),
                )
            }
        }
        return results
    }

    private fun deduplicate(input: List<DependencyCoordinate>): List<DependencyCoordinate> {
        val byRange = linkedMapOf<String, DependencyCoordinate>()
        input.forEach { dep ->
            val key = "${dep.name}:${dep.version}:${dep.inspectionRange.startOffset}:${dep.inspectionRange.endOffset}"
            val existing = byRange[key]
            if (existing == null) {
                byRange[key] = dep
                return@forEach
            }
            if (existing.scope == "overrides" && dep.scope == "pnpm.overrides") {
                byRange[key] = dep
            }
        }
        return byRange.values.toList()
    }
}
