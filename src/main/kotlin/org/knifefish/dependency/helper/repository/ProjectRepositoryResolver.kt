package org.knifefish.dependency.helper.repository

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import org.knifefish.dependency.helper.model.Ecosystem
import org.knifefish.dependency.helper.model.RepositorySpec
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.exists

class ProjectRepositoryResolver(private val project: Project) {

    fun resolveForProject(): Map<Ecosystem, List<RepositorySpec>> {
        return ReadAction.compute<Map<Ecosystem, List<RepositorySpec>>, RuntimeException> {
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
                    addRegexRepositories(file, Ecosystem.GRADLE, repos[Ecosystem.GRADLE]!!, Regex("""url\s*[=]?\s*["'](https?://[^"']+)["']"""), file.path)
                ".npmrc" -> addRegexRepositories(file, Ecosystem.NPM, repos[Ecosystem.NPM]!!, Regex("""(?m)^registry=(https?://.+)$"""), file.path)
                "pyproject.toml" -> addRegexRepositories(file, Ecosystem.PYTHON, repos[Ecosystem.PYTHON]!!, Regex("""url\s*=\s*["'](https?://[^"']+)["']"""), file.path)
                "Cargo.toml" -> addRegexRepositories(file, Ecosystem.RUST, repos[Ecosystem.RUST]!!, Regex("""index\s*=\s*["'](https?://[^"']+)["']"""), file.path)
            }
        }
    }

    private fun collectUserRepositories(repos: MutableMap<Ecosystem, MutableList<RepositorySpec>>) {
        addMavenSettingsRepositories(repos[Ecosystem.MAVEN]!!, Path.of(System.getProperty("user.home"), ".m2", "settings.xml"))
        addPathRegex(Ecosystem.NPM, repos[Ecosystem.NPM]!!, Path.of(System.getProperty("user.home"), ".npmrc"), Regex("""(?m)^registry=(https?://.+)$"""))
        addPathRegex(Ecosystem.PYTHON, repos[Ecosystem.PYTHON]!!, Path.of(System.getProperty("user.home"), ".pip", "pip.conf"), Regex("""index-url\s*=\s*(https?://\S+)"""))
        addPathRegex(Ecosystem.RUST, repos[Ecosystem.RUST]!!, Path.of(System.getProperty("user.home"), ".cargo", "config.toml"), Regex("""index\s*=\s*["'](https?://[^"']+)["']"""))
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

    private fun addRegexRepositories(
        file: VirtualFile,
        ecosystem: Ecosystem,
        target: MutableList<RepositorySpec>,
        regex: Regex,
        source: String,
    ) {
        val text = file.inputStream.bufferedReader().use { it.readText() }
        regex.findAll(text).forEach { match ->
            target += RepositorySpec(ecosystem, normalizeUrl(match.groupValues[1]), source, supportsSearch(match.groupValues[1], ecosystem))
        }
    }

    private fun addPathRegex(
        ecosystem: Ecosystem,
        target: MutableList<RepositorySpec>,
        path: Path,
        regex: Regex,
    ) {
        if (!path.exists()) {
            return
        }
        val text = Files.readString(path)
        regex.findAll(text).forEach { match ->
            target += RepositorySpec(ecosystem, normalizeUrl(match.groupValues[1]), path.toString(), supportsSearch(match.groupValues[1], ecosystem))
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
        Ecosystem.PYTHON ->
            RepositorySpec(ecosystem, "https://pypi.org/", "default", supportsSearch = false)
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
        Ecosystem.PYTHON -> url.contains("pypi.org")
        Ecosystem.RUST -> url.contains("crates.io")
    }

    private fun normalizeUrl(url: String): String = if (url.endsWith("/")) url else "$url/"
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
