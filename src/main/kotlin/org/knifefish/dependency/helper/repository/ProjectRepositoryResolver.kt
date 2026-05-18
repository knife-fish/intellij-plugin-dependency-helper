package org.knifefish.dependency.helper.repository

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import org.knifefish.dependency.helper.model.DependencyFileKind
import org.knifefish.dependency.helper.model.DependencyFiles
import org.knifefish.dependency.helper.model.Ecosystem
import org.knifefish.dependency.helper.model.RepositorySpec
import org.knifefish.dependency.helper.util.readAction
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.exists

class ProjectRepositoryResolver(private val project: Project) {

    fun resolveForProject(): Map<Ecosystem, List<RepositorySpec>> {
        return readAction {
            val byEcosystem = mutableMapOf<Ecosystem, MutableList<RepositorySpec>>()
            Ecosystem.entries.forEach { byEcosystem[it] = mutableListOf() }
            project.guessProjectDir()?.let { root ->
                collectProjectRepositories(root, byEcosystem)
            }
            collectUserRepositories(byEcosystem)
            byEcosystem.mapValues { (ecosystem, repos) ->
                repos.distinctBy { it.url to it.pluginUrl }.ifEmpty { listOf(ecosystem.defaultRepository) }
            }
        }
    }

    private fun collectProjectRepositories(root: VirtualFile, repos: MutableMap<Ecosystem, MutableList<RepositorySpec>>) {
        walkProject(root).forEach { file ->
            when (DependencyFiles.kindOf(file)) {
                DependencyFileKind.MAVEN_POM -> addPomRepositories(file, repos[Ecosystem.MAVEN]!!)
                DependencyFileKind.GRADLE_BUILD,
                DependencyFileKind.GRADLE_SETTINGS -> addGradleRepositories(file, repos[Ecosystem.GRADLE]!!)
                DependencyFileKind.NPM_PACKAGE_JSON,
                null -> if (file.name == ".npmrc") addNpmRepositories(file, repos[Ecosystem.NPM]!!)
            }
        }
    }

    private fun collectUserRepositories(repos: MutableMap<Ecosystem, MutableList<RepositorySpec>>) {
        addMavenSettingsRepositories(repos[Ecosystem.MAVEN]!!, Path.of(System.getProperty("user.home"), ".m2", "settings.xml"))
        addNpmRepositories(Path.of(System.getProperty("user.home"), ".npmrc"), repos[Ecosystem.NPM]!!)
    }

    private fun addMavenSettingsRepositories(
        target: MutableList<RepositorySpec>,
        path: Path,
    ) {
        if (!path.exists()) {
            return
        }
        addRepositorySpecs(Ecosystem.MAVEN, target, path.toString(), MavenSettingsRepositoryParser.parse(path))
    }

    private fun addPomRepositories(
        file: VirtualFile,
        target: MutableList<RepositorySpec>,
    ) {
        addRepositorySpecs(
            Ecosystem.MAVEN,
            target,
            file.path,
            MavenPomRepositoryParser.parse(file.inputStream.bufferedReader().use { it.readText() }),
        )
    }

    private fun addGradleRepositories(file: VirtualFile, target: MutableList<RepositorySpec>) {
        val text = file.inputStream.bufferedReader().use { it.readText() }
        addRepositorySpecs(Ecosystem.GRADLE, target, file.path, GradleRepositoryParser.parse(text))
    }

    private fun addNpmRepositories(file: VirtualFile, target: MutableList<RepositorySpec>) {
        val text = file.inputStream.bufferedReader().use { it.readText() }
        extractNpmRegistries(text).forEach { url ->
            target += RepositorySpec(Ecosystem.NPM, normalizeUrl(url), file.path, supportsSearch(url, Ecosystem.NPM))
        }
    }

    private fun addNpmRepositories(path: Path, target: MutableList<RepositorySpec>) {
        if (!path.exists()) {
            return
        }
        val text = Files.readString(path)
        extractNpmRegistries(text).forEach { url ->
            target += RepositorySpec(Ecosystem.NPM, normalizeUrl(url), path.toString(), supportsSearch(url, Ecosystem.NPM))
        }
    }

    private fun addRepositorySpecs(
        ecosystem: Ecosystem,
        target: MutableList<RepositorySpec>,
        source: String,
        repositories: ParsedRepositories,
    ) {
        if (repositories.pluginRepositories.isEmpty()) {
            repositories.repositories.forEach { url ->
                target += RepositorySpec(ecosystem, normalizeUrl(url), source, supportsSearch(url, ecosystem))
            }
            return
        }

        val normalUrls = repositories.repositories.ifEmpty { listOf(ecosystem.defaultRepository.url) }
        normalUrls.forEach { url ->
            repositories.pluginRepositories.forEach { pluginUrl ->
                target += RepositorySpec(
                    ecosystem = ecosystem,
                    url = normalizeUrl(url),
                    source = source,
                    supportsSearch = supportsSearch(url, ecosystem),
                    pluginUrl = normalizeUrl(pluginUrl),
                )
            }
        }
    }

    private fun walkProject(root: VirtualFile): Sequence<VirtualFile> = sequence {
        val stack = ArrayDeque<VirtualFile>()
        stack += root
        while (stack.isNotEmpty()) {
            val file = stack.removeLast()
            if (file.isDirectory) {
                file.children
                    .filterNot { it.name.startsWith(".idea") || it.name == "build" || it.name == ".gradle" || it.name == ".git" || it.name == "node_modules" }
                    .forEach { stack += it }
            } else {
                yield(file)
            }
        }
    }

    private fun supportsSearch(url: String, ecosystem: Ecosystem): Boolean = when (ecosystem) {
        Ecosystem.MAVEN, Ecosystem.GRADLE ->
            url.contains("search.maven.org") ||
                url.contains("repo1.maven.org") ||
                url.contains("repo.maven.apache.org") ||
                url.contains("plugins.gradle.org") ||
                url.contains("nexus", ignoreCase = true) ||
                url.contains("artifactory", ignoreCase = true)
        Ecosystem.NPM -> true
    }

    private fun normalizeUrl(url: String): String = if (url.endsWith("/")) url else "$url/"

    private fun extractNpmRegistries(text: String): List<String> {
        val result = mutableListOf<String>()
        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEach
            val key = line.substring(0, eq).trim()
            if (key != "registry") return@forEach
            val value = line.substring(eq + 1).trim().trim('"', '\'')
            if (value.startsWith("http://") || value.startsWith("https://")) {
                result += value
            }
        }
        return result
    }

}

internal data class ParsedRepositories(
    val repositories: List<String>,
    val pluginRepositories: List<String> = emptyList(),
)

internal object MavenSettingsRepositoryParser {

    fun parse(path: Path): ParsedRepositories {
        val document = runCatching {
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
            }.newDocumentBuilder().parse(path.toFile())
        }.getOrNull() ?: return ParsedRepositories(emptyList())
        val root = document.documentElement ?: return ParsedRepositories(emptyList())
        val activeProfiles = activeProfileIds(root)
        val profileRepositories = repositoriesFromProfiles(root, activeProfiles)
        val profilePluginRepositories = pluginRepositoriesFromProfiles(root, activeProfiles)
        val mirrors = mirrorUrls(root)
        return ParsedRepositories(
            repositories = (mirrors + profileRepositories).map(::normalizeUrl).distinct(),
            pluginRepositories = (mirrors + profilePluginRepositories).map(::normalizeUrl).distinct(),
        )
    }

    private fun activeProfileIds(root: Element): Set<String> {
        val explicit = child(root, "activeProfiles")
            ?.children("activeProfile")
            .orEmpty()
            .mapNotNull { it.textContent?.trim()?.takeIf(String::isNotEmpty) }
            .toMutableSet()
        if (explicit.isNotEmpty()) {
            return explicit
        }
        return child(root, "profiles")
            ?.children("profile")
            .orEmpty()
            .filter { profile ->
                child(profile, "activation")
                    ?.let { activation -> child(activation, "activeByDefault")?.textContent?.trim()?.equals("true", ignoreCase = true) == true }
                    ?: false
            }
            .mapNotNull { child(it, "id")?.textContent?.trim()?.takeIf(String::isNotEmpty) }
            .toSet()
    }

    private fun repositoriesFromProfiles(root: Element, activeProfileIds: Set<String>): List<String> {
        return child(root, "profiles")
            ?.children("profile")
            .orEmpty()
            .filter { profile ->
                val id = child(profile, "id")?.textContent?.trim()
                id != null && id in activeProfileIds
            }
            .flatMap { profile ->
                child(profile, "repositories")
                    ?.children("repository")
                    .orEmpty()
                    .mapNotNull { child(it, "url")?.textContent?.trim()?.takeIf(String::isNotEmpty) }
            }
    }

    private fun pluginRepositoriesFromProfiles(root: Element, activeProfileIds: Set<String>): List<String> {
        return child(root, "profiles")
            ?.children("profile")
            .orEmpty()
            .filter { profile ->
                val id = child(profile, "id")?.textContent?.trim()
                id != null && id in activeProfileIds
            }
            .flatMap { profile ->
                child(profile, "pluginRepositories")
                    ?.children("pluginRepository")
                    .orEmpty()
                    .mapNotNull { child(it, "url")?.textContent?.trim()?.takeIf(String::isNotEmpty) }
            }
    }

    private fun mirrorUrls(root: Element): List<String> {
        return child(root, "mirrors")
            ?.children("mirror")
            .orEmpty()
            .mapNotNull { child(it, "url")?.textContent?.trim()?.takeIf(String::isNotEmpty) }
    }

    private fun child(element: Element, name: String): Element? =
        element.children(name).firstOrNull()

    private fun Element.children(name: String): List<Element> {
        val result = mutableListOf<Element>()
        val nodes = childNodes
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node is Element && node.tagName == name) {
                result += node
            }
        }
        return result
    }

    private fun normalizeUrl(url: String): String = if (url.endsWith("/")) url else "$url/"
}

internal object MavenPomRepositoryParser {

    fun parse(xml: String): ParsedRepositories {
        val document = runCatching {
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
            }.newDocumentBuilder().parse(xml.byteInputStream())
        }.getOrNull() ?: return ParsedRepositories(emptyList())
        val root = document.documentElement ?: return ParsedRepositories(emptyList())
        val repositories = mutableListOf<String>()
        val pluginRepositories = mutableListOf<String>()
        collectRepositories(root, repositories)
        collectPluginRepositories(root, pluginRepositories)
        child(root, "profiles")
            ?.children("profile")
            .orEmpty()
            .forEach { profile ->
                collectRepositories(profile, repositories)
                collectPluginRepositories(profile, pluginRepositories)
            }
        return ParsedRepositories(
            repositories = repositories.map(::normalizeUrl).distinct(),
            pluginRepositories = pluginRepositories.map(::normalizeUrl).distinct(),
        )
    }

    private fun collectRepositories(element: Element, target: MutableList<String>) {
        child(element, "repositories")
            ?.children("repository")
            .orEmpty()
            .mapNotNull { repository -> child(repository, "url")?.textContent?.trim()?.takeIf(String::isNotEmpty) }
            .forEach(target::add)
    }

    private fun collectPluginRepositories(element: Element, target: MutableList<String>) {
        child(element, "pluginRepositories")
            ?.children("pluginRepository")
            .orEmpty()
            .mapNotNull { repository -> child(repository, "url")?.textContent?.trim()?.takeIf(String::isNotEmpty) }
            .forEach(target::add)
    }

    private fun child(element: Element, name: String): Element? =
        element.children(name).firstOrNull()

    private fun Element.children(name: String): List<Element> {
        val result = mutableListOf<Element>()
        val nodes = childNodes
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node is Element && node.tagName == name) {
                result += node
            }
        }
        return result
    }

    private fun normalizeUrl(url: String): String = if (url.endsWith("/")) url else "$url/"
}

internal object GradleRepositoryParser {

    fun parse(text: String): ParsedRepositories {
        val normalRanges = findNamedBlockRanges(text, "repositories")
            .filterNot { range -> isInsideNamedBlock(text, range.first, "pluginManagement") }
        val pluginRanges = findNamedBlockRanges(text, "repositories")
            .filter { range -> isInsideNamedBlock(text, range.first, "pluginManagement") }
        return ParsedRepositories(
            repositories = normalRanges.flatMap { range -> repositoryUrlsFromBlock(text.substring(range.first, range.last + 1)) }
                .map(::normalizeUrl)
                .distinct(),
            pluginRepositories = pluginRanges.flatMap { range -> repositoryUrlsFromBlock(text.substring(range.first, range.last + 1)) }
                .map(::normalizeUrl)
                .distinct(),
        )
    }

    private fun repositoryUrlsFromBlock(block: String): List<String> {
        val result = mutableListOf<String>()
        if (block.contains("mavenCentral()")) {
            result += "https://repo.maven.apache.org/maven2/"
        }
        if (block.contains("google()")) {
            result += "https://dl.google.com/dl/android/maven2/"
        }
        if (block.contains("gradlePluginPortal()")) {
            result += "https://plugins.gradle.org/m2/"
        }
        block.lineSequence().forEach { raw ->
            val line = stripLineComment(raw).trim()
            if (line.contains("url")) {
                extractQuotedHttpValues(line).forEach(result::add)
            }
        }
        return result.distinct()
    }

    private fun findNamedBlockRanges(text: String, name: String): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var index = 0
        while (index < text.length) {
            val nameIndex = text.indexOf(name, index)
            if (nameIndex < 0) break
            if (!isIdentifierBoundary(text, nameIndex - 1) || !isIdentifierBoundary(text, nameIndex + name.length)) {
                index = nameIndex + name.length
                continue
            }
            val brace = text.indexOf('{', nameIndex + name.length)
            if (brace < 0) break
            val between = text.substring(nameIndex + name.length, brace)
            if (between.any { !it.isWhitespace() && it != '(' && it != ')' }) {
                index = nameIndex + name.length
                continue
            }
            val end = matchingBrace(text, brace)
            if (end != null) {
                result += nameIndex..end
                index = end + 1
            } else {
                index = brace + 1
            }
        }
        return result
    }

    private fun isInsideNamedBlock(text: String, offset: Int, name: String): Boolean =
        findNamedBlockRanges(text, name).any { range -> offset in range }

    private fun matchingBrace(text: String, openBrace: Int): Int? {
        var depth = 0
        var quote: Char? = null
        var index = openBrace
        while (index < text.length) {
            val ch = text[index]
            when {
                quote != null -> {
                    if (ch == quote && text.getOrNull(index - 1) != '\\') quote = null
                }
                ch == '"' || ch == '\'' -> quote = ch
                ch == '{' -> depth++
                ch == '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return null
    }

    private fun isIdentifierBoundary(text: String, index: Int): Boolean {
        if (index !in text.indices) return true
        val ch = text[index]
        return !ch.isLetterOrDigit() && ch != '_' && ch != '-'
    }

    private fun extractQuotedHttpValues(text: String): List<String> {
        val values = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (ch != '"' && ch != '\'') {
                index++
                continue
            }
            val quote = ch
            var end = index + 1
            while (end < text.length && text[end] != quote) {
                end++
            }
            if (end >= text.length) break
            val candidate = text.substring(index + 1, end).trim()
            if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
                values += candidate
            }
            index = end + 1
        }
        return values
    }

    private fun stripLineComment(text: String): String {
        var quote: Char? = null
        var index = 0
        while (index < text.length - 1) {
            val ch = text[index]
            when {
                quote != null -> {
                    if (ch == quote && text.getOrNull(index - 1) != '\\') quote = null
                }
                ch == '"' || ch == '\'' -> quote = ch
                ch == '/' && text[index + 1] == '/' -> return text.substring(0, index)
            }
            index++
        }
        return text
    }

    private fun normalizeUrl(url: String): String = if (url.endsWith("/")) url else "$url/"
}
