package org.knifefish.dependency.helper.services.ecosystem

import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.Ecosystem
import org.knifefish.dependency.helper.model.PackageSearchResult

class RustDependencyEcosystemSupport : DependencyEcosystemSupport {
    override val ecosystem: Ecosystem = Ecosystem.RUST

    override fun replaceVersionInDeclaration(
        dependency: DependencyCoordinate,
        declaration: String,
        newVersion: String,
    ): String? {
        val inlineKey = "${dependency.name} ="
        val keyIndex = declaration.indexOf(inlineKey)
        if (keyIndex >= 0 && declaration.indexOf('{', keyIndex) < 0) {
            val quote1 = declaration.indexOf('"', keyIndex)
            if (quote1 >= 0) {
                val valueStart = quote1 + 1
                val valueEnd = declaration.indexOf('"', valueStart)
                if (valueEnd > valueStart) {
                    return declaration.replaceRange(valueStart, valueEnd, newVersion)
                }
            }
        }
        val versionKeyIndex = declaration.indexOf("version")
        if (versionKeyIndex < 0) return null
        val equals = declaration.indexOf('=', versionKeyIndex)
        if (equals < 0) return null
        val quote1 = declaration.indexOf('"', equals)
        if (quote1 < 0) return null
        val valueStart = quote1 + 1
        val valueEnd = declaration.indexOf('"', valueStart)
        if (valueEnd <= valueStart) return null
        return declaration.replaceRange(valueStart, valueEnd, newVersion)
    }

    override fun buildDependencyInsertion(
        fileName: String,
        result: PackageSearchResult,
        version: String,
        text: String,
    ): DependencyInsertion? {
        if (fileName != "Cargo.toml") return null
        val anchor = if (hasTomlSection(text, "dependencies")) {
            val sectionStart = text.indexOf("[dependencies]").takeIf { it >= 0 } ?: -1
            if (sectionStart >= 0) {
                val sectionLineEnd = text.indexOf('\n', sectionStart).takeIf { it >= 0 } ?: text.length
                sectionLineEnd + 1
            } else {
                null
            }
        } else {
            null
        }
        if (anchor != null) {
            return DependencyInsertion(anchor, "\n${result.name} = \"$version\"")
        }
        val prefix = if (text.endsWith("\n") || text.isEmpty()) "" else "\n"
        return DependencyInsertion(text.length, "${prefix}[dependencies]\n${result.name} = \"$version\"\n")
    }

    private fun hasTomlSection(text: String, name: String): Boolean =
        text.lineSequence().any { it.substringBefore('#').trim() == "[$name]" }
}
