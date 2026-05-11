package org.knifefish.dependency.helper.scanner.gradle

import com.intellij.openapi.vfs.VirtualFile
import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.Ecosystem
import org.knifefish.dependency.helper.model.TextRangeMarker
import org.knifefish.dependency.helper.scanner.DependencyScannerContributor
import org.knifefish.dependency.helper.scanner.createDependency
import org.knifefish.dependency.helper.scanner.lineNumber

internal class GradleDependencyScanner : DependencyScannerContributor {
    override val ecosystem: Ecosystem = Ecosystem.GRADLE

    override fun scan(file: VirtualFile, text: String): List<DependencyCoordinate> {
        val aliasRegexes = listOf(
            Regex("""(?m)^(\s*)([\w.]+)\s*\(\s*(?:\w+\(\s*)?([A-Za-z][A-Za-z0-9_]*)\.([A-Za-z0-9_.-]+)\s*\)?\s*\)"""),
            Regex("""(?m)^(\s*)([\w.]+)\s+(?:\w+\(\s*)?([A-Za-z][A-Za-z0-9_]*)\.([A-Za-z0-9_.-]+)\s*\)?"""),
        )
        val regexes = listOf(
            Regex("""(?m)^(\s*)([\w.]+)\s*\(\s*["']([^:"']+):([^:"']+):([^"']+)["']\s*\)"""),
            Regex("""(?m)^(\s*)([\w.]+)\s+["']([^:"']+):([^:"']+):([^"']+)["']"""),
            Regex("""(?m)^(\s*)([\w.]+)\s*\(\s*["']([^:"']+):([^:"']+)["']\s*\)"""),
            Regex("""(?m)^(\s*)([\w.]+)\s+["']([^:"']+):([^:"']+)["']"""),
        )
        val results = mutableListOf<DependencyCoordinate>()
        aliasRegexes.forEach { regex ->
            regex.findAll(text).forEach { match ->
                val configuration = match.groupValues[2]
                val catalogName = match.groupValues[3]
                val aliasAccessor = match.groupValues[4]
                val aliasExpression = "$catalogName.$aliasAccessor"
                val aliasEnd = match.range.first + match.value.lastIndexOf(aliasExpression) + aliasExpression.length
                results += createDependency(
                    ecosystem = Ecosystem.GRADLE,
                    group = null,
                    name = aliasAccessor.substringAfterLast('.'),
                    version = "",
                    declaredVersion = aliasExpression,
                    scope = configuration,
                    file = file,
                    declaration = match.value.trim(),
                    lineNumber = lineNumber(text, match.range.first),
                    range = null,
                    displayRange = TextRangeMarker(aliasEnd, aliasEnd),
                    inspectionRange = TextRangeMarker(match.range.first, match.range.last + 1),
                )
            }
        }
        regexes.forEach { regex ->
            regex.findAll(text).forEach { match ->
                val configuration = match.groupValues[2]
                val group = match.groupValues[3]
                val name = match.groupValues[4]
                val hasVersion = match.groupValues.size > 5
                val version = if (hasVersion) match.groupValues[5] else ""
                val versionIndex = if (hasVersion) match.value.lastIndexOf(version) else -1
                val versionStart = if (hasVersion) match.range.first + versionIndex else -1
                val displayRange = if (hasVersion) {
                    TextRangeMarker(versionStart, versionStart + version.length)
                } else {
                    val artifactEnd = match.range.first + match.value.lastIndexOf(name) + name.length
                    TextRangeMarker(artifactEnd, artifactEnd)
                }
                results += createDependency(
                    ecosystem = Ecosystem.GRADLE,
                    group = group,
                    name = name,
                    version = version.trim(),
                    declaredVersion = if (hasVersion) version.trim() else null,
                    scope = configuration,
                    file = file,
                    declaration = match.value.trim(),
                    lineNumber = lineNumber(text, if (hasVersion) versionStart else match.range.first),
                    range = if (hasVersion) TextRangeMarker(versionStart, versionStart + version.length) else null,
                    displayRange = displayRange,
                    inspectionRange = TextRangeMarker(match.range.first, match.range.last + 1),
                )
            }
        }
        return results.distinctBy { "${it.key}:${it.versionRange?.startOffset ?: -1}" }
    }
}
