package org.knifefish.dependency.helper.scanner

import com.intellij.openapi.vfs.VirtualFile
import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.Ecosystem
import org.knifefish.dependency.helper.model.TextRangeMarker

class DependencyFileScanner {

    fun supports(file: VirtualFile): Boolean = detectEcosystem(file) != null

    fun detectEcosystem(file: VirtualFile): Ecosystem? = when (file.name) {
        "pom.xml" -> Ecosystem.MAVEN
        "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts" -> Ecosystem.GRADLE
        "package.json" -> Ecosystem.NPM
        "requirements.txt", "pyproject.toml" -> Ecosystem.PYTHON
        "Cargo.toml" -> Ecosystem.RUST
        else -> null
    }

    fun scan(file: VirtualFile, text: String): List<DependencyCoordinate> {
        return when (detectEcosystem(file)) {
            Ecosystem.MAVEN -> scanMaven(file, text)
            Ecosystem.GRADLE -> scanGradle(file, text)
            Ecosystem.NPM -> scanPackageJson(file, text)
            Ecosystem.PYTHON -> scanPython(file, text)
            Ecosystem.RUST -> scanCargo(file, text)
            null -> emptyList()
        }
    }

    private fun scanMaven(file: VirtualFile, text: String): List<DependencyCoordinate> {
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

    private fun scanGradle(file: VirtualFile, text: String): List<DependencyCoordinate> {
        val regexes = listOf(
            Regex("""(?m)^(\s*)([\w.]+)\s*\(\s*["']([^:"']+):([^:"']+):([^"']+)["']\s*\)"""),
            Regex("""(?m)^(\s*)([\w.]+)\s+["']([^:"']+):([^:"']+):([^"']+)["']"""),
        )
        val results = mutableListOf<DependencyCoordinate>()
        regexes.forEach { regex ->
            regex.findAll(text).forEach { match ->
                val configuration = match.groupValues[2]
                val group = match.groupValues[3]
                val name = match.groupValues[4]
                val version = match.groupValues[5]
                val versionIndex = match.value.lastIndexOf(version)
                val versionStart = match.range.first + versionIndex
                results += createDependency(
                    ecosystem = Ecosystem.GRADLE,
                    group = group,
                    name = name,
                    version = version.trim(),
                    scope = configuration,
                    file = file,
                    declaration = match.value.trim(),
                    lineNumber = lineNumber(text, versionStart),
                    range = TextRangeMarker(versionStart, versionStart + version.length),
                    inspectionRange = TextRangeMarker(match.range.first, match.range.last + 1),
                )
            }
        }
        return results.distinctBy { "${it.key}:${it.versionRange?.startOffset ?: -1}" }
    }

    private fun scanPackageJson(file: VirtualFile, text: String): List<DependencyCoordinate> {
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

    private fun scanPython(file: VirtualFile, text: String): List<DependencyCoordinate> {
        return if (file.name == "requirements.txt") {
            scanRequirements(file, text)
        } else {
            scanPyProject(file, text)
        }
    }

    private fun scanRequirements(file: VirtualFile, text: String): List<DependencyCoordinate> {
        val regex = Regex("""(?m)^([A-Za-z0-9_.-]+)\s*(==|>=|<=|~=|!=|>|<)\s*([^\s#;]+)""")
        return regex.findAll(text).map { match ->
            val version = match.groupValues[3]
            val versionStart = match.range.first + match.value.lastIndexOf(version)
            createDependency(
                ecosystem = Ecosystem.PYTHON,
                group = null,
                name = match.groupValues[1],
                version = version.trim(),
                scope = match.groupValues[2],
                file = file,
                declaration = match.value.trim(),
                lineNumber = lineNumber(text, versionStart),
                range = TextRangeMarker(versionStart, versionStart + version.length),
                inspectionRange = TextRangeMarker(match.range.first, match.range.last + 1),
            )
        }.toList()
    }

    private fun scanPyProject(file: VirtualFile, text: String): List<DependencyCoordinate> {
        val results = mutableListOf<DependencyCoordinate>()

        val arrayRegex = Regex("""dependencies\s*=\s*\[(.*?)\]""", setOf(RegexOption.DOT_MATCHES_ALL))
        arrayRegex.findAll(text).forEach { section ->
            val itemRegex = Regex(""""([A-Za-z0-9_.-]+)\s*(==|>=|<=|~=|!=|>|<)\s*([^"]+)"""")
            itemRegex.findAll(section.groupValues[1]).forEach { item ->
                val version = item.groupValues[3]
                val versionStart = section.range.first + item.range.first + item.value.lastIndexOf(version)
                results += createDependency(
                    ecosystem = Ecosystem.PYTHON,
                    group = null,
                    name = item.groupValues[1],
                    version = version.trim(),
                    scope = item.groupValues[2],
                    file = file,
                    declaration = item.value.trim(),
                    lineNumber = lineNumber(text, versionStart),
                    range = TextRangeMarker(versionStart, versionStart + version.length),
                    inspectionRange = TextRangeMarker(section.range.first + item.range.first, section.range.first + item.range.last + 1),
                )
            }
        }

        val poetryRegex = Regex("""(?ms)^\[tool\.poetry\.dependencies]\s*(.*?)(^\[|\z)""")
        poetryRegex.findAll(text).forEach { section ->
            val itemRegex = Regex("""(?m)^([A-Za-z0-9_.-]+)\s*=\s*["']([^"']+)["']""")
            itemRegex.findAll(section.groupValues[1]).forEach { item ->
                val name = item.groupValues[1]
                if (name == "python") {
                    return@forEach
                }
                val version = item.groupValues[2]
                val versionStart = section.range.first + item.range.first + item.value.lastIndexOf(version)
                results += createDependency(
                    ecosystem = Ecosystem.PYTHON,
                    group = null,
                    name = name,
                    version = version.trim(),
                    scope = "poetry",
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

    private fun scanCargo(file: VirtualFile, text: String): List<DependencyCoordinate> {
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

    private fun extractTag(block: String, tag: String): String? {
        return Regex("<$tag>(.*?)</$tag>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(block)
            ?.groupValues
            ?.get(1)
            ?.trim()
    }

    private fun lineNumber(text: String, offset: Int): Int = text.take(offset).count { it == '\n' } + 1

    private fun createDependency(
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
}
