package org.knifefish.dependency.helper.services

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import org.knifefish.dependency.helper.model.*
import org.knifefish.dependency.helper.repository.ProjectRepositoryResolver
import org.knifefish.dependency.helper.scanner.DependencyFileScanner
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class DependencyInsightService(private val project: Project) {

    private val scanner = DependencyFileScanner()
    private val indexClient = PackageIndexClient()
    private val cache = ConcurrentHashMap<String, CachedVersion>()
    @Volatile
    private var latestVersionPolicy: LatestVersionPolicy = LatestVersionPolicy.RELEASE_ONLY

    fun scanProject(): DependencySnapshot {
        return ReadAction.compute<DependencySnapshot, RuntimeException> {
            val repositories = ProjectRepositoryResolver(project).resolveForProject()
            val dependencies = mutableListOf<DependencyCoordinate>()
            val baseDir = project.guessProjectDir() ?: return@compute DependencySnapshot(emptyList(), repositories)
            VfsUtilCore.iterateChildrenRecursively(baseDir, { file ->
                val excluded = file.path.contains("/build/") || file.path.contains("/.gradle/") || file.path.contains("/.git/")
                !excluded
            }) { file ->
                if (!file.isDirectory && scanner.supports(file)) {
                    readText(file)?.let { text -> dependencies += scanner.scan(file, text) }
                }
                true
            }
            DependencySnapshot(dependencies.sortedBy { it.file.path + it.lineNumber }, repositories)
        }
    }

    fun scanFile(file: VirtualFile): List<DependencyCoordinate> =
        ReadAction.compute<List<DependencyCoordinate>, RuntimeException> {
            readText(file)?.let { text ->
                val scanned = scanner.scan(file, text)
                when {
                    file.name == "pom.xml" -> project.getService(MavenSupport::class.java)?.enrichDependencies(file, scanned) ?: scanned
                    file.name in setOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts") ->
                        project.getService(GradleSupport::class.java)?.enrichDependencies(file, scanned) ?: scanned
                    else -> scanned
                }
            } ?: emptyList()
        }

    fun repositoriesFor(ecosystem: Ecosystem): List<RepositorySpec> =
        scanProject().repositories[ecosystem].orEmpty()

    fun lookupLatestVersion(
        dependency: DependencyCoordinate,
        repositories: List<RepositorySpec>,
        forceRefresh: Boolean = false,
    ): VersionInfo {
        val cacheKey = cacheKey(dependency, repositories)
        if (forceRefresh) {
            cache.remove(cacheKey)
        }
        val existing = cache[cacheKey]
        if (existing != null && !existing.isExpired()) {
            return existing.info
        }
        val info = indexClient.findLatestVersion(dependency, repositories, latestVersionPolicy)
        cache[cacheKey] = CachedVersion(info)
        return info
    }

    fun lookupLatestVersionIfCached(dependency: DependencyCoordinate, repositories: List<RepositorySpec>): VersionInfo? {
        return cache[cacheKey(dependency, repositories)]?.takeUnless { it.isExpired() }?.info
    }

    fun latestVersionPolicy(): LatestVersionPolicy = latestVersionPolicy

    fun setLatestVersionPolicy(policy: LatestVersionPolicy) {
        if (latestVersionPolicy == policy) {
            return
        }
        latestVersionPolicy = policy
        cache.clear()
        refreshOpenEditors()
    }

    fun refreshOpenEditors() {
        val editors = FileEditorManager.getInstance(project).selectedTextEditor?.let { listOf(it) }.orEmpty()
        editors.forEach { refreshEditor(it) }
    }

    fun refreshEditor(editor: Editor) {
        refreshEditor(editor, null)
    }

    fun refreshEditorsForFile(
        file: VirtualFile,
        forceRefresh: ((DependencyCoordinate) -> Boolean)? = null,
    ) {
        val editors = FileEditorManager.getInstance(project)
            .getAllEditors(file)
            .mapNotNull { (it as? TextEditor)?.editor }
        editors.forEach { refreshEditor(it, forceRefresh) }
    }

    fun refreshEditor(
        editor: Editor,
        forceRefresh: ((DependencyCoordinate) -> Boolean)?,
    ) {
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val dependencies = scanFile(file)
        if (dependencies.isEmpty()) {
            DependencyInlayManager.clear(editor)
            return
        }
        val repositories = ProjectRepositoryResolver(project).resolveForProject()
        ApplicationManager.getApplication().executeOnPooledThread {
            val lookups = dependencies.map { dependency ->
                val info = lookupLatestVersion(
                    dependency,
                    repositories[dependency.ecosystem].orEmpty(),
                    forceRefresh = forceRefresh?.invoke(dependency) == true,
                )
                DependencyLookupResult(dependency, info)
            }
            ApplicationManager.getApplication().invokeLater {
                DependencyInlayManager.render(editor, lookups)
                DaemonCodeAnalyzer.getInstance(project).restart()
            }
        }
    }

    fun searchPackages(ecosystem: Ecosystem, query: String): List<PackageSearchResult> {
        val repositories = ProjectRepositoryResolver(project).resolveForProject()[ecosystem].orEmpty()
        return indexClient.search(ecosystem, query, repositories)
    }

    fun upgradeDependency(dependency: DependencyCoordinate, newVersion: String): Boolean {
        val document = FileDocumentManager.getInstance().getDocument(dependency.file) ?: return false
        if (dependency.ecosystem == Ecosystem.MAVEN && dependency.usesManagedVersion) {
            val upgraded = project.getService(MavenSupport::class.java)?.upgradeManagedDependency(dependency, newVersion)
            if (upgraded != true) {
                return false
            }
            project.getService(MavenSupport::class.java)?.refreshMavenProject(dependency.file) {
                refreshEditorsForFile(dependency.file) { candidate -> sameArtifact(candidate, dependency) }
            }
            return true
        }
        if (dependency.ecosystem == Ecosystem.GRADLE) {
            val upgraded = project.getService(GradleSupport::class.java)?.upgradeDependency(dependency, newVersion)
            if (upgraded == true) {
                project.getService(GradleSupport::class.java)?.refreshGradleProject(dependency.file) {
                    refreshEditorsForFile(dependency.file) { candidate -> sameArtifact(candidate, dependency) }
                } ?: refreshEditorsForFile(dependency.file) { candidate -> sameArtifact(candidate, dependency) }
                return true
            }
        }
        val replacement = resolveVersionReplacement(dependency, document.text, newVersion) ?: return false
        WriteCommandAction.runWriteCommandAction(project, Runnable {
            if (replacement.range.endOffset > document.textLength) {
                return@Runnable
            }
            document.replaceString(replacement.range.startOffset, replacement.range.endOffset, replacement.newDeclaration)
            FileDocumentManager.getInstance().saveDocument(document)
        })
        if (dependency.ecosystem == Ecosystem.MAVEN) {
            project.getService(MavenSupport::class.java)?.refreshMavenProject(dependency.file) {
                refreshEditorsForFile(dependency.file) { candidate -> sameArtifact(candidate, dependency) }
            }
        } else if (dependency.ecosystem == Ecosystem.GRADLE) {
            project.getService(GradleSupport::class.java)?.refreshGradleProject(dependency.file) {
                refreshEditorsForFile(dependency.file) { candidate -> sameArtifact(candidate, dependency) }
            } ?: refreshEditorsForFile(dependency.file) { candidate -> sameArtifact(candidate, dependency) }
        } else {
            refreshEditorsForFile(dependency.file) { candidate -> sameArtifact(candidate, dependency) }
        }
        return true
    }

    fun dependencyAt(file: VirtualFile, offset: Int): DependencyCoordinate? =
        scanFile(file).firstOrNull { marker ->
            marker.versionRange?.let { offset in it.startOffset..it.endOffset } == true ||
                offset in marker.inspectionRange.startOffset..marker.inspectionRange.endOffset
        }

    private fun readText(file: VirtualFile): String? = runCatching {
        file.inputStream.bufferedReader().use { it.readText() }
    }.onFailure {
        thisLogger().warn("Failed to read ${file.path}", it)
    }.getOrNull()

    private fun cacheKey(dependency: DependencyCoordinate, repositories: List<RepositorySpec>): String {
        return "${dependency.key}:${latestVersionPolicy.name}:${repositories.joinToString(",") { it.url }}"
    }

    private fun sameArtifact(left: DependencyCoordinate, right: DependencyCoordinate): Boolean {
        return left.ecosystem == right.ecosystem &&
            left.group == right.group &&
            left.name == right.name
    }

    private fun resolveVersionReplacement(
        dependency: DependencyCoordinate,
        currentText: String,
        newVersion: String,
    ): DeclarationReplacement? {
        val scanned = scanner.scan(dependency.file, currentText)
        val currentDependencies = when (dependency.file.name) {
            "pom.xml" -> project.getService(MavenSupport::class.java)?.enrichDependencies(dependency.file, scanned) ?: scanned
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts" ->
                project.getService(GradleSupport::class.java)?.enrichDependencies(dependency.file, scanned) ?: scanned
            else -> scanned
        }
        val target = currentDependencies
            .filter { candidate ->
                candidate.ecosystem == dependency.ecosystem &&
                    candidate.group == dependency.group &&
                    candidate.name == dependency.name &&
                    candidate.version == dependency.version
            }
            .minByOrNull { kotlin.math.abs(it.lineNumber - dependency.lineNumber) }
            ?: return null

        val declarationRange = target.inspectionRange
        if (declarationRange.endOffset > currentText.length) {
            return null
        }
        val declaration = currentText.substring(declarationRange.startOffset, declarationRange.endOffset)
        val updatedDeclaration = replaceVersionInDeclaration(target, declaration, newVersion) ?: return null
        return DeclarationReplacement(declarationRange, updatedDeclaration)
    }

    private fun replaceVersionInDeclaration(
        dependency: DependencyCoordinate,
        declaration: String,
        newVersion: String,
    ): String? {
        val oldVersion = dependency.declaredVersion ?: dependency.version
        if (oldVersion.isBlank()) {
            return null
        }
        return when (dependency.ecosystem) {
            Ecosystem.MAVEN -> {
                val pattern = Regex("(<version>\\s*)${Regex.escape(oldVersion)}(\\s*</version>)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                if (!pattern.containsMatchIn(declaration)) null else declaration.replaceFirst(pattern, "$1$newVersion$2")
            }
            Ecosystem.GRADLE -> {
                val pattern = Regex("(:)${Regex.escape(oldVersion)}([\"'])")
                if (!pattern.containsMatchIn(declaration)) null else declaration.replaceFirst(pattern, "$1$newVersion$2")
            }
            Ecosystem.NPM -> {
                val pattern = Regex("(\"${Regex.escape(dependency.name)}\"\\s*:\\s*\")${Regex.escape(oldVersion)}(\")")
                if (!pattern.containsMatchIn(declaration)) null else declaration.replaceFirst(pattern, "$1$newVersion$2")
            }
            Ecosystem.PYTHON -> {
                val namePattern = Regex.escape(dependency.name)
                val pattern = Regex("($namePattern\\s*(?:==|>=|<=|~=|!=|>|<)\\s*)${Regex.escape(oldVersion)}")
                if (!pattern.containsMatchIn(declaration)) null else declaration.replaceFirst(pattern, "$1$newVersion")
            }
            Ecosystem.RUST -> {
                val inlinePattern = Regex("(${Regex.escape(dependency.name)}\\s*=\\s*\")${Regex.escape(oldVersion)}(\")")
                when {
                    inlinePattern.containsMatchIn(declaration) -> declaration.replaceFirst(inlinePattern, "$1$newVersion$2")
                    else -> {
                        val tablePattern = Regex("(version\\s*=\\s*\")${Regex.escape(oldVersion)}(\")")
                        if (!tablePattern.containsMatchIn(declaration)) null else declaration.replaceFirst(tablePattern, "$1$newVersion$2")
                    }
                }
            }
        }
    }

    private data class DeclarationReplacement(
        val range: TextRangeMarker,
        val newDeclaration: String,
    )

    private data class CachedVersion(
        val info: VersionInfo,
        val timestamp: Long = System.currentTimeMillis(),
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > 10 * 60 * 1000
    }
}

fun Project.dependencyInsightService(): DependencyInsightService = service()
