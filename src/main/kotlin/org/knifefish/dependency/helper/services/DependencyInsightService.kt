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
import org.jetbrains.annotations.VisibleForTesting
import org.knifefish.dependency.helper.model.*
import org.knifefish.dependency.helper.repository.ProjectRepositoryResolver
import org.knifefish.dependency.helper.services.ecosystem.DependencyDeclarationRewriter
import org.knifefish.dependency.helper.services.ecosystem.DependencyInsertion
import org.knifefish.dependency.helper.services.ecosystem.DependencyInsertionPlanner
import org.knifefish.dependency.helper.services.external.ExternalDependencySystems
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class DependencyInsightService(private val project: Project) {

    private val indexClient = PackageIndexClient()
    private val externalSystems = ExternalDependencySystems(project)
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
                if (!file.isDirectory && externalSystems.supports(file)) {
                    dependencies += scanFileInternal(file)
                }
                true
            }
            DependencySnapshot(dependencies.sortedBy { it.file.path + it.lineNumber }, repositories)
        }
    }

    fun scanFile(file: VirtualFile): List<DependencyCoordinate> =
        ReadAction.compute<List<DependencyCoordinate>, RuntimeException> {
            scanFileInternal(file)
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

    fun availableVersions(ecosystem: Ecosystem, group: String?, name: String): List<String> {
        val repositories = ProjectRepositoryResolver(project).resolveForProject()[ecosystem].orEmpty()
        return indexClient.availableVersions(ecosystem, group, name, repositories)
    }

    fun addDependency(targetFile: VirtualFile, result: PackageSearchResult, version: String): Boolean {
        val document = FileDocumentManager.getInstance().getDocument(targetFile) ?: return false
        val insertion = buildDependencyInsertion(targetFile.name, result, version, document.text) ?: return false
        WriteCommandAction.runWriteCommandAction(project, Runnable {
            document.insertString(insertion.offset, insertion.text)
            FileDocumentManager.getInstance().saveDocument(document)
        })
        if (!externalSystems.refresh(targetFile) {
                refreshEditorsForFile(targetFile)
            }) {
            refreshEditorsForFile(targetFile)
        }
        return true
    }

    fun upgradeDependency(dependency: DependencyCoordinate, newVersion: String): Boolean {
        val document = FileDocumentManager.getInstance().getDocument(dependency.file) ?: return false
        val upgradedByExternal = externalSystems.upgrade(dependency, newVersion)
        if (upgradedByExternal) {
            if (!externalSystems.refresh(dependency.file) {
                    refreshEditorsForFile(dependency.file) { candidate -> sameArtifact(candidate, dependency) }
                }) {
                refreshEditorsForFile(dependency.file) { candidate -> sameArtifact(candidate, dependency) }
            }
            return true
        }
        val replacement = resolveVersionReplacement(dependency, document.text, newVersion) ?: return false
        WriteCommandAction.runWriteCommandAction(project, Runnable {
            if (replacement.range.endOffset > document.textLength) {
                return@Runnable
            }
            document.replaceString(replacement.range.startOffset, replacement.range.endOffset, replacement.newDeclaration)
            FileDocumentManager.getInstance().saveDocument(document)
        })
        if (!externalSystems.refresh(dependency.file) {
                refreshEditorsForFile(dependency.file) { candidate -> sameArtifact(candidate, dependency) }
            }) {
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
        val currentDependencies = scanFileInternal(dependency.file)
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
        return DependencyDeclarationRewriter.replaceVersionInDeclaration(
            dependency = dependency,
            declaration = declaration,
            newVersion = newVersion,
        )
    }

    private fun scanFileInternal(file: VirtualFile): List<DependencyCoordinate> {
        val scanned = externalSystems.scan(file)
        return externalSystems.enrich(file, scanned)
    }

    private data class DeclarationReplacement(
        val range: TextRangeMarker,
        val newDeclaration: String,
    )

    @VisibleForTesting
    internal fun buildDependencyInsertion(
        fileName: String,
        result: PackageSearchResult,
        version: String,
        text: String,
    ): DependencyInsertion? = DependencyInsertionPlanner.buildDependencyInsertion(fileName, result, version, text)

    private data class CachedVersion(
        val info: VersionInfo,
        val timestamp: Long = System.currentTimeMillis(),
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > 10 * 60 * 1000
    }

}

fun Project.dependencyInsightService(): DependencyInsightService = service()

internal fun npmUpgradedVersionValue(oldVersion: String, newVersion: String): String? =
    org.knifefish.dependency.helper.services.ecosystem.npmUpgradedVersionValue(oldVersion, newVersion)

internal fun hasRecommendedUpgrade(dependency: DependencyCoordinate, latestStable: String?): Boolean =
    org.knifefish.dependency.helper.services.ecosystem.hasRecommendedUpgrade(dependency, latestStable)
