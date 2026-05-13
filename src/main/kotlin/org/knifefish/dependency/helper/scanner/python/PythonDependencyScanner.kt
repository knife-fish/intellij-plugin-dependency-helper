package org.knifefish.dependency.helper.scanner.python

import com.intellij.openapi.vfs.VirtualFile
import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.Ecosystem
import org.knifefish.dependency.helper.model.TextRangeMarker
import org.knifefish.dependency.helper.scanner.DependencyScannerContributor
import org.knifefish.dependency.helper.scanner.createDependency
import org.knifefish.dependency.helper.scanner.lineNumber

internal class PythonDependencyScanner : DependencyScannerContributor {
    override val ecosystem: Ecosystem = Ecosystem.PYTHON

    override fun scan(file: VirtualFile, text: String): List<DependencyCoordinate> {
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

        val poetryGroupRegex = Regex("""(?ms)^\[tool\.poetry\.group\.[^.]+\.dependencies]\s*(.*?)(^\[|\z)""")
        poetryGroupRegex.findAll(text).forEach { section ->
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
                    scope = "poetry-group",
                    file = file,
                    declaration = item.value.trim(),
                    lineNumber = lineNumber(text, versionStart),
                    range = TextRangeMarker(versionStart, versionStart + version.length),
                    inspectionRange = TextRangeMarker(section.range.first + item.range.first, section.range.first + item.range.last + 1),
                )
            }
        }

        val optionalDepsRegex = Regex("""(?ms)^\[project\.optional-dependencies]\s*(.*?)(^\[|\z)""")
        optionalDepsRegex.findAll(text).forEach { section ->
            val groupRegex = Regex("""(?ms)^([A-Za-z0-9_.-]+)\s*=\s*\[(.*?)]""")
            groupRegex.findAll(section.groupValues[1]).forEach { groupMatch ->
                val itemRegex = Regex(""""([A-Za-z0-9_.-]+)\s*(==|>=|<=|~=|!=|>|<)\s*([^"]+)"""")
                itemRegex.findAll(groupMatch.groupValues[2]).forEach { item ->
                    val version = item.groupValues[3]
                    val versionStart = section.range.first + groupMatch.range.first + item.range.first + item.value.lastIndexOf(version)
                    results += createDependency(
                        ecosystem = Ecosystem.PYTHON,
                        group = null,
                        name = item.groupValues[1],
                        version = version.trim(),
                        scope = "optional-${groupMatch.groupValues[1]}",
                        file = file,
                        declaration = item.value.trim(),
                        lineNumber = lineNumber(text, versionStart),
                        range = TextRangeMarker(versionStart, versionStart + version.length),
                        inspectionRange = TextRangeMarker(section.range.first + groupMatch.range.first + item.range.first, section.range.first + groupMatch.range.first + item.range.last + 1),
                    )
                }
            }
        }

        return results
    }
}
