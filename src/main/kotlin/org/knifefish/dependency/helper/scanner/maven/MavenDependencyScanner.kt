package org.knifefish.dependency.helper.scanner.maven

import com.intellij.openapi.vfs.VirtualFile
import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.Ecosystem
import org.knifefish.dependency.helper.model.TextRangeMarker
import org.knifefish.dependency.helper.scanner.DependencyScannerContributor
import org.knifefish.dependency.helper.scanner.createDependency
import org.knifefish.dependency.helper.scanner.extractTag
import org.knifefish.dependency.helper.scanner.lineNumber

internal class MavenDependencyScanner : DependencyScannerContributor {
    override val ecosystem: Ecosystem = Ecosystem.MAVEN

    override fun scan(file: VirtualFile, text: String): List<DependencyCoordinate> {
        val blockRegex = Regex("<dependency>(.*?)</dependency>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        return blockRegex.findAll(text).mapNotNull { match ->
            val block = match.value
            val group = extractTag(block, "groupId")
            val artifact = extractTag(block, "artifactId")
            val version = extractTag(block, "version")?.trim()
            if (artifact.isNullOrBlank() || group.isNullOrBlank()) {
                return@mapNotNull null
            }

            val versionTag = Regex("<version>(.*?)</version>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(block)
            val artifactTag = Regex("<artifactId>(.*?)</artifactId>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(block)
            val versionStart = versionTag?.let { match.range.first + it.range.first + "<version>".length }
            val versionEnd = versionStart?.let { it + (version?.length ?: 0) }
            val displayRange = when {
                versionStart != null && versionEnd != null -> TextRangeMarker(versionStart, versionEnd)
                artifactTag != null -> {
                    val artifactEnd = match.range.first + artifactTag.range.first + "<artifactId>".length + artifact.length
                    TextRangeMarker(artifactEnd, artifactEnd)
                }
                else -> TextRangeMarker(match.range.last, match.range.last)
            }
            createDependency(
                ecosystem = Ecosystem.MAVEN,
                group = group,
                name = artifact,
                version = version.orEmpty(),
                declaredVersion = version,
                scope = extractTag(block, "scope")?.trim(),
                file = file,
                declaration = block.trim(),
                lineNumber = lineNumber(text, versionStart ?: match.range.first),
                range = if (versionStart != null && versionEnd != null) TextRangeMarker(versionStart, versionEnd) else null,
                displayRange = displayRange,
                inspectionRange = TextRangeMarker(match.range.first, match.range.last + 1),
            )
        }.toList()
    }
}
