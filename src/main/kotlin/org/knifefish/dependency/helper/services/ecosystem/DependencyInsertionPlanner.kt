package org.knifefish.dependency.helper.services.ecosystem

import org.knifefish.dependency.helper.model.Ecosystem
import org.knifefish.dependency.helper.model.PackageSearchResult

internal object DependencyInsertionPlanner {

    fun buildDependencyInsertion(
        fileName: String,
        result: PackageSearchResult,
        version: String,
        text: String,
    ): DependencyInsertion? {
        return when (result.ecosystem) {
            Ecosystem.MAVEN -> buildMavenInsertion(fileName, result, version, text)
            Ecosystem.GRADLE -> buildGradleInsertion(fileName, result, version, text)
            Ecosystem.NPM, Ecosystem.RUST ->
                ecosystemSupport(result.ecosystem)
                    ?.buildDependencyInsertion(fileName, result, version, text)
        }
    }

    private fun buildMavenInsertion(
        fileName: String,
        result: PackageSearchResult,
        version: String,
        text: String,
    ): DependencyInsertion? {
        if (fileName != "pom.xml") return null
        val anchor = text.lowercase().indexOf("</dependencies>")
        if (anchor < 0) return null
        return DependencyInsertion(
            anchor,
            """

                <dependency>
                    <groupId>${result.group.orEmpty()}</groupId>
                    <artifactId>${result.name}</artifactId>
                    <version>$version</version>
                </dependency>

                """,
        )
    }

    private fun buildGradleInsertion(
        fileName: String,
        result: PackageSearchResult,
        version: String,
        text: String,
    ): DependencyInsertion? {
        if (fileName != "build.gradle" && fileName != "build.gradle.kts") return null
        val dependenciesBlock = locateGradleDependenciesBlockStart(text) ?: return null
        val anchor = dependenciesBlock + 1
        val notation = if (result.group.isNullOrBlank()) result.name else "${result.group}:${result.name}"
        return DependencyInsertion(anchor, "\n    implementation(\"$notation:$version\")")
    }

    private fun locateGradleDependenciesBlockStart(text: String): Int? {
        val key = "dependencies"
        var idx = text.indexOf(key)
        while (idx >= 0) {
            val after = idx + key.length
            var cursor = after
            while (cursor < text.length && text[cursor].isWhitespace()) cursor++
            if (cursor < text.length && text[cursor] == '{') {
                return cursor
            }
            idx = text.indexOf(key, after)
        }
        return null
    }

    private fun ecosystemSupport(ecosystem: Ecosystem): DependencyEcosystemSupport? {
        val extensions = runCatching { DependencyEcosystemSupport.EP_NAME.extensionList }.getOrDefault(emptyList())
        return extensions.firstOrNull { it.ecosystem == ecosystem }
            ?: when (ecosystem) {
                Ecosystem.NPM -> NpmDependencyEcosystemSupport()
                Ecosystem.RUST -> RustDependencyEcosystemSupport()
                else -> null
            }
    }
}

data class DependencyInsertion(
    val offset: Int,
    val text: String,
)
