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
        listOf("dependencies", "dev-dependencies", "build-dependencies").forEach { sectionName ->
            val sectionRegex = Regex("""(?ms)^\[$sectionName]\s*(.*?)(^\[|\z)""")
            sectionRegex.findAll(text).forEach { section ->
                val body = section.groupValues[1]
                val bodyOffset = text.indexOf(body, section.range.first).takeIf { it >= 0 } ?: section.range.first
                results += scanSection(file, text, body, bodyOffset, sectionName)
            }
        }

        val targetSectionRegex = Regex(
            """(?ms)^\[target\.[^\]]+\.(dependencies|dev-dependencies|build-dependencies)]\s*(.*?)(^\[|\z)""",
        )
        targetSectionRegex.findAll(text).forEach { section ->
            val sectionName = section.groupValues[1]
            val body = section.groupValues[2]
            val bodyOffset = text.indexOf(body, section.range.first).takeIf { it >= 0 } ?: section.range.first
            results += scanSection(file, text, body, bodyOffset, "target-$sectionName")
        }
        return results
    }

    private fun scanSection(
        file: VirtualFile,
        fullText: String,
        body: String,
        bodyOffset: Int,
        scope: String,
    ): List<DependencyCoordinate> {
        val results = mutableListOf<DependencyCoordinate>()
        val inlineRegex = Regex("""(?m)^([A-Za-z0-9_.-]+)\s*=\s*"([^"]+)"""")
        inlineRegex.findAll(body).forEach { item ->
            val version = item.groupValues[2]
            val versionStart = bodyOffset + item.range.first + item.value.lastIndexOf(version)
            results += createDependency(
                ecosystem = Ecosystem.RUST,
                group = null,
                name = item.groupValues[1],
                version = version.trim(),
                scope = scope,
                file = file,
                declaration = item.value.trim(),
                lineNumber = lineNumber(fullText, versionStart),
                range = TextRangeMarker(versionStart, versionStart + version.length),
                inspectionRange = TextRangeMarker(bodyOffset + item.range.first, bodyOffset + item.range.last + 1),
            )
        }

        val tableRegex = Regex("""(?m)^([A-Za-z0-9_.-]+)\s*=\s*\{[^}]*version\s*=\s*"([^"]+)"""")
        tableRegex.findAll(body).forEach { item ->
            val version = item.groupValues[2]
            val versionStart = bodyOffset + item.range.first + item.value.lastIndexOf(version)
            results += createDependency(
                ecosystem = Ecosystem.RUST,
                group = null,
                name = item.groupValues[1],
                version = version.trim(),
                scope = scope,
                file = file,
                declaration = item.value.trim(),
                lineNumber = lineNumber(fullText, versionStart),
                range = TextRangeMarker(versionStart, versionStart + version.length),
                inspectionRange = TextRangeMarker(bodyOffset + item.range.first, bodyOffset + item.range.last + 1),
            )
        }
        return results
    }
}
