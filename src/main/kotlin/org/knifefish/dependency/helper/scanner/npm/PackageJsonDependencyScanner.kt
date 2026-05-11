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
        val sections = listOf("dependencies", "devDependencies", "peerDependencies", "optionalDependencies")
        val results = mutableListOf<DependencyCoordinate>()
        sections.forEach { section ->
            val sectionRegex = Regex(""""$section"\s*:\s*\{(.*?)\}""", setOf(RegexOption.DOT_MATCHES_ALL))
            sectionRegex.findAll(text).forEach { sectionMatch ->
                val body = sectionMatch.groupValues[1]
                val itemRegex = Regex(""""([^"]+)"\s*:\s*"([^"]+)"""")
                itemRegex.findAll(body).forEach { item ->
                    val name = item.groupValues[1]
                    val version = item.groupValues[2]
                    val versionStart = sectionMatch.range.first + item.range.first + item.value.lastIndexOf(version)
                    results += createDependency(
                        ecosystem = Ecosystem.NPM,
                        group = null,
                        name = name,
                        version = version.trim(),
                        scope = section,
                        file = file,
                        declaration = item.value.trim(),
                        lineNumber = lineNumber(text, versionStart),
                        range = TextRangeMarker(versionStart, versionStart + version.length),
                        inspectionRange = TextRangeMarker(sectionMatch.range.first + item.range.first, sectionMatch.range.first + item.range.last + 1),
                    )
                }
            }
        }
        return results
    }
}
