package org.knifefish.dependency.helper.services

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.CodeStyleManager
import org.jetbrains.annotations.VisibleForTesting
import org.knifefish.dependency.helper.model.*
import org.knifefish.dependency.helper.repository.ProjectRepositoryResolver
import org.knifefish.dependency.helper.services.ecosystem.DependencyDeclarationRewriter
import org.knifefish.dependency.helper.services.ecosystem.DependencyInsertion
import org.knifefish.dependency.helper.services.ecosystem.DependencyInsertionPlanner
import org.knifefish.dependency.helper.services.external.ExternalDependencySystems
import org.knifefish.dependency.helper.util.readAction
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class DependencyInsightService(private val project: Project) {

    private val indexClient = PackageIndexClient()
    private val externalSystems = ExternalDependencySystems(project)
    private val cache = ConcurrentHashMap<String, CachedVersion>()
    @Volatile
    private var latestVersionPolicy: LatestVersionPolicy = LatestVersionPolicy.RELEASE_ONLY

    fun scanProject(): DependencySnapshot {
        return readAction {
            val repositories = ProjectRepositoryResolver(project).resolveForProject()
            val dependencies = mutableListOf<DependencyCoordinate>()
            val baseDir = project.guessProjectDir() ?: return@readAction DependencySnapshot(emptyList(), repositories)
            VfsUtilCore.iterateChildrenRecursively(baseDir, { file ->
                val excluded = file.path.contains("/build/") ||
                    file.path.contains("/.gradle/") ||
                    file.path.contains("/.git/") ||
                    file.path.contains("/node_modules/")
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
        readAction {
            scanFileInternal(file)
        }

    fun repositoriesFor(ecosystem: Ecosystem): List<RepositorySpec> =
        scanProject().repositories[ecosystem].orEmpty()

    fun lookupLatestVersion(
        dependency: DependencyCoordinate,
        repositories: List<RepositorySpec>,
        forceRefresh: Boolean = false,
    ): VersionInfo {
        val effectiveRepositories = repositoriesForLookup(dependency, repositories)
        val cacheKey = cacheKey(dependency, effectiveRepositories)
        if (forceRefresh) {
            cache.remove(cacheKey)
        }
        val existing = cache[cacheKey]
        if (existing != null && !existing.isExpired()) {
            return existing.info
        }
        val info = indexClient.findLatestVersion(dependency, effectiveRepositories, latestVersionPolicy)
        cache[cacheKey] = CachedVersion(info)
        return info
    }

    fun lookupLatestVersionIfCached(dependency: DependencyCoordinate, repositories: List<RepositorySpec>): VersionInfo? {
        return cache[cacheKey(dependency, repositoriesForLookup(dependency, repositories))]?.takeUnless { it.isExpired() }?.info
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
        ApplicationManager.getApplication().executeOnPooledThread {
            val dependencies = readAction {
                withEditorLocations(file, scanFileInternal(file, includeMavenPlugins = true))
                    .filter(::isEditorLocatable)
            }
            if (dependencies.isEmpty()) {
                ApplicationManager.getApplication().invokeLater {
                    DependencyInlayManager.clear(editor)
                }
                return@executeOnPooledThread
            }
            val repositories = ProjectRepositoryResolver(project).resolveForProject()
            val latestRule = latestVersionPolicy().displayName
            val lookups = dependencies.map { dependency ->
                val info = lookupLatestVersion(
                    dependency,
                    repositories[dependency.ecosystem].orEmpty(),
                    forceRefresh = forceRefresh?.invoke(dependency) == true,
                )
                DependencyLookupResult(dependency, info)
            }
            val managedOptions = lookups
                .filter { result -> result.dependency.usesManagedVersion && result.dependency.ecosystem == Ecosystem.MAVEN }
                .mapNotNull { result ->
                    val support = project.getService(MavenSupport::class.java) ?: return@mapNotNull null
                    result.dependency to support.resolveManagedUpgradeOptions(result.dependency)
                }
                .toMap()
            ApplicationManager.getApplication().invokeLater {
                DependencyInlayManager.render(editor, lookups, latestRule, managedOptions)
                DaemonCodeAnalyzer.getInstance(project).settingsChanged()
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
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(insertion.offset, insertion.text)
            reformatInsertedDependency(targetFile, insertion.offset, insertion.offset + insertion.text.length)
            FileDocumentManager.getInstance().saveDocument(document)
        }
        if (!externalSystems.refresh(targetFile) {
                refreshEditorsForFile(targetFile)
            }) {
            refreshEditorsForFile(targetFile)
        }
        return true
    }

    private fun reformatInsertedDependency(targetFile: VirtualFile, startOffset: Int, endOffset: Int) {
        if (!DependencyFiles.isMavenPom(targetFile)) {
            return
        }
        val psiDocumentManager = PsiDocumentManager.getInstance(project)
        val document = FileDocumentManager.getInstance().getDocument(targetFile) ?: return
        psiDocumentManager.commitDocument(document)
        val psiFile = psiDocumentManager.getPsiFile(document) ?: return
        val safeEndOffset = endOffset.coerceAtMost(document.textLength)
        if (startOffset >= safeEndOffset) {
            return
        }
        CodeStyleManager.getInstance(project).reformatText(psiFile, startOffset, safeEndOffset)
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
        readAction {
            withEditorLocations(file, scanFileInternal(file, includeMavenPlugins = true)).filter(::isEditorLocatable).firstOrNull { marker ->
                marker.versionRange?.let { offset in it.startOffset..it.endOffset } == true ||
                    offset in marker.inspectionRange.startOffset..marker.inspectionRange.endOffset
            }
        }

    private fun withEditorLocations(file: VirtualFile, dependencies: List<DependencyCoordinate>): List<DependencyCoordinate> {
        if (!DependencyFiles.isGradle(file) || dependencies.isEmpty()) {
            return dependencies
        }
        return project.getService(GradleSupport::class.java)?.attachEditorLocations(file, dependencies) ?: dependencies
    }

    private fun isEditorLocatable(dependency: DependencyCoordinate): Boolean {
        if (dependency.ecosystem != Ecosystem.GRADLE) {
            return true
        }
        return dependency.displayRange.startOffset != 0 || dependency.displayRange.endOffset != 0
    }

    private fun cacheKey(dependency: DependencyCoordinate, repositories: List<RepositorySpec>): String {
        return "${dependency.key}:${latestVersionPolicy.name}:${repositories.joinToString(",") { it.url }}"
    }

    private fun repositoriesForLookup(dependency: DependencyCoordinate, repositories: List<RepositorySpec>): List<RepositorySpec> {
        if (dependency.scope !in PLUGIN_SCOPES || dependency.ecosystem !in setOf(Ecosystem.MAVEN, Ecosystem.GRADLE)) {
            return repositories
        }
        val pluginRepositories = repositories.mapNotNull { repository ->
            repository.pluginUrl?.let { pluginUrl ->
                repository.copy(url = pluginUrl, supportsSearch = repository.supportsSearch || pluginUrl.supportsMavenSearch())
            }
        }
        if (pluginRepositories.isNotEmpty()) {
            return pluginRepositories
        }
        if (dependency.ecosystem == Ecosystem.GRADLE) {
            return listOf(
                RepositorySpec(
                    ecosystem = Ecosystem.GRADLE,
                    url = GRADLE_PLUGIN_PORTAL_REPOSITORY,
                    source = "Gradle Plugin Portal",
                    supportsSearch = true,
                ),
            )
        }
        return repositories
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
        val currentDependencies = readAction {
            withEditorLocations(dependency.file, scanFileInternal(dependency.file, includeMavenPlugins = true))
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
        return DependencyDeclarationRewriter.replaceVersionInDeclaration(
            dependency = dependency,
            declaration = declaration,
            newVersion = newVersion,
        )
    }

    private fun scanFileInternal(file: VirtualFile, includeMavenPlugins: Boolean = false): List<DependencyCoordinate> {
        if (includeMavenPlugins && DependencyFiles.isMavenPom(file)) {
            return externalSystems.enrich(
                file,
                MavenDeclarationCollector.collect(project, file, includePlugins = true),
            )
        }
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

private val PLUGIN_SCOPES = setOf(
    MavenDeclarationCollector.PLUGIN_SCOPE,
    MavenDeclarationCollector.PLUGIN_MANAGEMENT_SCOPE,
)

private const val GRADLE_PLUGIN_PORTAL_REPOSITORY = "https://plugins.gradle.org/m2/"

private fun String.supportsMavenSearch(): Boolean =
    contains("search.maven.org") ||
        contains("repo1.maven.org") ||
        contains("repo.maven.apache.org") ||
        contains("nexus", ignoreCase = true) ||
        contains("artifactory", ignoreCase = true)

internal fun npmUpgradedVersionValue(oldVersion: String, newVersion: String): String? =
    org.knifefish.dependency.helper.services.ecosystem.npmUpgradedVersionValue(oldVersion, newVersion)

internal fun hasRecommendedUpgrade(dependency: DependencyCoordinate, latestStable: String?): Boolean =
    org.knifefish.dependency.helper.services.ecosystem.hasRecommendedUpgrade(dependency, latestStable)
