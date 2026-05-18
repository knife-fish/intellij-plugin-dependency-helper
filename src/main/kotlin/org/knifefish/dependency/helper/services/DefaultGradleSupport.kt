package org.knifefish.dependency.helper.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.project.dependencies.*
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.knifefish.dependency.helper.model.*
import org.knifefish.dependency.helper.util.readAction
import java.nio.file.Path
import java.nio.file.Paths

class DefaultGradleSupport(private val project: Project) : GradleSupport {

    override fun analyze(file: VirtualFile): List<MavenDependencyNodeView> {
        if (!isGradleFile(file)) {
            return emptyList()
        }
        val analysisFile = resolveAnalysisFile(file)
        val projectPath = findGradleProjectPath(analysisFile)
        val graphContext = findGradleDependencyGraphContext(projectPath)
        if (graphContext == null) {
            return buildDeclaredDependencyRoot(file, analysisFile)
        }
        val components = graphContext.dependencies.componentsDependencies
        if (components.isEmpty()) {
            return emptyList()
        }

        val projectName = file.name
        val rootPath = listOf(file.path)
        val root = MavenDependencyNodeView(
            ownerProjectName = projectName,
            ownerProjectFile = file,
            groupId = "",
            artifactId = projectName,
            version = "",
            scope = "file",
            packaging = null,
            path = rootPath,
            sourceDependency = null,
        )

        components.forEach { component ->
            root.children += buildComponentDependencyNodes(
                ownerFile = file,
                ownerProjectName = projectName,
                component = component,
                parentPath = rootPath,
            )
        }
        val deduplicatedRootChildren = deduplicateGradleNodes(root.children)
        root.children.run {
            clear()
            addAll(deduplicatedRootChildren)
        }
        logGradleTreeDiagnostics(file, root)
        return listOf(root)
    }

    private fun buildDeclaredDependencyRoot(
        file: VirtualFile,
        analysisFile: VirtualFile,
    ): List<MavenDependencyNodeView> {
        val (text, locations) = collectGradleEditorLocationsFromDocument(analysisFile) ?: return emptyList()
        val declaredLocations = locations
            .filter { it.scope in GRADLE_DEPENDENCY_SCOPES }
            .distinctBy { "${it.scope}:${it.group}:${it.name}:${it.version}:${it.displayRange.startOffset}" }
        if (declaredLocations.isEmpty()) {
            return emptyList()
        }

        val projectName = file.name
        val rootPath = listOf(file.path)
        val root = MavenDependencyNodeView(
            ownerProjectName = projectName,
            ownerProjectFile = file,
            groupId = "",
            artifactId = projectName,
            version = "",
            scope = "file",
            packaging = null,
            path = rootPath,
            sourceDependency = null,
        )
        declaredLocations.forEach { location ->
            val coordinate = location.toDependencyCoordinate(analysisFile, text)
            root.children += MavenDependencyNodeView(
                ownerProjectName = projectName,
                ownerProjectFile = analysisFile,
                groupId = location.group,
                artifactId = location.name,
                version = location.version,
                scope = location.scope,
                packaging = null,
                path = rootPath + location.pathSegment(),
                sourceDependency = coordinate,
            )
        }
        return listOf(root)
    }

    private fun buildComponentDependencyNodes(
        ownerFile: VirtualFile,
        ownerProjectName: String,
        component: ComponentDependencies,
        parentPath: List<String>,
    ): List<MavenDependencyNodeView> {
        return listOfNotNull(
            component.compileDependenciesGraph?.let { "compile" to it },
            component.runtimeDependenciesGraph?.let { "runtime" to it },
        ).flatMap { (scope, graph) ->
            val sourceNodesById = collectSourceNodesById(graph.dependencies)
            graph.dependencies.mapNotNull { dependency ->
                buildResolvedGradleNode(
                    ownerFile = ownerFile,
                    ownerProjectName = ownerProjectName,
                    node = dependency,
                    parentPath = parentPath,
                    directDependency = true,
                    inheritedScope = scope,
                    sourceNodesById = sourceNodesById,
                    visitingIds = emptySet(),
                )
            }
        }.let(::deduplicateGradleNodes)
    }

    private fun deduplicateGradleNodes(nodes: List<MavenDependencyNodeView>): MutableList<MavenDependencyNodeView> {
        val mergedByKey = linkedMapOf<String, MavenDependencyNodeView>()
        nodes.forEach { node ->
            val key = "${node.groupId}:${node.artifactId}:${node.version}"
            val existing = mergedByKey[key]
            if (existing == null) {
                mergedByKey[key] = node.copy(children = deduplicateGradleNodes(node.children))
            } else {
                val mergedScope = preferredScope(existing.scope, node.scope)
                if (mergedScope != existing.scope) {
                    mergedByKey[key] = existing.copy(scope = mergedScope, children = existing.children)
                }
                existing.children += node.children
                val mergedChildren = deduplicateGradleNodes(existing.children)
                existing.children.run {
                    clear()
                    addAll(mergedChildren)
                }
            }
        }
        return mergedByKey.values.toMutableList()
    }

    private fun preferredScope(left: String?, right: String?): String? {
        if (left == null) return right
        if (right == null) return left
        return if (scopePriority(left) <= scopePriority(right)) left else right
    }

    private fun scopePriority(scope: String): Int {
        val normalized = scope.lowercase()
        return when {
            normalized == "api" -> 0
            normalized == "implementation" || normalized == "compile" -> 1
            normalized.contains("compileonly") -> 2
            normalized.contains("runtime") -> 3
            normalized.contains("test") -> 4
            else -> 5
        }
    }

    private fun collectSourceNodesById(nodes: List<DependencyNode>): MutableMap<Long, DependencyNode> {
        val sourceNodesById = mutableMapOf<Long, DependencyNode>()
        val visiting = mutableSetOf<Long>()
        fun visit(node: DependencyNode) {
            if (!visiting.add(node.id)) {
                return
            }
            if (node !is ReferenceNode) {
                sourceNodesById.putIfAbsent(node.id, node)
            }
            node.dependencies.forEach(::visit)
            visiting.remove(node.id)
        }
        nodes.forEach(::visit)
        return sourceNodesById
    }

    override fun enrichDependencies(file: VirtualFile, dependencies: List<DependencyCoordinate>): List<DependencyCoordinate> {
        return dependencies
    }

    override fun attachEditorLocations(file: VirtualFile, dependencies: List<DependencyCoordinate>): List<DependencyCoordinate> {
        if (!isGradleFile(file) || dependencies.isEmpty()) {
            return dependencies
        }
        val (text, locations) = collectGradleEditorLocationsFromDocument(file) ?: return dependencies
        if (locations.isEmpty()) {
            return dependencies
        }
        val used = mutableSetOf<Int>()
        return dependencies.map { dependency ->
            if (dependency.ecosystem != Ecosystem.GRADLE || isLocated(dependency)) {
                dependency
            } else {
                findBestLocationMatch(dependency, locations, used, text)?.let { location ->
                    used += location.displayRange.startOffset
                    dependency.copy(
                        declaredVersion = location.declaredVersion,
                        declarationText = location.declarationText,
                        lineNumber = text.take(location.displayRange.startOffset).count { it == '\n' } + 1,
                        versionRange = location.versionRange,
                        displayRange = location.displayRange,
                        inspectionRange = location.inspectionRange,
                    )
                } ?: dependency
            }
        }
    }

    private fun findBestLocationMatch(
        dependency: DependencyCoordinate,
        locations: List<GradleEditorLocation>,
        used: Set<Int>,
        text: String,
    ): GradleEditorLocation? {
        val candidates = locations.filter { location ->
            location.displayRange.startOffset !in used &&
                location.group == dependency.group &&
                location.name == dependency.name
        }
        if (candidates.isEmpty()) {
            return null
        }
        return candidates.minWithOrNull(
            compareBy<GradleEditorLocation>(
                { location -> if (location.scope == dependency.scope) 0 else 1 },
                { location -> if (location.version == dependency.version) 0 else 1 },
                { location -> kotlin.math.abs(lineNumberAt(text, location.displayRange.startOffset) - dependency.lineNumber) },
                { it.displayRange.startOffset },
            ),
        )
    }

    private fun lineNumberAt(text: String, offset: Int): Int =
        text.take(offset.coerceAtLeast(0).coerceAtMost(text.length)).count { it == '\n' } + 1

    override fun declaredDependencies(file: VirtualFile): List<DependencyCoordinate> {
        if (!isGradleFile(file)) {
            return emptyList()
        }
        val resolvedDependencies = if (file.name in DependencyFileKind.GRADLE_BUILD.fileNames) {
            collectDeclaredDependencyCoordinates(file)
                .distinctBy { "${it.scope}:${it.group}:${it.name}:${it.version}" }
        } else {
            emptyList()
        }
        val pluginDependencies = declaredPluginDependencies(file)
        return (resolvedDependencies + pluginDependencies)
            .distinctBy { "${it.scope}:${it.group}:${it.name}:${it.version}:${it.displayRange.startOffset}" }
    }

    private fun collectDeclaredDependencyCoordinates(file: VirtualFile): List<DependencyCoordinate> {
        val (text, locations) = collectGradleEditorLocationsFromDocument(file) ?: return emptyList()
        return locations
            .filter { it.scope in GRADLE_DEPENDENCY_SCOPES }
            .map { it.toDependencyCoordinate(file, text) }
    }

    override fun upgradeDependency(dependency: DependencyCoordinate, newVersion: String): Boolean {
        resolveCatalogTarget(dependency.file, dependency)?.let { target ->
            return replaceCatalogVersion(target, newVersion)
        }
        val propertyName = dependency.declaredVersion?.let(::gradlePropertyName)
        if (!propertyName.isNullOrBlank()) {
            return replaceGradlePropertyValue(dependency.file, propertyName, newVersion)
        }
        if (dependency.versionRange == null) {
            return replaceManagedPluginVersion(dependency.file, dependency, newVersion)
        }
        if (dependency.scope in GRADLE_PLUGIN_SCOPES) {
            return replaceGradleRange(dependency.file, dependency.versionRange, newVersion)
        }
        return false
    }

    override fun refreshGradleProject(file: VirtualFile, afterRefresh: (() -> Unit)?) {
        val callback = afterRefresh ?: return
        if (!isGradleFile(file)) {
            ApplicationManager.getApplication().invokeLater(callback)
            return
        }

        val systemId = GRADLE_SYSTEM_ID
        if (ExternalSystemApiUtil.getManager(systemId) == null) {
            ApplicationManager.getApplication().invokeLater(callback)
            return
        }

        val projectPath = findGradleProjectPath(file) ?: run {
            ApplicationManager.getApplication().invokeLater(callback)
            return
        }

        val spec = ImportSpecBuilder(project, systemId)
            .use(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
            .withCallback(object : ExternalProjectRefreshCallback {
                override fun onSuccess(errorProject: com.intellij.openapi.externalSystem.model.DataNode<com.intellij.openapi.externalSystem.model.project.ProjectData>?) {
                    ApplicationManager.getApplication().invokeLater(callback)
                }

                override fun onFailure(errorMessage: String, errorDetails: String?) {
                    ApplicationManager.getApplication().invokeLater(callback)
                }
            })
            .build()
        ExternalSystemUtil.refreshProject(projectPath, spec)
    }

    override fun resolveMetadataPath(dependency: DependencyCoordinate): Path? = null

    private fun replaceGradlePropertyValue(file: VirtualFile, propertyName: String, newVersion: String): Boolean {
        val propertyFile = findGradlePropertiesFile(file)
        if (propertyFile != null) {
            val document = FileDocumentManager.getInstance().getDocument(propertyFile) ?: return false
            val valueRange = findGradlePropertyAssignmentRange(document.text, propertyName) ?: return false
            WriteCommandAction.runWriteCommandAction(project, Runnable {
                document.replaceString(valueRange.startOffset, valueRange.endOffset, newVersion)
                FileDocumentManager.getInstance().saveDocument(document)
            })
            refreshGradleProject(file)
            return true
        }

        val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
        val valueRange = findKotlinPropertyAssignmentRange(document.text, propertyName) ?: return false
        WriteCommandAction.runWriteCommandAction(project, Runnable {
            document.replaceString(valueRange.startOffset, valueRange.endOffset, newVersion)
            FileDocumentManager.getInstance().saveDocument(document)
        })
        refreshGradleProject(file)
        return true
    }

    private fun replaceManagedPluginVersion(file: VirtualFile, dependency: DependencyCoordinate, newVersion: String): Boolean {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
        if (dependency.group != "io.ktor" && dependency.group != "org.jetbrains.kotlin") {
            return false
        }

        val keys = mutableListOf("id(\"io.ktor.plugin\") version", "id('io.ktor.plugin') version")
        if (dependency.group == "org.jetbrains.kotlin") {
            keys += "kotlin(\""
            keys += "id(\"org.jetbrains.kotlin"
            keys += "id('org.jetbrains.kotlin"
        }
        var updatedAny = false
        WriteCommandAction.runWriteCommandAction(project, Runnable {
            val range = findManagedPluginVersionRange(document.text, dependency.group.orEmpty()) ?: return@Runnable
            document.replaceString(range.startOffset, range.endOffset, newVersion)
            updatedAny = true
            FileDocumentManager.getInstance().saveDocument(document)
        })
        if (updatedAny) {
            refreshGradleProject(file)
        }
        return updatedAny
    }

    private fun replaceGradleRange(file: VirtualFile, range: TextRangeMarker, newVersion: String): Boolean {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
        if (range.endOffset > document.textLength) {
            return false
        }
        WriteCommandAction.runWriteCommandAction(project, Runnable {
            if (range.endOffset > document.textLength) {
                return@Runnable
            }
            document.replaceString(range.startOffset, range.endOffset, newVersion)
            FileDocumentManager.getInstance().saveDocument(document)
        })
        refreshGradleProject(file)
        return true
    }

    private fun replaceCatalogVersion(target: GradleCatalogUpgradeTarget, newVersion: String): Boolean {
        val document = FileDocumentManager.getInstance().getDocument(target.file) ?: return false
        val text = document.text
        val updated = when (target) {
            is GradleCatalogUpgradeTarget.VersionRef -> {
                val range = findTomlSimpleValueRange(text, target.reference) ?: return false
                WriteCommandAction.runWriteCommandAction(project, Runnable {
                    document.replaceString(range.startOffset, range.endOffset, newVersion)
                    FileDocumentManager.getInstance().saveDocument(document)
                })
                true
            }
            is GradleCatalogUpgradeTarget.LibraryVersion -> {
                val lineRange = findTomlLineRange(text, target.alias) ?: return false
                val body = text.substring(lineRange.startOffset, lineRange.endOffset)
                val relative = findInlineTomlKeyValueRange(body, "version") ?: return false
                val start = lineRange.startOffset + relative.startOffset
                val end = lineRange.startOffset + relative.endOffset
                WriteCommandAction.runWriteCommandAction(project, Runnable {
                    document.replaceString(start, end, newVersion)
                    FileDocumentManager.getInstance().saveDocument(document)
                })
                true
            }
            is GradleCatalogUpgradeTarget.PluginVersion -> {
                val lineRange = findTomlLineRange(text, target.alias) ?: return false
                val body = text.substring(lineRange.startOffset, lineRange.endOffset)
                val relative = findInlineTomlKeyValueRange(body, "version") ?: return false
                val start = lineRange.startOffset + relative.startOffset
                val end = lineRange.startOffset + relative.endOffset
                WriteCommandAction.runWriteCommandAction(project, Runnable {
                    document.replaceString(start, end, newVersion)
                    FileDocumentManager.getInstance().saveDocument(document)
                })
                true
            }
            is GradleCatalogUpgradeTarget.RemoteCatalogVersion -> {
                val gav = target.coordinate.split(':')
                if (gav.size < 3 || !text.contains(target.coordinate)) {
                    return false
                }
                val replacement = "${gav[0]}:${gav[1]}:$newVersion"
                WriteCommandAction.runWriteCommandAction(project, Runnable {
                    document.setText(text.replaceFirst(target.coordinate, replacement))
                    FileDocumentManager.getInstance().saveDocument(document)
                })
                true
            }
        }
        if (updated) {
            refreshGradleProject(target.sourceFile)
        }
        return updated
    }

    private fun findManagedPluginVersionRange(text: String, dependencyGroup: String): TextRangeMarker? {
        fun rangeAfterVersionKeyword(start: Int): TextRangeMarker? {
            val versionIndex = text.indexOf("version", start)
            if (versionIndex < 0) return null
            val quote1 = text.indexOfAny(charArrayOf('"', '\''), versionIndex).takeIf { it >= 0 } ?: return null
            val valueStart = quote1 + 1
            val quote2 = text.indexOf(text[quote1], valueStart).takeIf { it > valueStart } ?: return null
            return TextRangeMarker(valueStart, quote2)
        }
        return when (dependencyGroup) {
            "io.ktor" -> {
                val idx = text.indexOf("id(\"io.ktor.plugin\")").takeIf { it >= 0 }
                    ?: text.indexOf("id('io.ktor.plugin')").takeIf { it >= 0 }
                    ?: return null
                rangeAfterVersionKeyword(idx)
            }
            "org.jetbrains.kotlin" -> {
                val kotlinIdx = text.indexOf("kotlin(\"").takeIf { it >= 0 }
                if (kotlinIdx != null) {
                    rangeAfterVersionKeyword(kotlinIdx)
                } else {
                    val idIdx = text.indexOf("id(\"org.jetbrains.kotlin").takeIf { it >= 0 }
                        ?: text.indexOf("id('org.jetbrains.kotlin").takeIf { it >= 0 }
                        ?: return null
                    rangeAfterVersionKeyword(idIdx)
                }
            }
            else -> null
        }
    }

    private fun isGradleFile(file: VirtualFile): Boolean = file.name in GRADLE_FILE_NAMES

    private fun findGradleProjectPath(file: VirtualFile): String? {
        var current: VirtualFile? = if (file.isDirectory) file else file.parent
        while (current != null) {
            if (current.children.any { it.name in GRADLE_SETTINGS_FILE_NAMES }) {
                return current.path
            }
            current = current.parent
        }
        return if (file.isDirectory) file.path else file.parent?.path
    }

    private fun findGradlePropertiesFile(file: VirtualFile): VirtualFile? {
        var current: VirtualFile? = if (file.isDirectory) file else file.parent
        while (current != null) {
            current.findChild("gradle.properties")?.let { return it }
            current = current.parent
        }
        return null
    }

    private fun findGradleSettingsFile(file: VirtualFile): VirtualFile? {
        var current: VirtualFile? = if (file.isDirectory) file else file.parent
        while (current != null) {
            DependencyFiles.findChild(current, DependencyFileKind.GRADLE_SETTINGS)?.let { return it }
            current = current.parent
        }
        return null
    }

    private fun loadVersionCatalogSources(file: VirtualFile): Map<String, GradleVersionCatalogSource> {
        val root = findGradleProjectRoot(file)
        val settingsFile = findGradleSettingsFile(file)
        val result = linkedMapOf<String, GradleVersionCatalogSource>()
        if (root != null) {
            root.findChild("gradle")?.findChild("libs.versions.toml")?.let { catalogFile ->
                val content = catalogFile.inputStream.bufferedReader().use { it.readText() }
                result["libs"] = GradleVersionCatalogSource("libs", catalogFile, content)
            }
        }
        if (settingsFile != null) {
            val settingsText = settingsFile.inputStream.bufferedReader().use { it.readText() }
            parseSettingsCatalogMappings(Paths.get(settingsFile.parent.path), settingsText).forEach { (name, catalogFileOrCoordinate) ->
                val catalogSource = resolveSettingsCatalogSource(settingsFile, name, catalogFileOrCoordinate) ?: return@forEach
                result[name] = catalogSource
            }
        }
        return result
    }

    private fun resolveSettingsCatalogSource(settingsFile: VirtualFile, name: String, source: String): GradleVersionCatalogSource? {
        val localPath = Paths.get(settingsFile.parent.path).resolve(source).normalize()
        if (source.endsWith(".toml")) {
            if (!java.nio.file.Files.exists(localPath)) {
                return null
            }
            val file = LocalFileSystem.getInstance().findFileByNioFile(localPath)
            val content = java.nio.file.Files.readString(localPath)
            return GradleVersionCatalogSource(name, file ?: settingsFile, content, editableFile = settingsFile.takeIf { file == null } ?: file)
        }
        val gav = source.trim().split(':')
        if (gav.size < 3) {
            return null
        }
        val group = gav[0]
        val artifact = gav[1]
        val version = gav[2]
        val fileName = "$artifact-$version.toml"
        val gradleHomes = candidateGradleHomes(settingsFile)
        thisLogger().info("DependencyHelper Gradle resolve remote catalog: $source, candidateGradleHomes=${gradleHomes.joinToString()}")
        gradleHomes.forEach { gradleHome ->
            val gradleCacheRoot = gradleHome.resolve("caches").resolve("modules-2").resolve("files-2.1")
            val gradleArtifactDir = gradleCacheRoot.resolve(group).resolve(artifact).resolve(version)
            runCatching {
                java.nio.file.Files.walk(gradleArtifactDir, 3).use { stream ->
                    stream
                        .filter { java.nio.file.Files.isRegularFile(it) && it.fileName.toString() == fileName }
                        .findFirst()
                        .orElse(null)
                }
            }.getOrNull()?.let {
                val file = LocalFileSystem.getInstance().findFileByNioFile(it)
                val content = java.nio.file.Files.readString(it)
                return GradleVersionCatalogSource(
                    name = name,
                    file = file ?: settingsFile,
                    text = content,
                    editableFile = settingsFile,
                    sourceCoordinate = source,
                )
            }
        }

        val mavenPath = Paths.get(System.getProperty("user.home"), ".m2", "repository")
            .resolve(group.replace('.', '/'))
            .resolve(artifact)
            .resolve(version)
            .resolve(fileName)
        if (!java.nio.file.Files.exists(mavenPath)) {
            return null
        }
        val file = LocalFileSystem.getInstance().findFileByNioFile(mavenPath)
        val content = java.nio.file.Files.readString(mavenPath)
        return GradleVersionCatalogSource(
            name = name,
            file = file ?: settingsFile,
            text = content,
            editableFile = settingsFile,
            sourceCoordinate = source,
        )
    }

    private fun candidateGradleHomes(settingsFile: VirtualFile): List<Path> {
        val result = linkedSetOf<Path>()
        System.getProperty("gradle.user.home")
            ?.takeIf { it.isNotBlank() }
            ?.let { result.add(Paths.get(it)) }
        System.getenv("GRADLE_USER_HOME")
            ?.takeIf { it.isNotBlank() }
            ?.let { result.add(Paths.get(it)) }
        result.add(Paths.get(System.getProperty("user.home"), ".gradle"))
        Paths.get(settingsFile.parent.path)
            .resolve(".gradle")
            .takeIf { java.nio.file.Files.exists(it) }
            ?.let { result.add(it) }
        return result.toList()
    }

    private fun buildResolvedGradleNode(
        ownerFile: VirtualFile,
        ownerProjectName: String,
        node: DependencyNode,
        inheritedScope: String,
        parentPath: List<String>,
        directDependency: Boolean,
        sourceNodesById: MutableMap<Long, DependencyNode>,
        visitingIds: Set<Long>,
        depth: Int = 0,
    ): MavenDependencyNodeView? {
        val currentVisitingIds = visitingIds + node.id
        if (node is ReferenceNode) {
            if (visitingIds.contains(node.id)) {
                return null
            }
            val referencedNode = sourceNodesById[node.id] ?: return null
            return buildResolvedGradleNode(
                ownerFile = ownerFile,
                ownerProjectName = ownerProjectName,
                node = referencedNode,
                inheritedScope = inheritedScope,
                parentPath = parentPath,
                directDependency = directDependency,
                sourceNodesById = sourceNodesById,
                visitingIds = currentVisitingIds,
                depth = depth,
            )
        }
        sourceNodesById[node.id] = node
        val view = when (node) {
            is ArtifactDependencyNode -> {
                MavenDependencyNodeView(
                    ownerProjectName = ownerProjectName,
                    ownerProjectFile = ownerFile,
                    groupId = node.group,
                    artifactId = node.module,
                    version = node.version,
                    scope = inheritedScope,
                    packaging = null,
                    path = parentPath + "${
                        if (node.group.isBlank()) node.module else "${node.group}:${node.module}"
                    }",
                    sourceDependency = if (directDependency) {
                        node.toDependencyCoordinate(ownerFile, inheritedScope)
                    } else null,
                )
            }
            is ProjectDependencyNode -> MavenDependencyNodeView(
                ownerProjectName = ownerProjectName,
                ownerProjectFile = ownerFile,
                groupId = "",
                artifactId = node.projectName,
                version = "",
                scope = inheritedScope,
                packaging = null,
                path = parentPath + node.projectName,
                sourceDependency = null,
            )
            else -> buildSyntheticGradleNode(ownerFile, ownerProjectName, node.displayName, inheritedScope, parentPath)
        }
        if (depth >= MAX_GRADLE_TREE_DEPTH) {
            return view
        }
        node.dependencies.forEach { child ->
            buildResolvedGradleNode(
                ownerFile = ownerFile,
                ownerProjectName = ownerProjectName,
                node = child,
                inheritedScope = inheritedScope,
                parentPath = view.path,
                directDependency = false,
                sourceNodesById = sourceNodesById,
                visitingIds = currentVisitingIds,
                depth = depth + 1,
            )?.let { view.children += it }
        }
        return view
    }

    private fun collectGradleEditorLocationsFromDocument(file: VirtualFile): Pair<String, List<GradleEditorLocation>>? {
        return readAction {
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@readAction null
            val text = document.text
            text to collectGradleEditorLocationsWithPsi(file, text)
        }
    }

    private fun collectGradleEditorLocationsWithPsi(file: VirtualFile, text: String): List<GradleEditorLocation> {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return emptyList()
        val catalogs = loadVersionCatalogSources(file).mapValues { (_, source) -> parseVersionCatalog(source.text) }
        val result = mutableListOf<GradleEditorLocation>()
        fun visit(element: PsiElement) {
            collectGradleStringLocationFromPsiElement(element, text)?.let(result::add)
            collectGradlePluginVersionFromPsiElement(element, text)?.let(result::add)
            collectGradleCatalogAccessorFromPsiElement(element, text, catalogs)?.forEach(result::add)
            element.children.forEach(::visit)
        }
        visit(psiFile)
        return result.distinctBy { "${it.group}:${it.name}:${it.version}:${it.scope}:${it.displayRange.startOffset}:${it.displayRange.endOffset}" }
    }

    private fun collectGradleStringLocationFromPsiElement(element: PsiElement, text: String): GradleEditorLocation? {
        val raw = element.text
        if (raw.length < 5 || !raw.isQuotedLiteral()) {
            return null
        }
        val value = raw.substring(1, raw.length - 1)
        if ('$' in value) {
            return null
        }
        val first = value.indexOf(':')
        val second = value.indexOf(':', first + 1)
        if (first <= 0 || second <= first + 1 || second >= value.lastIndex) {
            return null
        }
        val group = value.substring(0, first).trim()
        val name = value.substring(first + 1, second).trim()
        val version = value.substring(second + 1).trim()
        if (group.isBlank() || name.isBlank() || version.isBlank()) {
            return null
        }
        val scope = findGradleScopeByPsi(element, GRADLE_DEPENDENCY_SCOPES) ?: return null
        val inspectionRange = TextRangeMarker(element.textRange.startOffset, element.textRange.endOffset)
        val valueStart = element.textRange.startOffset + 1
        val versionStart = valueStart + second + 1
        val versionRange = TextRangeMarker(versionStart, versionStart + version.length)
        return GradleEditorLocation(
            group = group,
            name = name,
            version = version,
            scope = scope,
            declaredVersion = version,
            declarationText = text.substring(inspectionRange.startOffset, inspectionRange.endOffset),
            versionRange = versionRange,
            displayRange = versionRange,
            inspectionRange = inspectionRange,
        )
    }

    private fun collectGradlePluginVersionFromPsiElement(element: PsiElement, text: String): GradleEditorLocation? {
        val raw = element.text
        if (raw.length < 3 || !raw.isQuotedLiteral()) {
            return null
        }
        val pluginScope = findGradleScopeByPsi(element, setOf("plugins", "pluginManagement")) ?: return null
        val blockScope = if (pluginScope == "pluginManagement") "pluginManagement" else "plugin"
        val parentText = element.parent?.text ?: return null
        val pluginId = when {
            "id(" in parentText -> extractQuotedArgAfter(parentText, "id(")
            "kotlin(" in parentText -> extractQuotedArgAfter(parentText, "kotlin(")?.let(::kotlinPluginId)
            else -> null
        } ?: return null
        if (!parentText.contains("version")) {
            return null
        }
        val version = raw.substring(1, raw.length - 1).trim()
        if (version.isBlank() || '$' in version) {
            return null
        }
        val inspectionRange = TextRangeMarker(element.textRange.startOffset, element.textRange.endOffset)
        val versionRange = TextRangeMarker(element.textRange.startOffset + 1, element.textRange.endOffset - 1)
        return GradleEditorLocation(
            group = pluginId,
            name = gradlePluginMarkerArtifact(pluginId),
            version = version,
            scope = blockScope,
            declaredVersion = version,
            declarationText = text.substring(inspectionRange.startOffset, inspectionRange.endOffset),
            versionRange = versionRange,
            displayRange = inspectionRange,
            inspectionRange = inspectionRange,
        )
    }

    private fun collectGradleCatalogAccessorFromPsiElement(
        element: PsiElement,
        text: String,
        catalogs: Map<String, GradleVersionCatalog>,
    ): List<GradleEditorLocation>? {
        val accessor = element.text.trim().takeIf { it.isNotBlank() } ?: return null
        val catalogAccessor = gradleCatalogAccessor(accessor) ?: return null
        val catalog = catalogs[catalogAccessor.catalogName] ?: return null
        val inspectionRange = TextRangeMarker(element.textRange.startOffset, element.textRange.endOffset)
        val dependencyScope = findGradleScopeByPsi(element, GRADLE_DEPENDENCY_SCOPES)
        val pluginScope = findGradleScopeByPsi(element, setOf("plugins", "pluginManagement"))

        if (catalogAccessor.alias.startsWith("plugins.")) {
            val alias = catalogAccessor.alias.removePrefix("plugins.")
            val plugin = catalog.plugins[alias] ?: return null
            val version = plugin.version ?: plugin.versionRef?.let(catalog.versions::get) ?: return null
            val scope = if (pluginScope == "pluginManagement") "pluginManagement" else "plugin"
            return listOf(
                GradleEditorLocation(
                    group = plugin.id,
                    name = gradlePluginMarkerArtifact(plugin.id),
                    version = version,
                    scope = scope,
                    declaredVersion = accessor,
                    declarationText = text.substring(inspectionRange.startOffset, inspectionRange.endOffset),
                    versionRange = inspectionRange,
                    displayRange = inspectionRange,
                    inspectionRange = inspectionRange,
                ),
            )
        }

        val scope = dependencyScope ?: return null
        if (catalogAccessor.alias.startsWith("bundles.")) {
            val bundleAlias = catalogAccessor.alias.removePrefix("bundles.")
            val libraries = catalog.bundles[bundleAlias].orEmpty()
            return libraries.mapNotNull { libraryAlias ->
                val library = catalog.libraries[libraryAlias] ?: return@mapNotNull null
                val version = library.version ?: library.versionRef?.let(catalog.versions::get) ?: return@mapNotNull null
                GradleEditorLocation(
                    group = library.group,
                    name = library.name,
                    version = version,
                    scope = scope,
                    declaredVersion = accessor,
                    declarationText = text.substring(inspectionRange.startOffset, inspectionRange.endOffset),
                    versionRange = inspectionRange,
                    displayRange = inspectionRange,
                    inspectionRange = inspectionRange,
                )
            }.takeIf { it.isNotEmpty() }
        }

        val library = catalog.libraries[catalogAccessor.alias] ?: return null
        val version = library.version ?: library.versionRef?.let(catalog.versions::get) ?: return null
        return listOf(
            GradleEditorLocation(
                group = library.group,
                name = library.name,
                version = version,
                scope = scope,
                declaredVersion = accessor,
                declarationText = text.substring(inspectionRange.startOffset, inspectionRange.endOffset),
                versionRange = inspectionRange,
                displayRange = inspectionRange,
                inspectionRange = inspectionRange,
            ),
        )
    }

    private fun findGradleScopeByPsi(element: PsiElement, candidates: Set<String>): String? {
        var current: PsiElement? = element
        while (current != null) {
            val callName = extractCallNamePrefix(current.text)
            if (callName != null && callName in candidates) {
                return callName
            }
            current = current.parent
        }
        return null
    }

    private fun extractCallNamePrefix(text: String): String? {
        val paren = text.indexOf('(')
        if (paren <= 0) {
            return null
        }
        var i = paren - 1
        while (i >= 0 && (text[i].isLetterOrDigit() || text[i] == '_')) {
            i--
        }
        val name = text.substring(i + 1, paren).trim()
        return name.takeIf { it.isNotBlank() }
    }

    private fun extractQuotedArgAfter(text: String, prefix: String): String? {
        val startIndex = text.indexOf(prefix)
        if (startIndex < 0) {
            return null
        }
        val quoteStart = text.indexOfAny(charArrayOf('"', '\''), startIndex + prefix.length)
        if (quoteStart < 0) {
            return null
        }
        val quote = text[quoteStart]
        val quoteEnd = text.indexOf(quote, quoteStart + 1)
        if (quoteEnd <= quoteStart + 1) {
            return null
        }
        return text.substring(quoteStart + 1, quoteEnd).trim().takeIf { it.isNotBlank() }
    }

    private fun String.isQuotedLiteral(): Boolean =
        (startsWith('"') && endsWith('"')) || (startsWith('\'') && endsWith('\''))

    private fun buildSyntheticGradleNode(
        ownerFile: VirtualFile,
        ownerProjectName: String,
        displayName: String,
        scope: String,
        parentPath: List<String>,
    ): MavenDependencyNodeView {
        val group = displayName.substringBefore(':', "")
        val artifact = if (group.isBlank()) displayName else displayName.substringAfter(':').substringBefore(':')
        val version = displayName.substringAfterLast(':', "")
        val key = if (group.isBlank()) artifact else "$group:$artifact"
        return MavenDependencyNodeView(
            ownerProjectName = ownerProjectName,
            ownerProjectFile = ownerFile,
            groupId = group,
            artifactId = artifact,
            version = if (version == displayName) "" else version,
            scope = scope,
            packaging = null,
            path = parentPath + key,
            sourceDependency = null,
        )
    }

    private fun ArtifactDependencyNode.toDependencyCoordinate(ownerFile: VirtualFile, scope: String): DependencyCoordinate {
        val declaration = if (group.isBlank()) "$module:$version" else "$group:$module:$version"
        val range = TextRangeMarker(0, 0)
        return DependencyCoordinate(
            ecosystem = Ecosystem.GRADLE,
            group = group.takeIf { it.isNotBlank() },
            name = module,
            version = version,
            declaredVersion = version.takeIf { it.isNotBlank() },
            scope = scope,
            file = ownerFile,
            declarationText = declaration,
            lineNumber = 1,
            versionRange = range,
            displayRange = range,
            inspectionRange = range,
        )
    }

    private fun declaredPluginDependencies(file: VirtualFile): List<DependencyCoordinate> {
        val (text, locations) = collectGradleEditorLocationsFromDocument(file) ?: return emptyList()
        return locations
            .filter { it.scope in GRADLE_PLUGIN_SCOPES }
            .map { it.toDependencyCoordinate(file, text) }
            .distinctBy { "${it.scope}:${it.group}:${it.name}:${it.version}:${it.displayRange.startOffset}" }
    }

    private fun gradlePluginMarkerArtifact(pluginId: String): String = "$pluginId.gradle.plugin"

    private fun kotlinPluginId(notation: String): String? {
        val value = notation.trim().takeIf { it.isNotEmpty() } ?: return null
        return "org.jetbrains.kotlin.$value"
    }

    private fun GradleEditorLocation.toDependencyCoordinate(file: VirtualFile, text: String): DependencyCoordinate {
        return DependencyCoordinate(
            ecosystem = Ecosystem.GRADLE,
            group = group,
            name = name,
            version = version,
            declaredVersion = declaredVersion,
            scope = scope,
            file = file,
            declarationText = declarationText,
            lineNumber = text.take(displayRange.startOffset).count { it == '\n' } + 1,
            versionRange = versionRange,
            displayRange = displayRange,
            inspectionRange = inspectionRange,
        )
    }

    private fun GradleEditorLocation.pathSegment(): String = "$group:$name"

    private fun isLocated(dependency: DependencyCoordinate): Boolean =
        dependency.displayRange.startOffset != 0 || dependency.displayRange.endOffset != 0

    private fun flattenResolvedNodes(root: MavenDependencyNodeView): List<MavenDependencyNodeView> {
        val result = mutableListOf<MavenDependencyNodeView>()
        val seen = mutableSetOf<List<String>>()
        fun visit(node: MavenDependencyNodeView) {
            if (!seen.add(node.path)) {
                return
            }
            result += node
            node.children.forEach(::visit)
        }
        root.children.forEach(::visit)
        return result
    }

    private fun logGradleTreeDiagnostics(file: VirtualFile, root: MavenDependencyNodeView) {
        val nodes = flattenResolvedNodes(root)
        if (nodes.isEmpty()) {
            thisLogger().info("DependencyHelper Gradle tree diag: file=${file.path}, nodes=0")
            return
        }
    }

    private fun resolveCatalogTarget(file: VirtualFile, dependency: DependencyCoordinate): GradleCatalogUpgradeTarget? {
        val accessor = gradleCatalogAccessor(dependency.declaredVersion) ?: return null
        val catalogSources = loadVersionCatalogSources(file)
        val catalogName = accessor.catalogName
        val catalogSource = catalogSources[catalogName] ?: return null
        val catalog = parseVersionCatalog(catalogSource.text)
        val alias = when {
            accessor.alias.startsWith("bundles.") -> {
                val bundle = catalog.bundles[accessor.alias.removePrefix("bundles.")] ?: return null
                bundle.firstOrNull { libraryAlias ->
                    val library = catalog.libraries[libraryAlias] ?: return@firstOrNull false
                    library.group == dependency.group && library.name == dependency.name
                } ?: return null
            }
            accessor.alias.startsWith("plugins.") -> accessor.alias.removePrefix("plugins.")
            else -> accessor.alias
        }
        if (!catalogSource.sourceCoordinate.isNullOrBlank()) {
            return GradleCatalogUpgradeTarget.RemoteCatalogVersion(
                sourceFile = file,
                file = requireNotNull(catalogSource.editableFile),
                coordinate = catalogSource.sourceCoordinate,
            )
        }
        if (accessor.alias.startsWith("plugins.")) {
            val plugin = catalog.plugins[alias] ?: return null
            return when {
                !plugin.versionRef.isNullOrBlank() -> GradleCatalogUpgradeTarget.VersionRef(
                    sourceFile = file,
                    file = requireNotNull(catalogSource.editableFile),
                    reference = plugin.versionRef,
                )
                !plugin.version.isNullOrBlank() -> GradleCatalogUpgradeTarget.PluginVersion(
                    sourceFile = file,
                    file = requireNotNull(catalogSource.editableFile),
                    alias = denormalizeCatalogAlias(alias),
                )
                else -> null
            }
        }
        val library = catalog.libraries[alias] ?: return null
        return when {
            !library.versionRef.isNullOrBlank() -> GradleCatalogUpgradeTarget.VersionRef(
                sourceFile = file,
                file = requireNotNull(catalogSource.editableFile),
                reference = library.versionRef,
            )
            !library.version.isNullOrBlank() -> GradleCatalogUpgradeTarget.LibraryVersion(
                sourceFile = file,
                file = requireNotNull(catalogSource.editableFile),
                alias = denormalizeCatalogAlias(alias),
            )
            else -> null
        }
    }

    private fun findGradleProjectRoot(file: VirtualFile): VirtualFile? {
        var current: VirtualFile? = if (file.isDirectory) file else file.parent
        while (current != null) {
            if (current.children.any { it.name in GRADLE_SETTINGS_FILE_NAMES }) {
                return current
            }
            current = current.parent
        }
        return if (file.isDirectory) file else file.parent
    }

    private fun findGradleDependencyGraphContext(projectPath: String?): GradleDependencyGraphContext? {
        val projectNode = projectPath
            ?.let { ExternalSystemApiUtil.findProjectNode(project, GradleConstants.SYSTEM_ID, it) }
            ?: return null
        val dependencyGraphNode = ExternalSystemApiUtil.find(projectNode, ProjectKeys.DEPENDENCIES_GRAPH)
            ?: findProjectDependenciesDataNode(projectNode)
            ?: run {
                return null
            }
        return GradleDependencyGraphContext(dependencyGraphNode.data)
    }

    private fun findProjectDependenciesDataNode(projectNode: DataNode<*>): DataNode<ProjectDependencies>? {
        val queue = ArrayDeque<DataNode<*>>()
        queue += projectNode
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            @Suppress("UNCHECKED_CAST")
            if (current.data is ProjectDependencies) {
                return current as DataNode<ProjectDependencies>
            }
            current.children.forEach(queue::addLast)
        }
        return null
    }

    private data class GradleDependencyGraphContext(
        val dependencies: ProjectDependencies,
    )

    private data class GradleEditorLocation(
        val group: String,
        val name: String,
        val version: String,
        val scope: String?,
        val declaredVersion: String,
        val declarationText: String,
        val versionRange: TextRangeMarker,
        val displayRange: TextRangeMarker,
        val inspectionRange: TextRangeMarker,
    )

    private fun resolveAnalysisFile(file: VirtualFile): VirtualFile {
        if (file.name !in DependencyFileKind.GRADLE_SETTINGS.fileNames) {
            return file
        }
        val root = findGradleProjectRoot(file) ?: return file
        return DependencyFiles.findChild(root, DependencyFileKind.GRADLE_BUILD) ?: file
    }

    private companion object {
        val GRADLE_SYSTEM_ID = ProjectSystemId("GRADLE")
        val GRADLE_FILE_NAMES = DependencyFileKind.GRADLE_BUILD.fileNames + DependencyFileKind.GRADLE_SETTINGS.fileNames
        val GRADLE_SETTINGS_FILE_NAMES = DependencyFileKind.GRADLE_SETTINGS.fileNames
        val GRADLE_PLUGIN_SCOPES = setOf("plugin", "pluginManagement")
        val GRADLE_DEPENDENCY_SCOPES = setOf(
            "api",
            "implementation",
            "compileOnly",
            "compileOnlyApi",
            "runtimeOnly",
            "testApi",
            "testImplementation",
            "testCompileOnly",
            "testRuntimeOnly",
            "androidTestApi",
            "androidTestImplementation",
            "androidTestCompileOnly",
            "androidTestRuntimeOnly",
            "kapt",
            "ksp",
        )
        const val MAX_GRADLE_TREE_DEPTH = 4
    }
}

internal fun enrichGradleDependencies(
    buildText: String,
    gradlePropertiesText: String?,
    versionCatalogs: Map<String, GradleVersionCatalogSource>,
    dependencies: List<DependencyCoordinate>,
): List<DependencyCoordinate> {
    val context = parseGradleVersionContext(buildText, gradlePropertiesText, versionCatalogs)
    return dependencies.flatMap { dependency ->
        if (dependency.ecosystem != Ecosystem.GRADLE) {
            listOf(dependency)
        } else {
            val resolved = resolveGradleDependencies(context, dependency)
            if (resolved.isEmpty()) {
                listOf(dependency)
            } else {
                resolved.map { item ->
                    dependency.copy(
                        group = item.group ?: dependency.group,
                        name = item.name ?: dependency.name,
                        version = item.version ?: dependency.version,
                    )
                }
            }
        }
    }
}

internal data class GradleVersionContext(
    val properties: Map<String, String>,
    val catalogs: Map<String, GradleVersionCatalog>,
    val kotlinPluginVersion: String?,
    val ktorPluginVersion: String?,
)

internal data class GradleVersionCatalogSource(
    val name: String,
    val file: VirtualFile,
    val text: String,
    val editableFile: VirtualFile? = file,
    val sourceCoordinate: String? = null,
)

internal data class GradleVersionCatalog(
    val versions: Map<String, String>,
    val libraries: Map<String, GradleCatalogLibrary>,
    val bundles: Map<String, List<String>>,
    val plugins: Map<String, GradleCatalogPlugin>,
)

internal data class GradleCatalogLibrary(
    val group: String,
    val name: String,
    val version: String?,
    val versionRef: String?,
)

internal data class GradleCatalogPlugin(
    val id: String,
    val version: String?,
    val versionRef: String?,
)

internal data class ResolvedGradleDependency(
    val group: String?,
    val name: String?,
    val version: String?,
)

internal fun parseGradleVersionContext(
    buildText: String,
    gradlePropertiesText: String?,
    versionCatalogs: Map<String, GradleVersionCatalogSource>,
): GradleVersionContext {
    val properties = mutableMapOf<String, String>()
    gradlePropertiesText
        ?.lineSequence()
        ?.map(String::trim)
        ?.filter { it.isNotBlank() && !it.startsWith("#") }
        ?.forEach { line ->
            val separator = line.indexOf('=')
            if (separator > 0) {
                properties[line.substring(0, separator).trim()] = line.substring(separator + 1).trim()
            }
        }

    extractGradleValAssignments(buildText).forEach { (key, value) -> properties[key] = value }
    extractGradleExtraAssignments(buildText).forEach { (key, value) -> properties[key] = value }

    val kotlinPluginVersion = extractKotlinPluginVersion(buildText)
    val ktorPluginVersion = extractPluginVersion(buildText, "io.ktor.plugin")

    val catalogs = versionCatalogs.mapValues { (_, source) -> parseVersionCatalog(source.text) }

    return GradleVersionContext(
        properties = properties,
        catalogs = catalogs,
        kotlinPluginVersion = kotlinPluginVersion,
        ktorPluginVersion = ktorPluginVersion,
    )
}

private fun extractGradleValAssignments(buildText: String): Map<String, String> {
    val values = linkedMapOf<String, String>()
    buildText.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (!line.startsWith("val ")) return@forEach
        val rest = line.removePrefix("val ").trim()
        val eq = rest.indexOf('=')
        if (eq <= 0) return@forEach
        val key = rest.substring(0, eq).trim()
        val value = quotedValue(rest.substring(eq + 1)) ?: return@forEach
        if (key.isNotBlank()) values[key] = value
    }
    return values
}

private fun extractGradleExtraAssignments(buildText: String): Map<String, String> {
    val values = linkedMapOf<String, String>()
    buildText.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (!line.startsWith("extra[")) return@forEach
        val keyStart = line.indexOfAny(charArrayOf('"', '\''))
        if (keyStart < 0) return@forEach
        val keyQuote = line[keyStart]
        val keyEnd = line.indexOf(keyQuote, keyStart + 1)
        if (keyEnd <= keyStart + 1) return@forEach
        val key = line.substring(keyStart + 1, keyEnd)
        val eq = line.indexOf('=', keyEnd + 1)
        if (eq < 0) return@forEach
        val value = quotedValue(line.substring(eq + 1)) ?: return@forEach
        if (key.isNotBlank()) values[key] = value
    }
    return values
}

private fun extractKotlinPluginVersion(buildText: String): String? {
    return extractVersionAfterPrefix(buildText, "kotlin(\"")
        ?: extractPluginVersion(buildText, "org.jetbrains.kotlin")
}

private fun extractPluginVersion(buildText: String, pluginIdPrefix: String): String? {
    buildText.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (!line.contains("id(")) return@forEach
        val id = extractIdCallArgument(line) ?: return@forEach
        if (!(id == pluginIdPrefix || id.startsWith("$pluginIdPrefix."))) return@forEach
        val version = extractVersionAfterKeyword(line)
        if (!version.isNullOrBlank()) return version
    }
    return null
}

private fun extractIdCallArgument(line: String): String? {
    val idIndex = line.indexOf("id(")
    if (idIndex < 0) return null
    val start = line.indexOfAny(charArrayOf('"', '\''), idIndex + 3)
    if (start < 0) return null
    val quote = line[start]
    val end = line.indexOf(quote, start + 1)
    if (end <= start + 1) return null
    return line.substring(start + 1, end)
}

private fun extractVersionAfterPrefix(buildText: String, prefix: String): String? {
    buildText.lineSequence().forEach { raw ->
        val line = raw.trim()
        val idx = line.indexOf(prefix)
        if (idx < 0) return@forEach
        val version = extractVersionAfterKeyword(line)
        if (!version.isNullOrBlank()) return version
    }
    return null
}

private fun extractVersionAfterKeyword(line: String): String? {
    val idx = line.indexOf("version")
    if (idx < 0) return null
    return quotedValue(line.substring(idx + "version".length))
}

private fun quotedValue(text: String): String? {
    val start = text.indexOfAny(charArrayOf('"', '\''))
    if (start < 0) return null
    val quote = text[start]
    val end = text.indexOf(quote, start + 1)
    if (end <= start + 1) return null
    return text.substring(start + 1, end)
}

internal fun resolveGradleDependencies(
    context: GradleVersionContext,
    dependency: DependencyCoordinate,
): List<ResolvedGradleDependency> {
    val declared = dependency.declaredVersion
    gradleCatalogAccessor(declared)?.let { accessor ->
        val catalog = context.catalogs[accessor.catalogName] ?: return@let
        if (accessor.alias.startsWith("plugins.")) {
            val pluginAlias = accessor.alias.removePrefix("plugins.")
            val plugin = catalog.plugins[pluginAlias] ?: return@let
            return listOf(
                ResolvedGradleDependency(
                    group = null,
                    name = plugin.id,
                    version = plugin.version ?: plugin.versionRef?.let(catalog.versions::get) ?: dependency.version.takeIf { it.isNotBlank() },
                ),
            )
        }
        if (accessor.alias.startsWith("bundles.")) {
            val bundleAlias = accessor.alias.removePrefix("bundles.")
            return catalog.bundles[bundleAlias].orEmpty().mapNotNull { libraryAlias ->
                val library = catalog.libraries[libraryAlias] ?: return@mapNotNull null
                ResolvedGradleDependency(
                    group = library.group,
                    name = library.name,
                    version = library.version ?: library.versionRef?.let(catalog.versions::get) ?: dependency.version.takeIf { it.isNotBlank() },
                )
            }
        }
        val library = catalog.libraries[accessor.alias] ?: return@let
        val resolvedVersion = library.version
            ?: library.versionRef?.let(catalog.versions::get)
            ?: dependency.version.takeIf { it.isNotBlank() }
        return listOf(
            ResolvedGradleDependency(
                group = library.group,
                name = library.name,
                version = resolvedVersion,
            ),
        )
    }
    if (!declared.isNullOrBlank()) {
        val propertyName = gradlePropertyName(declared)
        if (propertyName != null) {
            return listOf(
                ResolvedGradleDependency(
                group = dependency.group,
                name = dependency.name,
                version = context.properties[propertyName] ?: dependency.version.takeIf { it.isNotBlank() },
                ),
            )
        }
        return listOf(
            ResolvedGradleDependency(
                group = dependency.group,
                name = dependency.name,
                version = dependency.version.takeIf { it.isNotBlank() },
            ),
        )
    }
    val resolvedVersion = when {
        dependency.group == "io.ktor" -> context.ktorPluginVersion
        dependency.group == "org.jetbrains.kotlin" -> context.kotlinPluginVersion
        else -> dependency.version.takeIf { it.isNotBlank() }
    }
    return listOf(
        ResolvedGradleDependency(
            group = dependency.group,
            name = dependency.name,
            version = resolvedVersion,
        ),
    )
}

internal fun gradlePropertyName(versionExpression: String): String? {
    val trimmed = versionExpression.trim()
    if (trimmed.isEmpty()) return null
    val inner = when {
        trimmed.startsWith("\${") && trimmed.endsWith("}") -> trimmed.substring(2, trimmed.length - 1).trim()
        trimmed.startsWith("$") -> trimmed.substring(1).trim()
        else -> trimmed
    }
    if (inner.isEmpty()) return null
    return if (inner.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }) inner else null
}

internal data class GradleCatalogAccessor(
    val catalogName: String,
    val alias: String,
)

internal fun gradleCatalogAccessor(versionExpression: String?): GradleCatalogAccessor? {
    val declared = versionExpression?.trim() ?: return null
    val separator = declared.indexOf('.')
    if (separator <= 0 || separator == declared.lastIndex) {
        return null
    }
    return GradleCatalogAccessor(
        catalogName = declared.substring(0, separator),
        alias = normalizeCatalogAlias(declared.substring(separator + 1)),
    )
}

private fun normalizeCatalogAlias(alias: String): String = alias.replace('-', '.').replace('_', '.')
private fun denormalizeCatalogAlias(alias: String): String = alias.replace('.', '-')
private fun parseVersionCatalog(versionCatalogText: String?): GradleVersionCatalog {
    val sections = parseTomlSections(versionCatalogText.orEmpty())
    return GradleVersionCatalog(
        versions = parseVersionCatalogVersions(sections["versions"].orEmpty()),
        libraries = parseVersionCatalogLibraries(sections["libraries"].orEmpty()),
        bundles = parseVersionCatalogBundles(sections["bundles"].orEmpty()),
        plugins = parseVersionCatalogPlugins(sections["plugins"].orEmpty()),
    )
}

private fun parseVersionCatalogVersions(entries: Map<String, String>): Map<String, String> {
    val result = linkedMapOf<String, String>()
    entries.forEach { (key, value) ->
        parseTomlString(value)?.let { result[normalizeCatalogAlias(key)] = it.trim() }
    }
    return result
}

private fun parseVersionCatalogLibraries(entries: Map<String, String>): Map<String, GradleCatalogLibrary> {
    val result = mutableMapOf<String, GradleCatalogLibrary>()
    entries.forEach { (aliasRaw, rawValue) ->
        val alias = normalizeCatalogAlias(aliasRaw)
        val stringValue = parseTomlString(rawValue)
        if (stringValue != null) {
            val parts = stringValue.split(':')
            if (parts.size >= 3) {
                result[alias] = GradleCatalogLibrary(
                    group = parts[0].trim(),
                    name = parts[1].trim(),
                    version = parts.subList(2, parts.size).joinToString(":").trim(),
                    versionRef = null,
                )
            }
            return@forEach
        }
        val table = parseTomlInlineTable(rawValue) ?: return@forEach
        val module = table["module"]?.let(::parseTomlString)
        val group = table["group"]?.let(::parseTomlString)?.trim()
        val name = table["name"]?.let(::parseTomlString)?.trim()
        val version = table["version"]?.let(::parseTomlString)?.trim()
        val versionRef = tomlVersionRef(table)?.trim()?.let(::normalizeCatalogAlias)
        val resolvedGroup = module?.substringBefore(':')?.trim()?.takeIf { it.isNotEmpty() } ?: group
        val resolvedName = module?.substringAfter(':', "")?.trim()?.takeIf { it.isNotEmpty() } ?: name
        if (!resolvedGroup.isNullOrBlank() && !resolvedName.isNullOrBlank()) {
            result[alias] = GradleCatalogLibrary(
                group = resolvedGroup,
                name = resolvedName,
                version = version,
                versionRef = versionRef,
            )
            }
        }
    return result
}

private fun parseVersionCatalogBundles(entries: Map<String, String>): Map<String, List<String>> {
    val result = linkedMapOf<String, List<String>>()
    entries.forEach { (key, value) ->
        result[normalizeCatalogAlias(key)] = parseTomlStringArray(value).map(::normalizeCatalogAlias)
    }
    return result
}

private fun parseVersionCatalogPlugins(entries: Map<String, String>): Map<String, GradleCatalogPlugin> {
    val result = mutableMapOf<String, GradleCatalogPlugin>()
    entries.forEach { (key, rawValue) ->
        val table = parseTomlInlineTable(rawValue) ?: return@forEach
        val id = table["id"]?.let(::parseTomlString)?.trim() ?: return@forEach
        val version = table["version"]?.let(::parseTomlString)?.trim()
        val versionRef = tomlVersionRef(table)?.trim()?.let(::normalizeCatalogAlias)
        result[normalizeCatalogAlias(key)] = GradleCatalogPlugin(
            id = id,
            version = version,
            versionRef = versionRef,
        )
    }
    return result
}

private fun tomlVersionRef(table: Map<String, String>): String? {
    val dotted = table["version.ref"]?.let(::parseTomlString)
    if (!dotted.isNullOrBlank()) return dotted
    val versionTable = table["version"]?.let(::parseTomlInlineTable)
    return versionTable?.get("ref")?.let(::parseTomlString)
}

private fun parseTomlSections(text: String): Map<String, Map<String, String>> {
    val result = linkedMapOf<String, LinkedHashMap<String, String>>()
    var currentSection: String? = null
    text.lineSequence().forEach { raw ->
        val line = raw.substringBefore('#').trim()
        if (line.isBlank()) return@forEach
        if (line.startsWith("[") && line.endsWith("]")) {
            currentSection = line.trim('[', ']').trim()
            result.getOrPut(currentSection!!) { linkedMapOf() }
            return@forEach
        }
        val section = currentSection ?: return@forEach
        val eq = line.indexOf('=')
        if (eq <= 0) return@forEach
        val key = line.substring(0, eq).trim()
        val value = line.substring(eq + 1).trim()
        result.getOrPut(section) { linkedMapOf() }[key] = value
    }
    return result
}

private fun parseTomlString(raw: String): String? {
    val value = raw.trim().trimEnd(',')
    if (value.length < 2) return null
    val quote = value.first()
    if (quote != '"' && quote != '\'') return null
    val end = value.indexOf(quote, 1)
    if (end <= 0) return null
    return value.substring(1, end)
}

private fun parseTomlStringArray(raw: String): List<String> {
    val value = raw.trim()
    if (!value.startsWith("[") || !value.endsWith("]")) return emptyList()
    return splitTomlTopLevel(value.substring(1, value.length - 1))
        .mapNotNull(::parseTomlString)
}

private fun parseTomlInlineTable(raw: String): Map<String, String>? {
    val value = raw.trim()
    if (!value.startsWith("{") || !value.endsWith("}")) return null
    val result = linkedMapOf<String, String>()
    splitTomlTopLevel(value.substring(1, value.length - 1)).forEach { entry ->
        val eq = entry.indexOf('=')
        if (eq <= 0) return@forEach
        result[entry.substring(0, eq).trim()] = entry.substring(eq + 1).trim()
    }
    return result
}

private fun splitTomlTopLevel(text: String): List<String> {
    val result = mutableListOf<String>()
    var start = 0
    var quote: Char? = null
    var braceDepth = 0
    var bracketDepth = 0
    text.forEachIndexed { index, ch ->
        when {
            quote != null -> if (ch == quote) quote = null
            ch == '"' || ch == '\'' -> quote = ch
            ch == '{' -> braceDepth++
            ch == '}' -> braceDepth--
            ch == '[' -> bracketDepth++
            ch == ']' -> bracketDepth--
            ch == ',' && braceDepth == 0 && bracketDepth == 0 -> {
                result += text.substring(start, index).trim()
                start = index + 1
            }
        }
    }
    val tail = text.substring(start).trim()
    if (tail.isNotEmpty()) result += tail
    return result
}

internal fun parseSettingsCatalogMappings(baseDir: Path, settingsText: String): Map<String, String> {
    val result = linkedMapOf<String, String>()
    val lines = settingsText.lines()
    var inVersionCatalogs = false
    var versionCatalogDepth = 0
    lines.forEachIndexed { index, raw ->
        val line = raw.trim()
        if (!inVersionCatalogs) {
            if (line.startsWith("versionCatalogs")) {
                inVersionCatalogs = true
                versionCatalogDepth = line.count { it == '{' } - line.count { it == '}' }
            }
            return@forEachIndexed
        }
        versionCatalogDepth += line.count { it == '{' } - line.count { it == '}' }
        if (versionCatalogDepth <= 0 && !(line.startsWith("create(") || line.startsWith("maybeCreate("))) {
            inVersionCatalogs = false
            versionCatalogDepth = 0
            return@forEachIndexed
        }
        if (!(line.startsWith("create(") || line.startsWith("maybeCreate("))) return@forEachIndexed
        val name = betweenFirstQuotes(line) ?: return@forEachIndexed
        val fromPart = when {
            ".from(" in line -> line.substringAfter(".from(", "").substringBeforeLast(")", "")
            "from(" in line -> line.substringAfter("from(", "").substringBeforeLast(")", "")
            else -> ""
        }
        if (fromPart.isNotBlank()) {
            parseCatalogFromClause(fromPart, baseDir)?.let { result[name] = it }
            return@forEachIndexed
        }
        var j = index + 1
        var createDepth = line.count { it == '{' } - line.count { it == '}' }
        if (createDepth <= 0) {
            return@forEachIndexed
        }
        while (j < lines.size && (createDepth > 0 || !lines[j].trim().startsWith("create("))) {
            val candidate = lines[j].trim()
            createDepth += candidate.count { it == '{' } - candidate.count { it == '}' }
            if (candidate.startsWith("from(")) {
                val arg = candidate.substringAfter("from(", "").substringBeforeLast(")", "")
                parseCatalogFromClause(arg, baseDir)?.let { result[name] = it }
                break
            }
            j++
        }
    }
    return result
}

private fun parseCatalogFromClause(raw: String, baseDir: Path): String? {
    val trimmed = raw.trim()
    if (trimmed.startsWith("files(")) {
        val inner = trimmed.substringAfter("files(", "").substringBeforeLast(")", "")
        val path = betweenFirstQuotes(inner) ?: return null
        return baseDir.resolve(path).normalize().toString()
    }
    val coordinate = betweenFirstQuotes(trimmed) ?: return null
    if (coordinate.count { it == ':' } >= 2) {
        return coordinate
    }
    return null
}

private fun betweenFirstQuotes(text: String): String? {
    val q1 = text.indexOfAny(charArrayOf('"', '\''))
    if (q1 < 0) return null
    val q2 = text.indexOf(text[q1], q1 + 1)
    if (q2 <= q1) return null
    return text.substring(q1 + 1, q2)
}

private sealed interface GradleCatalogUpgradeTarget {
    val sourceFile: VirtualFile
    val file: VirtualFile

    data class VersionRef(
        override val sourceFile: VirtualFile,
        override val file: VirtualFile,
        val reference: String,
    ) : GradleCatalogUpgradeTarget

    data class LibraryVersion(
        override val sourceFile: VirtualFile,
        override val file: VirtualFile,
        val alias: String,
    ) : GradleCatalogUpgradeTarget

    data class PluginVersion(
        override val sourceFile: VirtualFile,
        override val file: VirtualFile,
        val alias: String,
    ) : GradleCatalogUpgradeTarget

    data class RemoteCatalogVersion(
        override val sourceFile: VirtualFile,
        override val file: VirtualFile,
        val coordinate: String,
    ) : GradleCatalogUpgradeTarget
}

private fun findGradlePropertyAssignmentRange(text: String, propertyName: String): TextRangeMarker? {
    var lineStart = 0
    while (lineStart < text.length) {
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        val trimmed = line.trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
            val eq = line.indexOf('=')
            if (eq > 0) {
                val name = line.substring(0, eq).trim()
                if (name == propertyName) {
                    val valueStartInLine = eq + 1 + line.substring(eq + 1).takeWhile { it.isWhitespace() }.length
                    val commentIdx = line.indexOf('#', valueStartInLine).let { if (it < 0) line.length else it }
                    val valueEndInLine = commentIdx
                    return TextRangeMarker(lineStart + valueStartInLine, lineStart + valueEndInLine)
                }
            }
        }
        lineStart = lineEnd + 1
    }
    return null
}

private fun findKotlinPropertyAssignmentRange(text: String, propertyName: String): TextRangeMarker? {
    var lineStart = 0
    while (lineStart < text.length) {
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        val trimmed = line.trim()
        val valPrefix = "val $propertyName"
        if (trimmed.startsWith(valPrefix) && trimmed.contains('=')) {
            val eq = line.indexOf('=')
            val quote = line.indexOfAny(charArrayOf('"', '\''), eq + 1)
            if (quote > eq) {
                val end = line.indexOf(line[quote], quote + 1).takeIf { it > quote } ?: return null
                return TextRangeMarker(lineStart + quote + 1, lineStart + end)
            }
        }
        val extraPrefixDouble = "extra[\"$propertyName\"]"
        val extraPrefixSingle = "extra['$propertyName']"
        if ((trimmed.startsWith(extraPrefixDouble) || trimmed.startsWith(extraPrefixSingle)) && trimmed.contains('=')) {
            val eq = line.indexOf('=')
            val quote = line.indexOfAny(charArrayOf('"', '\''), eq + 1)
            if (quote > eq) {
                val end = line.indexOf(line[quote], quote + 1).takeIf { it > quote } ?: return null
                return TextRangeMarker(lineStart + quote + 1, lineStart + end)
            }
        }
        lineStart = lineEnd + 1
    }
    return null
}

private fun findTomlSimpleValueRange(text: String, key: String): TextRangeMarker? {
    val lineRange = findTomlLineRange(text, key) ?: return null
    val line = text.substring(lineRange.startOffset, lineRange.endOffset)
    val eq = line.indexOf('=')
    if (eq < 0) return null
    val quote = line.indexOfAny(charArrayOf('"', '\''), eq + 1)
    if (quote < 0) return null
    val end = line.indexOf(line[quote], quote + 1).takeIf { it > quote } ?: return null
    return TextRangeMarker(lineRange.startOffset + quote + 1, lineRange.startOffset + end)
}

private fun findTomlLineRange(text: String, key: String): TextRangeMarker? {
    var lineStart = 0
    while (lineStart < text.length) {
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        val eq = line.indexOf('=')
        if (eq > 0 && line.substring(0, eq).trim() == key) {
            return TextRangeMarker(lineStart, lineEnd)
        }
        lineStart = lineEnd + 1
    }
    return null
}

private fun findInlineTomlKeyValueRange(body: String, key: String): TextRangeMarker? {
    val keyIdx = body.indexOf(key)
    if (keyIdx < 0) return null
    val eq = body.indexOf('=', keyIdx)
    if (eq < 0) return null
    val quote = body.indexOfAny(charArrayOf('"', '\''), eq + 1)
    if (quote < 0) return null
    val end = body.indexOf(body[quote], quote + 1).takeIf { it > quote } ?: return null
    return TextRangeMarker(quote + 1, end)
}
