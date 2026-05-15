package org.knifefish.dependency.helper.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.model.MavenDomDependency
import org.jetbrains.idea.maven.model.MavenArtifact
import org.jetbrains.idea.maven.model.MavenArtifactNode
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.Ecosystem
import org.knifefish.dependency.helper.model.MavenDependencyNodeView
import org.knifefish.dependency.helper.model.TextRangeMarker
import org.knifefish.dependency.helper.util.readAction

class MavenDependencyAnalyzer(private val project: Project) : MavenSupport {

    private fun mavenProjectsManager(): MavenProjectsManager = MavenProjectsManager.getInstance(project)

    override fun enrichDependencies(file: VirtualFile, dependencies: List<DependencyCoordinate>): List<DependencyCoordinate> {
        if (file.name != "pom.xml") {
            return dependencies
        }
        return readAction {
            val mavenProject = mavenProjectsManager().findProject(file) ?: return@readAction dependencies
            dependencies.map { dependency ->
                if (dependency.ecosystem != Ecosystem.MAVEN || dependency.version.isNotBlank()) {
                    dependency
                } else {
                    val managedVersion = dependency.group?.let { mavenProject.findManagedDependencyVersion(it, dependency.name) }
                    if (managedVersion.isNullOrBlank()) dependency else dependency.copy(version = managedVersion, declaredVersion = null)
                }
            }
        }
    }

    override fun analyze(): List<MavenDependencyNodeView> {
        return readAction {
            mavenProjectsManager().projects.map { projectNode ->
                val directDependencies = directDependenciesByKey(projectNode)
                buildProjectNodes(projectNode, directDependencies)
            }
        }
    }

    override fun toDependencyCoordinate(view: MavenDependencyNodeView): DependencyCoordinate {
        return view.sourceDependency ?: DependencyCoordinate(
            ecosystem = Ecosystem.MAVEN,
            group = view.groupId,
            name = view.artifactId,
            version = view.version,
            declaredVersion = view.version,
            scope = view.scope,
            file = view.ownerProjectFile,
            declarationText = view.displayName,
            lineNumber = 1,
            versionRange = null,
        )
    }

    override fun jumpToSource(view: MavenDependencyNodeView): Boolean {
        val source = view.sourceDependency
        if (source != null) {
            return openFile(source.file, source.lineNumber)
        }
        return openParentPom(view) || openReactorPom(view)
    }

    override fun exclude(view: MavenDependencyNodeView): Boolean {
        if (view.path.size < 3) {
            return false
        }
        val directDependencyGa = view.path[1]
        val directGroupId = directDependencyGa.substringBefore(':')
        val directArtifactId = directDependencyGa.substringAfter(':')
        val state = readAction {
            val xmlFile = PsiManager.getInstance(project).findFile(view.ownerProjectFile) as? XmlFile ?: return@readAction null
            val dependencyTag = findDependencyTag(xmlFile, directGroupId, directArtifactId) ?: return@readAction null
            val exclusionsTag = dependencyTag.findFirstSubTag("exclusions")
            val exists = exclusionsTag?.findSubTags("exclusion")?.any { exclusion ->
                exclusion.findFirstSubTag("groupId")?.value?.text == view.groupId &&
                    exclusion.findFirstSubTag("artifactId")?.value?.text == view.artifactId
            } == true
            ExcludeState(dependencyTag, exclusionsTag, exists)
        } ?: return false
        if (state.exists) {
            return true
        }

        WriteCommandAction.runWriteCommandAction(project, Runnable {
            val targetExclusions = state.exclusionsTag ?: state.dependencyTag.addSubTag(createTag("<exclusions/>"), false)
            targetExclusions.addSubTag(
                createTag(
                    """
                    <exclusion>
                      <groupId>${view.groupId}</groupId>
                      <artifactId>${view.artifactId}</artifactId>
                    </exclusion>
                    """.trimIndent(),
                ),
                false,
            )
            FileDocumentManager.getInstance().getDocument(view.ownerProjectFile)?.let(FileDocumentManager.getInstance()::saveDocument)
        })
        refreshMavenProject(view.ownerProjectFile)
        return true
    }

    fun insertManagedVersion(dependency: DependencyCoordinate, newVersion: String): Boolean {
        val state = readAction {
            val psiFile = PsiManager.getInstance(project).findFile(dependency.file) as? XmlFile ?: return@readAction null
            val dependencyTag = findDependencyTag(psiFile, dependency.group ?: return@readAction null, dependency.name) ?: return@readAction null
            ManagedDependencyEditState(dependencyTag, dependencyTag.findFirstSubTag("version"))
        } ?: return false
        WriteCommandAction.runWriteCommandAction(project, Runnable {
            val newTag = createTag("<version>$newVersion</version>")
            if (state.versionTag != null) {
                state.versionTag.replace(newTag)
            } else {
                state.dependencyTag.addSubTag(newTag, false)
            }
            FileDocumentManager.getInstance().getDocument(dependency.file)?.let(FileDocumentManager.getInstance()::saveDocument)
        })
        refreshMavenProject(dependency.file)
        return true
    }

    override fun upgradeManagedDependency(dependency: DependencyCoordinate, latestVersion: String): Boolean {
        return executeManagedUpgradeTarget(
            discoverManagedUpgradeTargets(dependency).firstOrNull { it.kind == ManagedUpgradeTargetKind.CURRENT }
                ?: return insertManagedVersion(dependency, latestVersion),
            latestVersion,
        )
    }

    override fun resolveManagedUpgradeOptions(dependency: DependencyCoordinate): List<ManagedUpgradeOption> {
        val repositories = project.dependencyInsightService().repositoriesFor(Ecosystem.MAVEN)
        return discoverManagedUpgradeTargets(dependency).mapNotNull { target ->
            val latest = when (target.kind) {
                ManagedUpgradeTargetKind.CURRENT -> project.dependencyInsightService().lookupLatestVersion(dependency, repositories).latestStable
                ManagedUpgradeTargetKind.PARENT, ManagedUpgradeTargetKind.BOM -> {
                    val targetDependency = DependencyCoordinate(
                        ecosystem = Ecosystem.MAVEN,
                        group = target.groupId,
                        name = target.artifactId.orEmpty(),
                        version = target.currentVersion.orEmpty(),
                        declaredVersion = target.currentVersion,
                        scope = null,
                        file = target.file,
                        declarationText = "",
                        lineNumber = 1,
                        versionRange = null,
                    )
                    project.dependencyInsightService().lookupLatestVersion(targetDependency, repositories).latestStable
                }
            }
            if (latest.isNullOrBlank() || latest == target.currentVersion) {
                null
            } else {
                ManagedUpgradeOption(target, latest)
            }
        }
    }

    fun discoverManagedUpgradeTargets(dependency: DependencyCoordinate): List<ManagedUpgradeTarget> {
        if (dependency.ecosystem != Ecosystem.MAVEN) {
            return emptyList()
        }
        return readAction {
            val baseTarget = ManagedUpgradeTarget(
                id = "current",
                kind = ManagedUpgradeTargetKind.CURRENT,
                label = "Upgrade current dependency",
                file = dependency.file,
                groupId = dependency.group,
                artifactId = dependency.name,
            )
            val chain = workspaceInheritanceChain(dependency.file)
            val descriptors = chain.mapNotNull { mavenProject ->
                readProjectDescriptor(mavenProject.file)?.let { descriptor ->
                    ManagedProjectDescriptor(mavenProject.file, descriptor)
                }
            }
            listOf(baseTarget) + collectManagedUpgradeTargets(
                dependency = dependency,
                descriptors = descriptors,
                parentRecursivelyManages = ::parentRecursivelyManagesDependency,
                bomRecursivelyManages = ::bomRecursivelyManagesDependency,
                workspaceParentDelegates = ::workspaceParentDelegatesToMoreDirectReference,
            )
        }
    }

    override fun executeManagedUpgradeTarget(target: ManagedUpgradeTarget, latestVersion: String): Boolean {
        return when (target.kind) {
            ManagedUpgradeTargetKind.CURRENT -> insertManagedVersion(
                DependencyCoordinate(
                    ecosystem = Ecosystem.MAVEN,
                    group = target.groupId,
                    name = target.artifactId.orEmpty(),
                    version = "",
                    declaredVersion = null,
                    scope = null,
                    file = target.file,
                    declarationText = "",
                    lineNumber = 1,
                    versionRange = null,
                ),
                latestVersion,
            )
            ManagedUpgradeTargetKind.PARENT -> updateParentVersion(target.file, target.groupId, target.artifactId, latestVersion)
            ManagedUpgradeTargetKind.BOM -> updateBomVersion(target.file, target.groupId, target.artifactId, latestVersion)
        }
    }

    private fun buildProjectNodes(
        mavenProject: MavenProject,
        directDependencies: Map<String, DependencyCoordinate>,
    ): MavenDependencyNodeView {
        val rootArtifact = mavenProject.mavenId
        val root = MavenDependencyNodeView(
            ownerProjectName = mavenProject.displayName,
            ownerProjectFile = mavenProject.file,
            groupId = rootArtifact.groupId ?: "",
            artifactId = rootArtifact.artifactId ?: "",
            version = rootArtifact.version ?: "",
            scope = "project",
            packaging = mavenProject.packaging,
            path = listOf("${rootArtifact.groupId ?: ""}:${rootArtifact.artifactId ?: ""}"),
            sourceDependency = null,
        )
        mavenProject.dependencyTree.forEach { child ->
            root.children += buildNode(mavenProject, child, root.path, directDependencies)
        }
        return root
    }

    private fun buildNode(
        ownerProject: MavenProject,
        node: MavenArtifactNode,
        parentPath: List<String>,
        directDependencies: Map<String, DependencyCoordinate>,
    ): MavenDependencyNodeView {
        val artifact = node.artifact
        val key = "${artifact.groupId}:${artifact.artifactId}"
        val view = MavenDependencyNodeView(
            ownerProjectName = ownerProject.displayName,
            ownerProjectFile = ownerProject.file,
            groupId = artifact.groupId,
            artifactId = artifact.artifactId,
            version = artifact.version,
            scope = node.originalScope ?: artifact.scope,
            packaging = artifact.packaging,
            path = parentPath + key,
            sourceDependency = if (parentPath.size == 1) directDependencies[key] else null,
        )
        node.dependencies.forEach { child ->
            view.children += buildNode(ownerProject, child, view.path, directDependencies)
        }
        return view
    }

    private fun directDependenciesByKey(mavenProject: MavenProject): Map<String, DependencyCoordinate> {
        return declaredDependenciesFromMavenModel(mavenProject.file)
            .let { enrichDependencies(mavenProject.file, it) }
            .associateBy { "${it.group}:${it.name}" }
    }

    private fun declaredDependenciesFromMavenModel(file: VirtualFile): List<DependencyCoordinate> {
        val model = MavenDomUtil.getMavenDomProjectModel(project, file) ?: return emptyList()
        val source = model.xmlTag?.containingFile?.text.orEmpty()
        return model.dependencies.dependencies.mapNotNull { dependency ->
            dependency.toCoordinate(file, source)
        }
    }

    private fun MavenDomDependency.toCoordinate(file: VirtualFile, source: String): DependencyCoordinate? {
        val group = getGroupId().stringValue?.trim()
        val artifact = getArtifactId().stringValue?.trim()
        if (group.isNullOrBlank() || artifact.isNullOrBlank()) {
            return null
        }

        val declaredVersion = getVersion().stringValue?.trim()
        val versionRange = getVersion().xmlTag?.value?.textRange?.let { TextRangeMarker(it.startOffset, it.endOffset) }
        val fallbackOffset = getArtifactId().xmlTag?.value?.textRange?.endOffset ?: xmlTag?.textRange?.endOffset ?: 0
        val displayRange = versionRange ?: TextRangeMarker(fallbackOffset, fallbackOffset)
        val declarationRange = xmlTag?.textRange?.let { TextRangeMarker(it.startOffset, it.endOffset) } ?: displayRange
        return DependencyCoordinate(
            ecosystem = Ecosystem.MAVEN,
            group = group,
            name = artifact,
            version = declaredVersion.orEmpty(),
            declaredVersion = declaredVersion,
            scope = getScope().stringValue?.trim(),
            file = file,
            declarationText = xmlTag?.text?.trim().orEmpty(),
            lineNumber = source.take(displayRange.startOffset).count { it == '\n' } + 1,
            versionRange = versionRange,
            displayRange = displayRange,
            inspectionRange = declarationRange,
        )
    }

    private fun findDependencyTag(xmlFile: XmlFile, groupId: String, artifactId: String): XmlTag? {
        val dependenciesSections = buildList {
            xmlFile.rootTag?.findFirstSubTag("dependencies")?.let(::add)
            xmlFile.rootTag?.findFirstSubTag("dependencyManagement")?.findFirstSubTag("dependencies")?.let(::add)
        }
        return dependenciesSections.asSequence()
            .flatMap { it.findSubTags("dependency").asSequence() }
            .firstOrNull { tag ->
                tag.findFirstSubTag("groupId")?.value?.text == groupId &&
                    tag.findFirstSubTag("artifactId")?.value?.text == artifactId
            }
    }

    private fun createTag(text: String): XmlTag {
        val tempFile = PsiManager.getInstance(project).findFile(LightVirtualFile("dependency-helper.xml", "<root>$text</root>")) as XmlFile
        return tempFile.rootTag!!.subTags.first()
    }

    private fun updateParentVersion(file: VirtualFile, groupId: String?, artifactId: String?, newVersion: String): Boolean {
        val versionTag = readAction {
            val model = MavenDomUtil.getMavenDomProjectModel(project, file) ?: return@readAction null
            val parent = model.mavenParent
            if (parent.groupId.stringValue != groupId || parent.artifactId.stringValue != artifactId) {
                return@readAction null
            }
            parent.version.xmlTag
        } ?: return false
        return replaceTagValue(file, versionTag, newVersion)
    }

    private fun updateBomVersion(file: VirtualFile, groupId: String?, artifactId: String?, newVersion: String): Boolean {
        val versionTag = readAction {
            val model = MavenDomUtil.getMavenDomProjectModel(project, file) ?: return@readAction null
            model.dependencyManagement?.dependencies?.dependencies.orEmpty()
                .firstOrNull {
                    it.groupId.stringValue == groupId &&
                        it.artifactId.stringValue == artifactId &&
                        it.scope.stringValue == "import" &&
                        it.type.stringValue == "pom"
                }?.version?.xmlTag
        } ?: return false
        return replaceTagValue(file, versionTag, newVersion)
    }

    private fun openFile(file: VirtualFile, lineNumber: Int): Boolean {
        ApplicationManager.getApplication().invokeLater {
            OpenFileDescriptor(project, file, maxOf(lineNumber - 1, 0), 0).navigate(true)
        }
        return true
    }

    private fun openParentPom(view: MavenDependencyNodeView): Boolean {
        if (view.path.size < 3) {
            return false
        }
        val parentGa = view.path[view.path.size - 2]
        val parentGroupId = parentGa.substringBefore(':')
        val parentArtifactId = parentGa.substringAfter(':')
        val parentNode = analyze().asSequence().flatMap { flatten(it).asSequence() }
            .firstOrNull { it.groupId == parentGroupId && it.artifactId == parentArtifactId } ?: return false
        val reactorPom = readAction {
            mavenProjectsManager().findProject(
                MavenArtifact(parentGroupId, parentArtifactId, parentNode.version, "jar", null, parentNode.scope ?: "compile", null, false, "jar", null, null, true, false),
            )?.file
        }
        if (reactorPom != null) {
            return openFile(reactorPom, 1)
        }
        val pomPath = mavenProjectsManager().repositoryPath
            .resolve(parentGroupId.replace('.', '/'))
            .resolve(parentArtifactId)
            .resolve(parentNode.version)
            .resolve("$parentArtifactId-${parentNode.version}.pom")
        val pomFile = LocalFileSystem.getInstance().findFileByNioFile(pomPath) ?: return false
        return openFile(pomFile, 1)
    }

    private fun openReactorPom(view: MavenDependencyNodeView): Boolean {
        val reactorProject = readAction {
            mavenProjectsManager().findProject(
                MavenArtifact(view.groupId, view.artifactId, view.version, "jar", null, view.scope ?: "compile", null, false, "jar", null, null, true, false),
            )
        } ?: return false
        return openFile(reactorProject.file, 1)
    }

    private fun flatten(root: MavenDependencyNodeView): List<MavenDependencyNodeView> {
        val result = mutableListOf<MavenDependencyNodeView>()
        fun visit(node: MavenDependencyNodeView) {
            result += node
            node.children.forEach(::visit)
        }
        visit(root)
        return result
    }

    private fun replaceTagValue(file: VirtualFile, versionTag: XmlTag, newVersion: String): Boolean {
        val targetTag = readAction {
            resolveVersionWriteTag(file, versionTag)
        } ?: return false
        WriteCommandAction.runWriteCommandAction(project, Runnable {
            targetTag.value.text = newVersion
            targetTag.containingFile.virtualFile?.let { targetFile ->
                FileDocumentManager.getInstance().getDocument(targetFile)?.let(FileDocumentManager.getInstance()::saveDocument)
                refreshMavenProject(targetFile)
            }
        })
        return true
    }

    private fun resolveVersionWriteTag(file: VirtualFile, versionTag: XmlTag): XmlTag {
        val rawText = versionTag.value.text.trim()
        val propertyName = extractPropertyReference(rawText) ?: return versionTag
        return findPropertyTag(file, propertyName, mutableSetOf()) ?: versionTag
    }

    private fun workspaceInheritanceChain(startFile: VirtualFile): List<MavenProject> {
        val startProject = mavenProjectsManager().findProject(startFile) ?: return emptyList()
        val result = mutableListOf<MavenProject>()
        val seen = mutableSetOf<String>()
        var current: MavenProject? = startProject
        while (current != null && seen.add(current.file.path)) {
            result += current
            val descriptor = readProjectDescriptor(current.file) ?: break
            val parent = descriptor.parent ?: break
            current = findWorkspaceProject(parent.groupId, parent.artifactId, parent.version)
        }
        return result
    }

    private fun findWorkspaceProject(groupId: String, artifactId: String, version: String): MavenProject? {
        return mavenProjectsManager().findProject(
            MavenArtifact(groupId, artifactId, version, "pom", null, "compile", null, false, "pom", null, null, true, false),
        )
    }

    private fun readProjectDescriptor(file: VirtualFile): ProjectDescriptor? {
        val xmlFile = PsiManager.getInstance(project).findFile(file) as? XmlFile ?: return null
        return parseProjectDescriptor(xmlFile)
    }

    private fun readProjectDescriptor(xmlFile: XmlFile): ProjectDescriptor? {
        return parseProjectDescriptor(xmlFile)
    }

    private fun parentRecursivelyManagesDependency(parent: PomReference, dependency: DependencyCoordinate): Boolean {
        return pomRecursivelyManagesDependency(resolvePomReference(parent), dependency, mutableSetOf())
    }

    private fun bomRecursivelyManagesDependency(bom: PomReference, dependency: DependencyCoordinate): Boolean {
        return pomRecursivelyManagesDependency(resolvePomReference(bom), dependency, mutableSetOf())
    }

    private fun bomDirectlyManagesDependency(bom: PomReference, dependency: DependencyCoordinate): Boolean {
        val xmlFile = resolvePomReference(bom) ?: return false
        return pomDirectlyManagesDependency(xmlFile, dependency)
    }

    private fun workspaceParentDelegatesToMoreDirectReference(
        parent: PomReference,
        dependency: DependencyCoordinate,
    ): Boolean {
        val parentXml = resolvePomReference(parent) ?: return false
        val workspaceProject = parentXml.virtualFile?.let(mavenProjectsManager()::findProject) ?: return false
        val descriptor = readProjectDescriptor(workspaceProject.file) ?: return false
        return descriptor.importedBoms.any { bom -> bomRecursivelyManagesDependency(bom, dependency) } ||
            (descriptor.parent?.let { directParent -> parentRecursivelyManagesDependency(directParent, dependency) } == true)
    }

    private fun pomRecursivelyManagesDependency(
        xmlFile: XmlFile?,
        dependency: DependencyCoordinate,
        seen: MutableSet<String>,
    ): Boolean {
        val file = xmlFile?.virtualFile ?: return false
        if (!seen.add(file.path)) {
            return false
        }
        if (pomDirectlyManagesDependency(xmlFile, dependency)) {
            return true
        }
        val descriptor = readProjectDescriptor(xmlFile) ?: return false
        if (descriptor.importedBoms.any { bom ->
                pomRecursivelyManagesDependency(resolvePomReference(bom), dependency, seen)
            }
        ) {
            return true
        }
        val parent = descriptor.parent ?: return false
        return pomRecursivelyManagesDependency(resolvePomReference(parent), dependency, seen)
    }

    private fun pomDirectlyManagesDependency(xmlFile: XmlFile, dependency: DependencyCoordinate): Boolean {
        val root = xmlFile.rootTag ?: return false
        return root.findFirstSubTag("dependencyManagement")
            ?.findFirstSubTag("dependencies")
            ?.findSubTags("dependency")
            ?.any { tag ->
                tag.findFirstSubTag("groupId")?.value?.text == dependency.group &&
                    tag.findFirstSubTag("artifactId")?.value?.text == dependency.name
            } == true
    }

    private fun resolvePomReference(reference: PomReference): XmlFile? {
        val workspaceProject = findWorkspaceProject(reference.groupId, reference.artifactId, reference.version)
        val workspaceFile = workspaceProject?.file
        val resolvedFile = workspaceFile ?: resolveLocalPom(reference)
        return resolvedFile?.let { PsiManager.getInstance(project).findFile(it) as? XmlFile }
    }

    private fun findPropertyTag(
        file: VirtualFile,
        propertyName: String,
        seen: MutableSet<String>,
    ): XmlTag? {
        if (!seen.add(file.path)) {
            return null
        }
        val xmlFile = PsiManager.getInstance(project).findFile(file) as? XmlFile ?: return null
        xmlFile.rootTag
            ?.findFirstSubTag("properties")
            ?.subTags
            ?.firstOrNull { tag -> (tag.localName ?: tag.name) == propertyName }
            ?.let { return it }
        val descriptor = readProjectDescriptor(xmlFile) ?: return null
        val parent = descriptor.parent ?: return null
        val parentFile = resolvePomReference(parent)?.virtualFile ?: return null
        return findPropertyTag(parentFile, propertyName, seen)
    }

    private fun resolveLocalPom(reference: PomReference): VirtualFile? {
        val pomPath = mavenProjectsManager().repositoryPath
            .resolve(reference.groupId.replace('.', '/'))
            .resolve(reference.artifactId)
            .resolve(reference.version)
            .resolve("${reference.artifactId}-${reference.version}.pom")
        return LocalFileSystem.getInstance().findFileByNioFile(pomPath)
    }

    override fun refreshMavenProject(file: VirtualFile, afterRefresh: (() -> Unit)?) {
        val mavenProject = readAction { mavenProjectsManager().findProject(file) } ?: run {
            afterRefresh?.invoke()
            return
        }
        if (afterRefresh != null) {
            val disposable = Disposer.newDisposable("dependency-helper-maven-refresh")
            mavenProjectsManager().addManagerListener(object : MavenProjectsManager.Listener {
                override fun projectImportCompleted() {
                    Disposer.dispose(disposable)
                    ApplicationManager.getApplication().invokeLater {
                        afterRefresh.invoke()
                    }
                }
            }, disposable)
        }
        mavenProjectsManager().forceUpdateAllProjectsOrFindAllAvailablePomFiles()
    }

    private data class ExcludeState(
        val dependencyTag: XmlTag,
        val exclusionsTag: XmlTag?,
        val exists: Boolean,
    )

    private data class ManagedDependencyEditState(
        val dependencyTag: XmlTag,
        val versionTag: XmlTag?,
    )
}

private fun extractPropertyReference(text: String): String? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("\${") || !trimmed.endsWith("}")) {
        return null
    }
    val inner = trimmed.substring(2, trimmed.length - 1).trim()
    return inner.takeIf { it.isNotEmpty() && it.none { ch -> ch.isWhitespace() } }
}

internal fun parseProjectDescriptor(xmlFile: XmlFile): ProjectDescriptor? {
    val root = xmlFile.rootTag ?: return null
    val rawParentTag = root.findFirstSubTag("parent")
    val rawParent = rawParentTag?.let { tag ->
        val groupId = tag.findFirstSubTag("groupId")?.value?.text
        val artifactId = tag.findFirstSubTag("artifactId")?.value?.text
        val version = tag.findFirstSubTag("version")?.value?.text
        if (groupId.isNullOrBlank() || artifactId.isNullOrBlank() || version.isNullOrBlank()) null
        else PomReference(groupId, artifactId, version)
    }
    val resolver = PomPropertyResolver.from(root, rawParent)
    val parent = rawParentTag?.let { tag ->
        val groupId = resolver.resolve(tag.findFirstSubTag("groupId")?.value?.text)
        val artifactId = resolver.resolve(tag.findFirstSubTag("artifactId")?.value?.text)
        val version = resolver.resolve(tag.findFirstSubTag("version")?.value?.text)
        if (groupId.isNullOrBlank() || artifactId.isNullOrBlank() || version.isNullOrBlank()) null
        else PomReference(groupId, artifactId, version)
    }
    val importedBoms = root.findFirstSubTag("dependencyManagement")
        ?.findFirstSubTag("dependencies")
        ?.findSubTags("dependency")
        ?.mapNotNull { dependencyTag ->
            val groupId = resolver.resolve(dependencyTag.findFirstSubTag("groupId")?.value?.text)
            val artifactId = resolver.resolve(dependencyTag.findFirstSubTag("artifactId")?.value?.text)
            val version = resolver.resolve(dependencyTag.findFirstSubTag("version")?.value?.text)
            val scope = resolver.resolve(dependencyTag.findFirstSubTag("scope")?.value?.text)
            val type = resolver.resolve(dependencyTag.findFirstSubTag("type")?.value?.text)
            if (groupId.isNullOrBlank() || artifactId.isNullOrBlank() || version.isNullOrBlank() || scope != "import" || type != "pom") {
                null
            } else {
                PomReference(groupId, artifactId, version)
            }
        }.orEmpty()
    return ProjectDescriptor(parent, importedBoms)
}

internal fun collectManagedUpgradeTargets(
    dependency: DependencyCoordinate,
    descriptors: List<ManagedProjectDescriptor>,
    parentRecursivelyManages: (PomReference, DependencyCoordinate) -> Boolean,
    bomRecursivelyManages: (PomReference, DependencyCoordinate) -> Boolean,
    workspaceParentDelegates: (PomReference, DependencyCoordinate) -> Boolean,
): List<ManagedUpgradeTarget> {
    val targets = mutableListOf<ManagedUpgradeTarget>()
    val seenParents = linkedSetOf<String>()
    val seenBoms = linkedSetOf<String>()
    descriptors.forEach { managedDescriptor ->
        managedDescriptor.descriptor.importedBoms
            .filter { bom -> bomRecursivelyManages(bom, dependency) }
            .forEach { bom ->
                val key = "${managedDescriptor.file.path}:${bom.groupId}:${bom.artifactId}"
                if (seenBoms.add(key)) {
                    targets += ManagedUpgradeTarget(
                        id = "bom:${targets.size + 1}",
                        kind = ManagedUpgradeTargetKind.BOM,
                        label = "Upgrade BOM (${bom.groupId}:${bom.artifactId})",
                        file = managedDescriptor.file,
                        groupId = bom.groupId,
                        artifactId = bom.artifactId,
                        currentVersion = bom.version,
                    )
                }
            }

        managedDescriptor.descriptor.parent?.takeIf { parent ->
            parentRecursivelyManages(parent, dependency) &&
                !workspaceParentDelegates(parent, dependency)
        }?.let { parent ->
            val key = "${managedDescriptor.file.path}:${parent.groupId}:${parent.artifactId}"
            if (seenParents.add(key)) {
                targets += ManagedUpgradeTarget(
                    id = "parent:${targets.size + 1}",
                    kind = ManagedUpgradeTargetKind.PARENT,
                    label = "Upgrade parent (${parent.groupId}:${parent.artifactId})",
                    file = managedDescriptor.file,
                    groupId = parent.groupId,
                    artifactId = parent.artifactId,
                    currentVersion = parent.version,
                )
            }
        }
    }
    return targets
}

internal data class ManagedProjectDescriptor(
    val file: VirtualFile,
    val descriptor: ProjectDescriptor,
)

internal data class ProjectDescriptor(
    val parent: PomReference?,
    val importedBoms: List<PomReference>,
)

internal data class PomReference(
    val groupId: String,
    val artifactId: String,
    val version: String,
)

internal class PomPropertyResolver private constructor(
    private val values: Map<String, String>,
) {

    fun resolve(value: String?): String? {
        if (value == null) {
            return null
        }
        var resolved: String = value
        repeat(8) {
            val updated = replaceProperties(resolved)
            if (updated == resolved) {
                return updated
            }
            resolved = updated
        }
        return resolved
    }

    private fun replaceProperties(input: String): String {
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val start = input.indexOf("\${", i)
            if (start < 0) {
                sb.append(input, i, input.length)
                break
            }
            sb.append(input, i, start)
            val end = input.indexOf('}', start + 2)
            if (end < 0) {
                sb.append(input.substring(start))
                break
            }
            val key = input.substring(start + 2, end).trim()
            val replacement = values[key]
            if (replacement != null) {
                sb.append(replacement)
            } else {
                sb.append(input, start, end + 1)
            }
            i = end + 1
        }
        return sb.toString()
    }

    companion object {
        fun from(root: XmlTag, rawParent: PomReference?): PomPropertyResolver {
            val values = linkedMapOf<String, String>()
            val rawGroupId = root.findFirstSubTag("groupId")?.value?.text?.trim().orEmpty()
            val rawArtifactId = root.findFirstSubTag("artifactId")?.value?.text?.trim().orEmpty()
            val rawVersion = root.findFirstSubTag("version")?.value?.text?.trim().orEmpty()
            val parentGroupId = rawParent?.groupId.orEmpty()
            val parentArtifactId = rawParent?.artifactId.orEmpty()
            val parentVersion = rawParent?.version.orEmpty()

            val projectGroupId = rawGroupId.ifBlank { parentGroupId }
            val projectVersion = rawVersion.ifBlank { parentVersion }
            values["project.groupId"] = projectGroupId
            values["project.artifactId"] = rawArtifactId
            values["project.version"] = projectVersion
            values["pom.groupId"] = projectGroupId
            values["pom.artifactId"] = rawArtifactId
            values["pom.version"] = projectVersion
            if (parentGroupId.isNotBlank()) values["parent.groupId"] = parentGroupId
            if (parentArtifactId.isNotBlank()) values["parent.artifactId"] = parentArtifactId
            if (parentVersion.isNotBlank()) values["parent.version"] = parentVersion
            root.findFirstSubTag("properties")
                ?.subTags
                ?.forEach { tag ->
                    val key = tag.localName ?: tag.name
                    val text = tag.value?.text?.trim().orEmpty()
                    if (key.isNotBlank() && text.isNotBlank()) {
                        values[key] = text
                    }
                }
            return PomPropertyResolver(values)
        }

    }
}

enum class ManagedUpgradeTargetKind {
    CURRENT,
    PARENT,
    BOM,
}

data class ManagedUpgradeTarget(
    val id: String,
    val kind: ManagedUpgradeTargetKind,
    val label: String,
    val file: VirtualFile,
    val groupId: String?,
    val artifactId: String?,
    val currentVersion: String? = null,
)

data class ManagedUpgradeOption(
    val target: ManagedUpgradeTarget,
    val latestVersion: String,
)
