package org.knifefish.dependency.helper.repository

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
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
                repos.distinctBy { it.url }.ifEmpty { listOf(defaultRepository(ecosystem)) }
            }
        }
    }

    private fun collectProjectRepositories(root: VirtualFile, repos: MutableMap<Ecosystem, MutableList<RepositorySpec>>) {
        walkProject(root).forEach { file ->
            when (file.name) {
                "pom.xml" -> addPomRepositories(file, repos[Ecosystem.MAVEN]!!)
                "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts" ->
                    addGradleRepositories(file, repos[Ecosystem.GRADLE]!!)
                ".npmrc" -> addNpmRepositories(file, repos[Ecosystem.NPM]!!)
                "Cargo.toml" -> addTomlRepositories(file, repos[Ecosystem.RUST]!!, setOf("index"))
            }
        }
    }

    private fun collectUserRepositories(repos: MutableMap<Ecosystem, MutableList<RepositorySpec>>) {
        addMavenSettingsRepositories(repos[Ecosystem.MAVEN]!!, Path.of(System.getProperty("user.home"), ".m2", "settings.xml"))
        addNpmRepositories(Path.of(System.getProperty("user.home"), ".npmrc"), repos[Ecosystem.NPM]!!)
        addTomlRepositories(Path.of(System.getProperty("user.home"), ".cargo", "config.toml"), repos[Ecosystem.RUST]!!, setOf("index"))
    }

    private fun addMavenSettingsRepositories(
        target: MutableList<RepositorySpec>,
        path: Path,
    ) {
        if (!path.exists()) {
            return
        }
        MavenSettingsRepositoryParser.parse(path).forEach { url ->
            target += RepositorySpec(Ecosystem.MAVEN, normalizeUrl(url), path.toString(), supportsSearch(url, Ecosystem.MAVEN))
        }
    }

    private fun addPomRepositories(
        file: VirtualFile,
        target: MutableList<RepositorySpec>,
    ) {
        MavenPomRepositoryParser.parse(file.inputStream.bufferedReader().use { it.readText() }).forEach { url ->
            target += RepositorySpec(Ecosystem.MAVEN, normalizeUrl(url), file.path, supportsSearch(url, Ecosystem.MAVEN))
        }
    }

    private fun addGradleRepositories(file: VirtualFile, target: MutableList<RepositorySpec>) {
        val text = file.inputStream.bufferedReader().use { it.readText() }
        extractGradleUrls(text).forEach { url ->
            target += RepositorySpec(Ecosystem.GRADLE, normalizeUrl(url), file.path, supportsSearch(url, Ecosystem.GRADLE))
        }
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

    private fun addTomlRepositories(file: VirtualFile, target: MutableList<RepositorySpec>, keys: Set<String>) {
        val text = file.inputStream.bufferedReader().use { it.readText() }
        extractTomlUrls(text, keys).forEach { url ->
            val ecosystem = Ecosystem.RUST
            target += RepositorySpec(ecosystem, normalizeUrl(url), file.path, supportsSearch(url, ecosystem))
        }
    }

    private fun addTomlRepositories(path: Path, target: MutableList<RepositorySpec>, keys: Set<String>) {
        if (!path.exists()) return
        val text = Files.readString(path)
        extractTomlUrls(text, keys).forEach { url ->
            target += RepositorySpec(Ecosystem.RUST, normalizeUrl(url), path.toString(), supportsSearch(url, Ecosystem.RUST))
        }
    }

    private fun walkProject(root: VirtualFile): Sequence<VirtualFile> = sequence {
        val stack = ArrayDeque<VirtualFile>()
        stack += root
        while (stack.isNotEmpty()) {
            val file = stack.removeLast()
            if (file.isDirectory) {
                file.children
                    .filterNot { it.name.startsWith(".idea") || it.name == "build" || it.name == ".gradle" || it.name == ".git" }
                    .forEach { stack += it }
            } else {
                yield(file)
            }
        }
    }

    private fun defaultRepository(ecosystem: Ecosystem): RepositorySpec = when (ecosystem) {
        Ecosystem.MAVEN, Ecosystem.GRADLE ->
            RepositorySpec(ecosystem, "https://repo1.maven.org/maven2/", "default", supportsSearch = ecosystem == Ecosystem.MAVEN)
        Ecosystem.NPM ->
            RepositorySpec(ecosystem, "https://registry.npmjs.org/", "default", supportsSearch = true)
        Ecosystem.RUST ->
            RepositorySpec(ecosystem, "https://crates.io/", "default", supportsSearch = true)
    }

    private fun supportsSearch(url: String, ecosystem: Ecosystem): Boolean = when (ecosystem) {
        Ecosystem.MAVEN, Ecosystem.GRADLE ->
            url.contains("search.maven.org") ||
                url.contains("repo1.maven.org") ||
                url.contains("repo.maven.apache.org") ||
                url.contains("nexus", ignoreCase = true) ||
                url.contains("artifactory", ignoreCase = true)
        Ecosystem.NPM -> true
        Ecosystem.RUST -> url.contains("crates.io")
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

    private fun extractGradleUrls(text: String): List<String> {
        val result = mutableListOf<String>()
        text.lineSequence().forEach { line ->
            if (!line.contains("url")) return@forEach
            extractQuotedHttpValues(line).forEach(result::add)
        }
        return result
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

    private fun extractTomlUrls(text: String, keys: Set<String>): List<String> {
        val found = linkedSetOf<String>()
        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEach
            val key = line.substring(0, eq).trim().substringAfterLast('.')
            if (key !in keys) return@forEach
            extractQuotedHttpValues(line.substring(eq + 1)).forEach(found::add)
        }
        return found.toList()
    }
}

internal object MavenSettingsRepositoryParser {

    fun parse(path: Path): List<String> {
        val document = runCatching {
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
            }.newDocumentBuilder().parse(path.toFile())
        }.getOrNull() ?: return emptyList()
        val root = document.documentElement ?: return emptyList()
        val activeProfiles = activeProfileIds(root)
        val profileRepositories = repositoriesFromProfiles(root, activeProfiles)
        val mirrors = mirrorUrls(root)
        return (mirrors + profileRepositories).map(::normalizeUrl).distinct()
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

    fun parse(xml: String): List<String> {
        val document = runCatching {
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
            }.newDocumentBuilder().parse(xml.byteInputStream())
        }.getOrNull() ?: return emptyList()
        val root = document.documentElement ?: return emptyList()
        val repositories = mutableListOf<String>()
        collectRepositories(root, repositories)
        child(root, "profiles")
            ?.children("profile")
            .orEmpty()
            .forEach { profile -> collectRepositories(profile, repositories) }
        return repositories.map(::normalizeUrl).distinct()
    }

    private fun collectRepositories(element: Element, target: MutableList<String>) {
        child(element, "repositories")
            ?.children("repository")
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
