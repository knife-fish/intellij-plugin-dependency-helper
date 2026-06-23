package org.knifefish.dependency.helper.documentation

import com.intellij.ide.DataManager
import com.intellij.lang.documentation.DocumentationProviderEx
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.*
import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.DependencyLookupResult
import org.knifefish.dependency.helper.model.LookupStatus
import org.knifefish.dependency.helper.model.VersionInfo
import org.knifefish.dependency.helper.repository.ProjectRepositoryResolver
import org.knifefish.dependency.helper.services.GradleSupport
import org.knifefish.dependency.helper.services.ManagedUpgradeOption
import org.knifefish.dependency.helper.services.MavenSupport
import org.knifefish.dependency.helper.services.dependencyInsightService
import org.knifefish.dependency.helper.toolWindow.DependencyUpgradeHtmlRenderer
import org.knifefish.dependency.helper.util.readAction
import java.awt.Point
import java.nio.file.Path
import java.nio.file.Paths

class DependencyDocumentationProvider : DocumentationProviderEx() {

    override fun getCustomDocumentationElement(
        editor: Editor,
        file: PsiFile,
        contextElement: PsiElement?,
        targetOffset: Int,
    ): PsiElement? {
        val lookup = editor.getUserData(EDITOR_LOOKUP_KEY)?.takeIf { it.contains(targetOffset) }
            ?: findLookupForOffset(editor, file, targetOffset)
            ?: return null
        editor.putUserData(EDITOR_LOOKUP_KEY, lookup)
        lookup.originalElement.putUserData(LOOKUP_RESULT_KEY, lookup)
        val range = lookup.dependency.inspectionRange
        if (targetOffset !in range.startOffset..range.endOffset && file.getUserData(FORCE_LOOKUP_KEY) != true) {
            return null
        }
        return DependencyDocumentationElement(lookup)
    }

    override fun generateHoverDoc(element: PsiElement, originalElement: PsiElement?): String? =
        generateDoc(element, originalElement)

    override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? {
        val result = element.getUserData(LOOKUP_RESULT_KEY)
            ?: originalElement?.getUserData(LOOKUP_RESULT_KEY)
            ?: buildLookupContext(element)
            ?: originalElement?.let { buildLookupContext(it) }
            ?: return null
        return DependencyUpgradeHtmlRenderer.documentationMarkup(
            dependency = result.dependency,
            versionInfo = result.versionInfo,
            latestRule = result.latestRule,
            managedOptions = result.managedOptions,
            result.metadataPsi?.virtualFile?.path ?: result.dependency.file.path
        )
    }

    override fun getDocumentationElementForLink(
        psiManager: PsiManager,
        link: String,
        context: PsiElement,
    ): PsiElement? {
        val normalizedLink = link.removePrefix("psi_element://")
        if (!normalizedLink.startsWith("dependency-helper-upgrade")) {
            return null
        }
        val lookup = context.getUserData(LOOKUP_RESULT_KEY)
            ?: context.containingFile?.getUserData(FILE_LOOKUP_KEY)?.takeIf { it.originalElement == context || it.contains(context.textOffset) }
            ?: buildLookupContext(context)
            ?: return context
        if (lookup.upgradeTriggered) {
            return context
        }
        lookup.upgradeTriggered = true
        val project = context.project
        invokeLater {
            executeUpgrade(project, lookup, normalizedLink)
        }
        return context
    }

    companion object {
        internal val LOOKUP_RESULT_KEY = Key.create<DocumentationLookupContext>("dependency.helper.documentation.lookup")
        internal val EDITOR_LOOKUP_KEY = Key.create<DocumentationLookupContext>("dependency.helper.documentation.editor.lookup")
        private val EDITOR_LOOKUPS_KEY = Key.create<List<EditorLookupResult>>("dependency.helper.documentation.editor.lookups")
        internal val FILE_LOOKUP_KEY = Key.create<DocumentationLookupContext>("dependency.helper.documentation.file.lookup")
        internal val FORCE_LOOKUP_KEY = Key.create<Boolean>("dependency.helper.documentation.file.force.lookup")

        fun setEditorLookups(
            editor: Editor,
            results: List<DependencyLookupResult>,
            latestRule: String,
            managedOptions: Map<DependencyCoordinate, List<ManagedUpgradeOption>> = emptyMap(),
        ) {
            editor.putUserData(
                EDITOR_LOOKUPS_KEY,
                results.map { EditorLookupResult(it, latestRule, managedOptions[it.dependency].orEmpty()) },
            )
        }

        fun clearEditorLookups(editor: Editor) {
            editor.putUserData(EDITOR_LOOKUPS_KEY, emptyList())
            editor.putUserData(EDITOR_LOOKUP_KEY, null)
        }

        fun showQuickDoc(editor: Editor, result: DependencyLookupResult, latestRule: String, point: Point? = null): Boolean {
            val project = editor.project ?: return false
            ApplicationManager.getApplication().executeOnPooledThread {
                val managedOptions = resolveManagedOptionsForDocumentation(project, result.dependency, cachedOnly = false)
                val context = readAction {
                    val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return@readAction null
                    buildLookupContext(project, psiFile, result, latestRule, managedOptions)
                } ?: return@executeOnPooledThread

                invokeLater {
                    val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return@invokeLater
                    editor.putUserData(EDITOR_LOOKUP_KEY, context)
                    psiFile.putUserData(FILE_LOOKUP_KEY, context)
                    psiFile.putUserData(FORCE_LOOKUP_KEY, true)
                    val anchorOffset = anchorOffset(editor, psiFile, result.dependency, point)
                        .coerceIn(0, psiFile.textLength.coerceAtLeast(1) - 1)
                    editor.caretModel.moveToOffset(anchorOffset)
                    val dataContext = point?.let {
                        DataManager.getInstance().getDataContext(editor.contentComponent, it.x, it.y)
                    } ?: DataManager.getInstance().getDataContext(editor.contentComponent)
                    val action = ActionManager.getInstance().getAction(IdeActions.ACTION_QUICK_JAVADOC) ?: return@invokeLater
                    val event = AnActionEvent.createEvent(
                        action,
                        dataContext,
                        action.templatePresentation.clone(),
                        ActionPlaces.UNKNOWN,
                        ActionUiKind.NONE,
                        null,
                    )
                    ActionUtil.performAction(action, event)
                    invokeLater { psiFile.putUserData(FORCE_LOOKUP_KEY, null) }
                }
            }
            return true
        }

        private fun findLookupForOffset(editor: Editor, psiFile: PsiFile, targetOffset: Int): DocumentationLookupContext? {
            val project = editor.project ?: psiFile.project
            val result = editor.getUserData(EDITOR_LOOKUPS_KEY)
                .orEmpty()
                .firstOrNull { it.result.dependency.contains(targetOffset) }
                ?: buildCachedLookupResult(project, psiFile, targetOffset)
                ?: return null
            return buildLookupContext(project, psiFile, result.result, result.latestRule, result.managedOptions)
        }

        private fun buildCachedLookupResult(
            project: com.intellij.openapi.project.Project,
            psiFile: PsiFile,
            targetOffset: Int,
        ): EditorLookupResult? {
            val file = psiFile.virtualFile ?: return null
            val service = project.dependencyInsightService()
            val dependency = service.dependencyAt(file, targetOffset) ?: return null
            val repositories = ProjectRepositoryResolver(project).resolveForProject()[dependency.ecosystem].orEmpty()
            val versionInfo = service.lookupLatestVersionIfCached(dependency, repositories)
                ?: VersionInfo(
                    latestStable = null,
                    latestAvailable = null,
                    repositoryUrl = null,
                    status = LookupStatus.NOT_FOUND,
                )
            return EditorLookupResult(
                result = DependencyLookupResult(dependency, versionInfo),
                latestRule = service.latestVersionPolicy().displayName,
                managedOptions = resolveManagedOptionsForDocumentation(project, dependency, cachedOnly = true),
            )
        }

        private fun buildLookupContext(element: PsiElement): DocumentationLookupContext? {
            val psiFile = element.containingFile ?: return null
            val offset = element.textRange?.startOffset?.takeIf { it >= 0 } ?: element.textOffset.takeIf { it >= 0 } ?: return null
            val result = buildCachedLookupResult(element.project, psiFile, offset) ?: return null
            return buildLookupContext(element.project, psiFile, result.result, result.latestRule, result.managedOptions)
        }

        private fun buildLookupContext(
            project: com.intellij.openapi.project.Project,
            psiFile: PsiFile,
            result: DependencyLookupResult,
            latestRule: String,
            precomputedManagedOptions: List<ManagedUpgradeOption> = emptyList(),
        ): DocumentationLookupContext {
            val originalElement = findOriginalElement(psiFile, result.dependency) ?: psiFile
            val metadataPsi = resolveMetadataPsi(project, result.dependency)
            val managedOptions = if (result.dependency.usesManagedVersion) {
                precomputedManagedOptions.ifEmpty {
                    resolveManagedOptionsForDocumentation(project, result.dependency, cachedOnly = true)
                }
            } else {
                emptyList()
            }
            return DocumentationLookupContext(result.dependency, result.versionInfo, latestRule, managedOptions, metadataPsi, originalElement).also {
                originalElement.putUserData(LOOKUP_RESULT_KEY, it)
                psiFile.putUserData(FILE_LOOKUP_KEY, it)
            }
        }

        private fun resolveManagedOptionsForDocumentation(
            project: com.intellij.openapi.project.Project,
            dependency: DependencyCoordinate,
            cachedOnly: Boolean,
        ): List<ManagedUpgradeOption> {
            if (!dependency.usesManagedVersion || dependency.ecosystem.name != "MAVEN") {
                return emptyList()
            }
            val mavenSupport = project.getService(MavenSupport::class.java) ?: return emptyList()
            return if (cachedOnly) {
                mavenSupport.resolveManagedUpgradeOptionsIfCached(dependency)
            } else {
                mavenSupport.resolveManagedUpgradeOptions(dependency)
            }
        }

        private fun anchorOffset(
            editor: Editor,
            psiFile: PsiFile,
            dependency: DependencyCoordinate,
            point: Point?,
        ): Int {
            if (point == null) {
                return dependency.versionRange?.startOffset ?: dependency.inspectionRange.startOffset
            }
            val logicalPosition: LogicalPosition = editor.xyToLogicalPosition(point)
            val clickOffset = editor.logicalPositionToOffset(logicalPosition)
            return clickOffset.coerceAtMost(psiFile.textLength.coerceAtLeast(1) - 1)
        }

        private fun executeUpgrade(
            project: com.intellij.openapi.project.Project,
            lookup: DocumentationLookupContext,
            normalizedLink: String,
        ) {
            val targetId = normalizedLink.substringAfter("dependency-helper-upgrade", "")
                .removePrefix(":")
                .ifBlank { "current" }
            val option = lookup.managedOptions.firstOrNull { it.target.id == targetId }
            if (option != null) {
                project.getService(MavenSupport::class.java)
                    ?.executeManagedUpgradeTarget(option.target, option.latestVersion)
            } else {
                val latest = lookup.versionInfo.latestStable ?: lookup.versionInfo.latestAvailable ?: return
                project.dependencyInsightService().upgradeDependency(lookup.dependency, latest)
            }
        }

        private fun findOriginalElement(psiFile: PsiFile, dependency: DependencyCoordinate): PsiElement? {
            val safeOffset = dependency.versionRange?.startOffset
                ?: dependency.inspectionRange.startOffset.coerceAtMost(psiFile.textLength - 1)
            return psiFile.findElementAt(safeOffset)
        }

        private fun resolveMetadataPsi(project: com.intellij.openapi.project.Project, dependency: DependencyCoordinate): PsiFileSystemItem? {
            val path = resolvedMetadataPath(project, dependency) ?: return null
            val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(path) ?: return null
            return PsiManager.getInstance(project).findFile(virtualFile) as? PsiFileSystemItem
        }

        private fun resolvedMetadataPath(project: com.intellij.openapi.project.Project, dependency: DependencyCoordinate): Path? {
            val group = dependency.group ?: return null
            if (dependency.version.isBlank()) {
                return null
            }
            return when (dependency.ecosystem.name) {
                "MAVEN" -> Paths.get(
                    org.jetbrains.idea.maven.project.MavenProjectsManager.getInstance(project).repositoryPath.toString(),
                    *group.split('.').toTypedArray(),
                    dependency.name,
                    dependency.version,
                    "${dependency.name}-${dependency.version}.pom",
                )
                "GRADLE" -> project.getService(GradleSupport::class.java)?.resolveMetadataPath(dependency)
                else -> null
            }
        }
    }

    internal class DocumentationLookupContext(
        val dependency: DependencyCoordinate,
        val versionInfo: org.knifefish.dependency.helper.model.VersionInfo,
        val latestRule: String,
        val managedOptions: List<ManagedUpgradeOption>,
        val metadataPsi: PsiFileSystemItem?,
        val originalElement: PsiElement,
    ) {
        @Volatile
        var upgradeTriggered: Boolean = false

        fun contains(offset: Int): Boolean = dependency.contains(offset)
    }

    private data class EditorLookupResult(
        val result: DependencyLookupResult,
        val latestRule: String,
        val managedOptions: List<ManagedUpgradeOption>,
    )
}

private fun DependencyCoordinate.contains(offset: Int): Boolean =
    versionRange?.let { offset in it.startOffset..it.endOffset } == true ||
        offset in inspectionRange.startOffset..inspectionRange.endOffset
