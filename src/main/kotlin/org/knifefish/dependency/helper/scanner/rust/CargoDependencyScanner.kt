package org.knifefish.dependency.helper.scanner.rust

import com.intellij.openapi.vfs.VirtualFile
import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.Ecosystem
import org.knifefish.dependency.helper.model.TextRangeMarker
import org.knifefish.dependency.helper.scanner.DependencyScannerContributor
import org.knifefish.dependency.helper.scanner.createDependency
import org.knifefish.dependency.helper.scanner.lineNumber

internal class CargoDependencyScanner : DependencyScannerContributor {
    override val ecosystem: Ecosystem = Ecosystem.RUST

    override fun scan(file: VirtualFile, text: String): List<DependencyCoordinate> {
        val results = mutableListOf<DependencyCoordinate>()
        val sectionRegex = Regex("""(?ms)^\[dependencies]\s*(.*?)(^\[|\z)""")
        sectionRegex.findAll(text).forEach { section ->
            val inlineRegex = Regex("""(?m)^([A-Za-z0-9_.-]+)\s*=\s*"([^"]+)"""")
            inlineRegex.findAll(section.groupValues[1]).forEach { item ->
                val version = item.groupValues[2]
                val versionStart = section.range.first + item.range.first + item.value.lastIndexOf(version)
                results += createDependency(
                    ecosystem = Ecosystem.RUST,
                    group = null,
                    name = item.groupValues[1],
                    version = version.trim(),
                    scope = "dependencies",
                    file = file,
                    declaration = item.value.trim(),
                    lineNumber = lineNumber(text, versionStart),
                    range = TextRangeMarker(versionStart, versionStart + version.length),
                    inspectionRange = TextRangeMarker(section.range.first + item.range.first, section.range.first + item.range.last + 1),
                )
            }

            val tableRegex = Regex("""(?m)^([A-Za-z0-9_.-]+)\s*=\s*\{[^}]*version\s*=\s*"([^"]+)"""")
            tableRegex.findAll(section.groupValues[1]).forEach { item ->
                val version = item.groupValues[2]
                val versionStart = section.range.first + item.range.first + item.value.lastIndexOf(version)
                results += createDependency(
                    ecosystem = Ecosystem.RUST,
                    group = null,
                    name = item.groupValues[1],
                    version = version.trim(),
                    scope = "dependencies",
                    file = file,
                    declaration = item.value.trim(),
                    lineNumber = lineNumber(text, versionStart),
                    range = TextRangeMarker(versionStart, versionStart + version.length),
                    inspectionRange = TextRangeMarker(section.range.first + item.range.first, section.range.first + item.range.last + 1),
                )
            }
        }
        return results
    }
}
