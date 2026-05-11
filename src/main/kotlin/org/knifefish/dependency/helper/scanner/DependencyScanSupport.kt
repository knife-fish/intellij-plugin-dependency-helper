package org.knifefish.dependency.helper.scanner

import com.intellij.openapi.vfs.VirtualFile
import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.Ecosystem
import org.knifefish.dependency.helper.model.TextRangeMarker

internal interface DependencyScannerContributor {
    val ecosystem: Ecosystem
    fun scan(file: VirtualFile, text: String): List<DependencyCoordinate>
}

internal fun extractTag(block: String, tag: String): String? {
    return Regex("<$tag>(.*?)</$tag>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(block)
        ?.groupValues
        ?.get(1)
        ?.trim()
}

internal fun lineNumber(text: String, offset: Int): Int = text.take(offset).count { it == '\n' } + 1

internal fun createDependency(
    ecosystem: Ecosystem,
    group: String?,
    name: String,
    version: String,
    declaredVersion: String? = version,
    scope: String?,
    file: VirtualFile,
    declaration: String,
    lineNumber: Int,
    range: TextRangeMarker?,
    displayRange: TextRangeMarker = range ?: TextRangeMarker(0, 0),
    inspectionRange: TextRangeMarker = displayRange,
) = DependencyCoordinate(
    ecosystem = ecosystem,
    group = group,
    name = name,
    version = version,
    declaredVersion = declaredVersion,
    scope = scope,
    file = file,
    declarationText = declaration,
    lineNumber = lineNumber,
    versionRange = range,
    displayRange = displayRange,
    inspectionRange = inspectionRange,
)
