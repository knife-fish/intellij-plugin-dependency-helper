package org.knifefish.dependency.helper.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.dependencies.ArtifactDependencyNode
import com.intellij.openapi.externalSystem.model.project.dependencies.DependencyNode
import com.intellij.openapi.externalSystem.model.project.dependencies.ProjectDependencyNode
import com.intellij.openapi.externalSystem.model.project.dependencies.ReferenceNode
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback
import com.intellij.openapi.externalSystem.service.project.ExternalSystemModuleDataIndex
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsDataStorage
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.plugins.gradle.dependency.analyzer.GradleDependencyNodeIndex
import org.jetbrains.plugins.gradle.service.project.GradleModuleDataIndex
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.jetbrains.plugins.gradle.util.GradleModuleData
import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.MavenDependencyNodeView
import org.knifefish.dependency.helper.model.TextRangeMarker
import org.knifefish.dependency.helper.scanner.DependencyFileScanner
import java.nio.file.Path
import java.nio.file.Paths

class DefaultGradleSupport(private val project: Project) : GradleSupport {

    override fun analyze(file: VirtualFile): List<MavenDependencyNodeView> {
        if (!isGradleFile(file)) {
            return emptyList()
        }
        val analysisFile = resolveAnalysisFile(file)
        val projectPath = findGradleProjectPath(analysisFile)
        val moduleData = ReadAction.compute<GradleModuleData?, RuntimeException> {
            val byProjectPath = projectPath
                ?.let { ExternalSystemModuleDataIndex.findModuleNode(project, it) }
                ?.let(::GradleModuleData)
            if (byProjectPath != null) {
                return@compute byProjectPath
            }
            val byStorage = projectPath?.let(::findGradleModuleDataInStorage)
            if (byStorage != null) {
                return@compute byStorage
            }
            val byModuleScan = projectPath?.let(::findGradleModuleDataByProjectPath)
            if (byModuleScan != null) {
                return@compute byModuleScan
            }
            val module = ProjectFileIndex.getInstance(project).getModuleForFile(analysisFile, false)
                ?: ModuleUtilCore.findModuleForFile(analysisFile, project)
                ?: return@compute null
            GradleModuleDataIndex.findGradleModuleData(module)
        } ?: run {
            thisLogger().info(
                "DependencyHelper Gradle analyze: file=${file.path}, analysisFile=${analysisFile.path}, projectPath=$projectPath, " +
                    "resolvedScopes=0, fallback=declared-no-module",
            )
            return buildDeclaredDependencyRoots(file)
        }

        thisLogger().info(
            "DependencyHelper Gradle analyze collecting: file=${file.path}, analysisFile=${analysisFile.path}, " +
                "projectPath=$projectPath, module=${moduleData.moduleName}, gradleProjectDir=${moduleData.gradleProjectDir}",
        )
        val scopeNodes = runCatching {
            GradleDependencyNodeIndex.getOrCollectDependencies(project, moduleData).get()
        }.getOrElse {
            thisLogger().warn("DependencyHelper Gradle analyze failed for ${file.path} using ${analysisFile.path}", it)
            emptyList()
        }
        thisLogger().info(
            "DependencyHelper Gradle analyze collected: file=${file.path}, analysisFile=${analysisFile.path}, " +
                "projectPath=$projectPath, scopeNodes=${scopeNodes.size}",
        )
        if (scopeNodes.isEmpty()) {
            thisLogger().info(
                "DependencyHelper Gradle analyze: file=${file.path}, analysisFile=${analysisFile.path}, projectPath=$projectPath, " +
                    "resolvedScopes=0, fallback=declared-empty",
            )
            return buildDeclaredDependencyRoots(file)
        }

        val directDependencies = enrichDependencies(
            file,
            DependencyFileScanner().scan(file, file.inputStream.bufferedReader().use { it.readText() }),
        )
        val directDependenciesByIdentity = directDependencies.associateBy { dependencyIdentity(it.group, it.name, it.version) }
        val directDependenciesByKey = directDependencies.associateBy { dependencyKey(it.group, it.name) }
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
        val sourceNodesById = mutableMapOf<Long, DependencyNode>()
        val occurrenceTreesByDirectKey = linkedMapOf<String, MutableList<MavenDependencyNodeView>>()
        scopeNodes.forEach { scopeNode ->
            scopeNode.dependencies.forEach { dependencyNode ->
                val occurrence = buildResolvedGradleNode(
                    ownerFile = file,
                    ownerProjectName = projectName,
                    node = dependencyNode,
                    inheritedScope = scopeNode.scope,
                    parentPath = rootPath,
                    directDependenciesByIdentity = directDependenciesByIdentity,
                    directDependenciesByKey = directDependenciesByKey,
                    sourceNodesById = sourceNodesById,
                    visitingIds = linkedSetOf(),
                )
                val directKey = directDependencyKey(occurrence, directDependenciesByIdentity, directDependenciesByKey)
                if (directKey != null) {
                    occurrenceTreesByDirectKey.getOrPut(directKey) { mutableListOf() }.add(occurrence)
                }
            }
        }
        directDependencies.forEach { dependency ->
            val directKey = dependencyKey(dependency.group, dependency.name)
            val rootChild = MavenDependencyNodeView(
                ownerProjectName = projectName,
                ownerProjectFile = file,
                groupId = dependency.group.orEmpty(),
                artifactId = dependency.name,
                version = dependency.version,
                scope = dependency.scope,
                packaging = null,
                path = rootPath + dependency.displayName,
                sourceDependency = dependency,
            )
            occurrenceTreesByDirectKey[directKey].orEmpty().forEach { occurrence ->
                occurrence.children.forEach { child ->
                    mergeInto(rootChild.children, child)
                }
            }
            root.children += rootChild
        }
        thisLogger().info(
            "DependencyHelper Gradle analyze: file=${file.path}, analysisFile=${analysisFile.path}, projectPath=$projectPath, " +
                "resolvedScopes=${scopeNodes.size}, " +
                "topLevel=${root.children.size}, topLevelChildren=${root.children.joinToString { "${it.displayName}:${it.children.size}" }}, " +
                "nodeCount=${flattenResolvedNodes(root).size}",
        )
        return listOf(root)
    }

    override fun enrichDependencies(file: VirtualFile, dependencies: List<DependencyCoordinate>): List<DependencyCoordinate> {
        if (!isGradleFile(file)) {
            return dependencies
        }
        val buildText = file.inputStream.bufferedReader().use { it.readText() }
        val gradleProperties = findGradlePropertiesFile(file)?.inputStream?.bufferedReader()?.use { it.readText() }
        val catalogSources = loadVersionCatalogSources(file)
        thisLogger().info(
            "DependencyHelper Gradle enrichDependencies: file=${file.path}, gradleProperties=${findGradlePropertiesFile(file)?.path}, " +
                "catalogs=${catalogSources.entries.joinToString { "${it.key}->file=${it.value.file.path}, editable=${it.value.editableFile?.path}, gav=${it.value.sourceCoordinate}" }}",
        )
        val enriched = enrichGradleDependencies(buildText, gradleProperties, catalogSources, dependencies)
        thisLogger().info(
            "DependencyHelper Gradle enrichDependencies result: " +
                enriched.joinToString { "${it.declaredVersion ?: it.displayName}=>${it.displayName}:${it.version.ifBlank { "unknown" }}" },
        )
        return enriched
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
            val match = GRADLE_PROPERTY_LINE_REGEX(propertyName).find(document.text) ?: return false
            val valueStart = match.range.first + match.groupValues[1].length
            val valueRange = TextRangeMarker(valueStart, valueStart + match.groupValues[2].length)
            WriteCommandAction.runWriteCommandAction(project, Runnable {
                document.replaceString(valueRange.startOffset, valueRange.endOffset, newVersion)
                FileDocumentManager.getInstance().saveDocument(document)
            })
            refreshGradleProject(file)
            return true
        }

        val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
        val match = localGradlePropertyRegex(propertyName).find(document.text) ?: return false
        val valueRange = TextRangeMarker(match.range.first + match.groupValues[1].length, match.range.first + match.groupValues[1].length + match.groupValues[2].length)
        WriteCommandAction.runWriteCommandAction(project, Runnable {
            document.replaceString(valueRange.startOffset, valueRange.endOffset, newVersion)
            FileDocumentManager.getInstance().saveDocument(document)
        })
        refreshGradleProject(file)
        return true
    }

    private fun replaceManagedPluginVersion(file: VirtualFile, dependency: DependencyCoordinate, newVersion: String): Boolean {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
        val regexes = managedPluginVersionRegexes(dependency)
        if (regexes.isEmpty()) {
            return false
        }

        var updated = false
        WriteCommandAction.runWriteCommandAction(project, Runnable {
            var currentText = document.text
            regexes.forEach { regex ->
                val matches = regex.findAll(currentText).toList()
                if (matches.isEmpty()) {
                    return@forEach
                }
                matches.asReversed().forEach { match ->
                    val start = match.range.first + match.groupValues[1].length
                    val end = start + match.groupValues[2].length
                    document.replaceString(start, end, newVersion)
                    updated = true
                    currentText = document.text
                }
            }
            if (updated) {
                FileDocumentManager.getInstance().saveDocument(document)
            }
        })
        if (updated) {
            refreshGradleProject(file)
        }
        return updated
    }

    private fun replaceCatalogVersion(target: GradleCatalogUpgradeTarget, newVersion: String): Boolean {
        val document = FileDocumentManager.getInstance().getDocument(target.file) ?: return false
        val text = document.text
        val updated = when (target) {
            is GradleCatalogUpgradeTarget.VersionRef -> {
                val regex = Regex("""(?m)^(\s*${Regex.escape(target.reference)}\s*=\s*["'])([^"']+)(["'])""")
                val match = regex.find(text) ?: return false
                val start = match.range.first + match.groupValues[1].length
                val end = start + match.groupValues[2].length
                WriteCommandAction.runWriteCommandAction(project, Runnable {
                    document.replaceString(start, end, newVersion)
                    FileDocumentManager.getInstance().saveDocument(document)
                })
                true
            }
            is GradleCatalogUpgradeTarget.LibraryVersion -> {
                val lineRegex = Regex("""(?m)^(\s*${Regex.escape(target.alias)}\s*=\s*)(.+)$""")
                val lineMatch = lineRegex.find(text) ?: return false
                val body = lineMatch.groupValues[2]
                val relativeMatch = Regex("""version\s*=\s*["']([^"']+)["']""").find(body)
                    ?: return false
                val start = lineMatch.range.first + lineMatch.groupValues[1].length + relativeMatch.range.first + relativeMatch.groupValues[0].indexOf(relativeMatch.groupValues[1])
                val end = start + relativeMatch.groupValues[1].length
                WriteCommandAction.runWriteCommandAction(project, Runnable {
                    document.replaceString(start, end, newVersion)
                    FileDocumentManager.getInstance().saveDocument(document)
                })
                true
            }
            is GradleCatalogUpgradeTarget.PluginVersion -> {
                val lineRegex = Regex("""(?m)^(\s*${Regex.escape(target.alias)}\s*=\s*)(.+)$""")
                val lineMatch = lineRegex.find(text) ?: return false
                val body = lineMatch.groupValues[2]
                val relativeMatch = Regex("""version\s*=\s*["']([^"']+)["']""").find(body)
                    ?: return false
                val start = lineMatch.range.first + lineMatch.groupValues[1].length + relativeMatch.range.first + relativeMatch.groupValues[0].indexOf(relativeMatch.groupValues[1])
                val end = start + relativeMatch.groupValues[1].length
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

    private fun managedPluginVersionRegexes(dependency: DependencyCoordinate): List<Regex> = when {
        dependency.group == "io.ktor" -> listOf(
            Regex("""(id\("io\.ktor\.plugin"\)\s+version\s+["'])([^"']+)(["'])"""),
        )
        dependency.group == "org.jetbrains.kotlin" -> listOf(
            Regex("""(kotlin\("[^"]+"\)\s+version\s+["'])([^"']+)(["'])"""),
            Regex("""(id\("org\.jetbrains\.kotlin[^"]*"\)\s+version\s+["'])([^"']+)(["'])"""),
        )
        else -> emptyList()
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

    private fun findDefaultVersionCatalogFile(file: VirtualFile): VirtualFile? {
        var current: VirtualFile? = if (file.isDirectory) file else file.parent
        while (current != null) {
            current.findChild("gradle")?.findChild("libs.versions.toml")?.let { return it }
            current = current.parent
        }
        return null
    }

    private fun findGradleSettingsFile(file: VirtualFile): VirtualFile? {
        var current: VirtualFile? = if (file.isDirectory) file else file.parent
        while (current != null) {
            current.findChild("settings.gradle.kts")?.let { return it }
            current.findChild("settings.gradle")?.let { return it }
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
            parseSettingsCatalogMappings(settingsFile, settingsText).forEach { (name, catalogFileOrCoordinate) ->
                val catalogSource = resolveSettingsCatalogSource(settingsFile, name, catalogFileOrCoordinate) ?: return@forEach
                result[name] = catalogSource
            }
        }
        thisLogger().info(
            "DependencyHelper Gradle loadVersionCatalogSources: file=${file.path}, result=" +
                result.entries.joinToString { "${it.key}[file=${it.value.file.path}, editable=${it.value.editableFile?.path}, gav=${it.value.sourceCoordinate}]" },
        )
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

    private fun buildDeclaredDependencyRoots(file: VirtualFile): List<MavenDependencyNodeView> {
        val dependencies = enrichDependencies(file, DependencyFileScanner().scan(file, file.inputStream.bufferedReader().use { it.readText() }))
        if (dependencies.isEmpty()) {
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
        dependencies.forEach { dependency ->
            root.children += MavenDependencyNodeView(
                ownerProjectName = projectName,
                ownerProjectFile = file,
                groupId = dependency.group.orEmpty(),
                artifactId = dependency.name,
                version = dependency.version,
                scope = dependency.scope,
                packaging = null,
                path = rootPath + dependency.displayName,
                sourceDependency = dependency,
            )
        }
        return listOf(root)
    }

    private fun buildResolvedGradleNode(
        ownerFile: VirtualFile,
        ownerProjectName: String,
        node: DependencyNode,
        inheritedScope: String,
        parentPath: List<String>,
        directDependenciesByIdentity: Map<String, DependencyCoordinate>,
        directDependenciesByKey: Map<String, DependencyCoordinate>,
        sourceNodesById: MutableMap<Long, DependencyNode>,
        visitingIds: Set<Long>,
        depth: Int = 0,
    ): MavenDependencyNodeView {
        val currentVisitingIds = visitingIds + node.id
        if (node is ReferenceNode) {
            if (visitingIds.contains(node.id)) {
                return buildReferencePlaceholderNode(ownerFile, ownerProjectName, inheritedScope, parentPath, node.id)
            }
            val referencedNode = sourceNodesById[node.id]
                ?: return buildReferencePlaceholderNode(ownerFile, ownerProjectName, inheritedScope, parentPath, node.id)
            return buildResolvedGradleNode(
                ownerFile = ownerFile,
                ownerProjectName = ownerProjectName,
                node = referencedNode,
                inheritedScope = inheritedScope,
                parentPath = parentPath,
                directDependenciesByIdentity = directDependenciesByIdentity,
                directDependenciesByKey = directDependenciesByKey,
                sourceNodesById = sourceNodesById,
                visitingIds = currentVisitingIds,
                depth = depth,
            )
        }
        sourceNodesById[node.id] = node
        val view = when (node) {
            is ArtifactDependencyNode -> {
                val key = dependencyIdentity(node.group, node.module, node.version)
                val fallbackKey = dependencyKey(node.group, node.module)
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
                    sourceDependency = if (parentPath.size == 1) {
                        directDependenciesByIdentity[key] ?: directDependenciesByKey[fallbackKey]
                    } else {
                        null
                    },
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
            view.children += buildResolvedGradleNode(
                ownerFile = ownerFile,
                ownerProjectName = ownerProjectName,
                node = child,
                inheritedScope = inheritedScope,
                parentPath = view.path,
                directDependenciesByIdentity = directDependenciesByIdentity,
                directDependenciesByKey = directDependenciesByKey,
                sourceNodesById = sourceNodesById,
                visitingIds = currentVisitingIds,
                depth = depth + 1,
            )
        }
        return view
    }

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

    private fun buildReferencePlaceholderNode(
        ownerFile: VirtualFile,
        ownerProjectName: String,
        scope: String,
        parentPath: List<String>,
        referenceId: Long,
    ): MavenDependencyNodeView {
        return MavenDependencyNodeView(
            ownerProjectName = ownerProjectName,
            ownerProjectFile = ownerFile,
            groupId = "",
            artifactId = "*",
            version = "",
            scope = scope,
            packaging = null,
            path = parentPath + "*:$referenceId",
            sourceDependency = null,
        )
    }

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

    private fun dependencyIdentity(group: String?, name: String, version: String): String {
        return "${group.orEmpty()}:$name:$version"
    }

    private fun dependencyKey(group: String?, name: String): String {
        return "${group.orEmpty()}:$name"
    }

    private fun directDependencyKey(
        node: MavenDependencyNodeView,
        directDependenciesByIdentity: Map<String, DependencyCoordinate>,
        directDependenciesByKey: Map<String, DependencyCoordinate>,
    ): String? {
        val identity = dependencyIdentity(node.groupId, node.artifactId, node.version)
        if (directDependenciesByIdentity.containsKey(identity)) {
            return dependencyKey(node.groupId, node.artifactId)
        }
        val key = dependencyKey(node.groupId, node.artifactId)
        return if (directDependenciesByKey.containsKey(key)) key else null
    }

    private fun mergeInto(targetChildren: MutableList<MavenDependencyNodeView>, source: MavenDependencyNodeView) {
        val existing = targetChildren.firstOrNull { sameDependencyTreeNode(it, source) }
        if (existing == null) {
            targetChildren += cloneDependencyTreeNode(source)
            return
        }
        source.children.forEach { child ->
            mergeInto(existing.children, child)
        }
    }

    private fun cloneDependencyTreeNode(node: MavenDependencyNodeView): MavenDependencyNodeView {
        return node.copy(
            children = node.children
                .take(MAX_GRADLE_CHILDREN_PER_NODE)
                .mapTo(mutableListOf()) { child -> cloneDependencyTreeNode(child) },
        )
    }

    private fun sameDependencyTreeNode(left: MavenDependencyNodeView, right: MavenDependencyNodeView): Boolean {
        return left.groupId == right.groupId &&
            left.artifactId == right.artifactId &&
            left.version == right.version &&
            left.scope == right.scope
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

    private fun findGradleModuleDataByProjectPath(projectPath: String): GradleModuleData? {
        val modules = ModuleManager.getInstance(project).modules
        val gradleModules = modules.filter { ExternalSystemApiUtil.isExternalSystemAwareModule(GradleConstants.SYSTEM_ID.id, it) }
        thisLogger().info(
            "DependencyHelper Gradle module scan: projectPath=$projectPath, modules=" +
                gradleModules.joinToString { module ->
                    val externalProjectPath = ExternalSystemApiUtil.getExternalProjectPath(module)
                    val rootProjectPath = ExternalSystemApiUtil.getExternalRootProjectPath(module)
                    val externalProjectId = ExternalSystemApiUtil.getExternalProjectId(module)
                    val moduleType = ExternalSystemApiUtil.getExternalModuleType(module)
                    "${module.name}[project=$externalProjectPath,root=$rootProjectPath,id=$externalProjectId,type=$moduleType]"
                },
        )
        val candidates = gradleModules
            .filter { module ->
                val externalProjectPath = ExternalSystemApiUtil.getExternalProjectPath(module)
                val rootProjectPath = ExternalSystemApiUtil.getExternalRootProjectPath(module)
                externalProjectPath == projectPath || rootProjectPath == projectPath
            }
            .sortedByDescending { ExternalSystemApiUtil.getExternalModuleType(it)?.contains("sourceSet", ignoreCase = true) != true }
        thisLogger().info(
            "DependencyHelper Gradle module candidates: projectPath=$projectPath, candidates=" +
                candidates.joinToString { module ->
                    val moduleType = ExternalSystemApiUtil.getExternalModuleType(module)
                    val externalProjectId = ExternalSystemApiUtil.getExternalProjectId(module)
                    val resolved = GradleModuleDataIndex.findGradleModuleData(module)
                    "${module.name}[id=$externalProjectId,type=$moduleType,resolved=${resolved != null}]"
                },
        )
        return candidates.firstNotNullOfOrNull { module ->
            GradleModuleDataIndex.findGradleModuleData(module)
        }
    }

    private fun findGradleModuleDataInStorage(projectPath: String): GradleModuleData? {
        val storage = ExternalProjectsDataStorage.getInstance(project)
        val moduleNodes = storage.list(GradleConstants.SYSTEM_ID)
            .mapNotNull { it.externalProjectStructure }
            .flatMap { ExternalSystemApiUtil.getChildren(it, ProjectKeys.MODULE) }
            .filter { it.data.owner == GradleConstants.SYSTEM_ID }
        thisLogger().info(
            "DependencyHelper Gradle storage module nodes: projectPath=$projectPath, nodes=" +
                moduleNodes.joinToString { node ->
                    val data: ModuleData = node.data
                    val gradleData = runCatching { GradleModuleData(node) }.getOrNull()
                    "${data.id}[linked=${data.linkedExternalProjectPath},module=${data.moduleName},identity=${gradleData?.gradleIdentityPathOrNull},owner=${data.owner}]"
                },
        )
        val matchedNode = moduleNodes.firstOrNull { it.data.linkedExternalProjectPath == projectPath }
            ?: moduleNodes.firstOrNull { node ->
                val linkedPath = node.data.linkedExternalProjectPath
                linkedPath.startsWith(projectPath) || projectPath.startsWith(linkedPath)
            }
        return matchedNode?.let(::GradleModuleData)
    }

    private fun resolveAnalysisFile(file: VirtualFile): VirtualFile {
        if (file.name !in GRADLE_SETTINGS_FILE_NAMES) {
            return file
        }
        val root = findGradleProjectRoot(file) ?: return file
        return root.findChild("build.gradle.kts")
            ?: root.findChild("build.gradle")
            ?: file
    }

    private companion object {
        val GRADLE_SYSTEM_ID = ProjectSystemId("GRADLE")
        val GRADLE_FILE_NAMES = setOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
        val GRADLE_SETTINGS_FILE_NAMES = setOf("settings.gradle", "settings.gradle.kts")
        const val MAX_GRADLE_TREE_DEPTH = 4
        const val MAX_GRADLE_CHILDREN_PER_NODE = 50
        fun GRADLE_PROPERTY_LINE_REGEX(name: String) = Regex("""(?m)^(\s*${Regex.escape(name)}\s*=\s*)([^\r\n#]+)""")
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
        if (dependency.ecosystem != org.knifefish.dependency.helper.model.Ecosystem.GRADLE) {
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

    Regex("""(?m)^\s*val\s+([A-Za-z0-9_.-]+)\s*=\s*["']([^"']+)["']""")
        .findAll(buildText)
        .forEach { match -> properties[match.groupValues[1]] = match.groupValues[2] }

    Regex("""(?m)^\s*extra\[\s*["']([A-Za-z0-9_.-]+)["']\s*]\s*=\s*["']([^"']+)["']""")
        .findAll(buildText)
        .forEach { match -> properties[match.groupValues[1]] = match.groupValues[2] }

    val kotlinPluginVersion =
        Regex("""kotlin\("([^"]+)"\)\s+version\s+["']([^"']+)["']""").find(buildText)?.groupValues?.get(2)
            ?: Regex("""id\("org\.jetbrains\.kotlin[^"]*"\)\s+version\s+["']([^"']+)["']""").find(buildText)?.groupValues?.get(1)

    val ktorPluginVersion =
        Regex("""id\("io\.ktor\.plugin"\)\s+version\s+["']([^"']+)["']""").find(buildText)?.groupValues?.get(1)

    val catalogs = versionCatalogs.mapValues { (_, source) -> parseVersionCatalog(source.text) }

    return GradleVersionContext(
        properties = properties,
        catalogs = catalogs,
        kotlinPluginVersion = kotlinPluginVersion,
        ktorPluginVersion = ktorPluginVersion,
    )
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
    return Regex("""^\$\{?([A-Za-z0-9_.-]+)}?$""").matchEntire(versionExpression.trim())?.groupValues?.get(1)
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
    return GradleVersionCatalog(
        versions = parseVersionCatalogVersions(versionCatalogText),
        libraries = parseVersionCatalogLibraries(versionCatalogText),
        bundles = parseVersionCatalogBundles(versionCatalogText),
        plugins = parseVersionCatalogPlugins(versionCatalogText),
    )
}

private fun parseVersionCatalogVersions(versionCatalogText: String?): Map<String, String> {
    val section = versionCatalogSection(versionCatalogText, "versions") ?: return emptyMap()
    return Regex("""(?m)^\s*([A-Za-z0-9_.-]+)\s*=\s*["']([^"']+)["']\s*$""")
        .findAll(section)
        .associate { normalizeCatalogAlias(it.groupValues[1]) to it.groupValues[2].trim() }
}

private fun parseVersionCatalogLibraries(versionCatalogText: String?): Map<String, GradleCatalogLibrary> {
    val section = versionCatalogSection(versionCatalogText, "libraries") ?: return emptyMap()
    val result = mutableMapOf<String, GradleCatalogLibrary>()
    Regex("""(?m)^\s*([A-Za-z0-9_.-]+)\s*=\s*["']([^:"']+):([^:"']+):([^"']+)["']\s*$""")
        .findAll(section)
        .forEach { match ->
            result[normalizeCatalogAlias(match.groupValues[1])] = GradleCatalogLibrary(
                group = match.groupValues[2].trim(),
                name = match.groupValues[3].trim(),
                version = match.groupValues[4].trim(),
                versionRef = null,
            )
        }
    Regex("""(?m)^\s*([A-Za-z0-9_.-]+)\s*=\s*\{([^}]*)}\s*$""")
        .findAll(section)
        .forEach { match ->
            val alias = normalizeCatalogAlias(match.groupValues[1])
            val body = match.groupValues[2]
            val module = Regex("""module\s*=\s*["']([^:"']+):([^"']+)["']""").find(body)
            val group = Regex("""group\s*=\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)?.trim()
            val name = Regex("""name\s*=\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)?.trim()
            val version = Regex("""version\s*=\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)?.trim()
            val versionRef = Regex("""version\.ref\s*=\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)?.trim()
                ?.let(::normalizeCatalogAlias)
            val resolvedGroup = module?.groupValues?.get(1)?.trim() ?: group
            val resolvedName = module?.groupValues?.get(2)?.trim() ?: name
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

private fun parseVersionCatalogBundles(versionCatalogText: String?): Map<String, List<String>> {
    val section = versionCatalogSection(versionCatalogText, "bundles") ?: return emptyMap()
    return Regex("""(?m)^\s*([A-Za-z0-9_.-]+)\s*=\s*\[(.*?)]\s*$""")
        .findAll(section)
        .associate { match ->
            val alias = normalizeCatalogAlias(match.groupValues[1])
            val entries = Regex("""["']([^"']+)["']""")
                .findAll(match.groupValues[2])
                .map { normalizeCatalogAlias(it.groupValues[1]) }
                .toList()
            alias to entries
        }
}

private fun parseVersionCatalogPlugins(versionCatalogText: String?): Map<String, GradleCatalogPlugin> {
    val section = versionCatalogSection(versionCatalogText, "plugins") ?: return emptyMap()
    val result = mutableMapOf<String, GradleCatalogPlugin>()
    Regex("""(?m)^\s*([A-Za-z0-9_.-]+)\s*=\s*\{([^}]*)}\s*$""")
        .findAll(section)
        .forEach { match ->
            val alias = normalizeCatalogAlias(match.groupValues[1])
            val body = match.groupValues[2]
            val id = Regex("""id\s*=\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)?.trim() ?: return@forEach
            val version = Regex("""version\s*=\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)?.trim()
            val versionRef = Regex("""version\.ref\s*=\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)?.trim()
                ?.let(::normalizeCatalogAlias)
            result[alias] = GradleCatalogPlugin(
                id = id,
                version = version,
                versionRef = versionRef,
            )
        }
    return result
}

private fun versionCatalogSection(versionCatalogText: String?, name: String): String? {
    val text = versionCatalogText ?: return null
    return Regex("""(?ms)^\[$name]\s*(.*?)(^\[[^]]+]\s*|\z)""").find(text)?.groupValues?.get(1)
}

private fun parseSettingsCatalogMappings(settingsFile: VirtualFile, settingsText: String): Map<String, String> {
    val baseDir = Paths.get(settingsFile.parent.path)
    val result = linkedMapOf<String, String>()
    val directCreateRegex = Regex("""(?m)(?:create|maybeCreate)\(\s*["']([^"']+)["']\s*\)\s*\.from\(\s*(?:files\(\s*["']([^"']+\.toml)["']\s*\)|["']([^"']+:[^"']+:[^"']+)["'])\s*\)""")
    directCreateRegex.findAll(settingsText).forEach { match ->
        val name = match.groupValues[1].trim()
        val path = match.groupValues[2].trim().ifBlank { null }
        val coordinate = match.groupValues[3].trim().ifBlank { null }
        when {
            path != null -> result[name] = baseDir.resolve(path).normalize().toString()
            coordinate != null -> result[name] = coordinate
        }
    }
    val createRegex = Regex("""(?ms)(?:create|maybeCreate)\(\s*["']([^"']+)["']\s*\)\s*\{(.*?)}""")
    createRegex.findAll(settingsText).forEach { match ->
        val name = match.groupValues[1].trim()
        if (result.containsKey(name)) {
            return@forEach
        }
        val body = match.groupValues[2]
        val path = Regex("""from\(\s*files\(\s*["']([^"']+\.toml)["']\s*\)\s*\)""")
            .find(body)
            ?.groupValues
            ?.get(1)
            ?.trim()
        val coordinate = Regex("""from\(\s*["']([^"']+:[^"']+:[^"']+)["']\s*\)""")
            .find(body)
            ?.groupValues
            ?.get(1)
            ?.trim()
        when {
            path != null -> result[name] = baseDir.resolve(path).normalize().toString()
            coordinate != null -> result[name] = coordinate
        }
    }
    return result
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

private fun localGradlePropertyRegex(propertyName: String): Regex {
    return Regex(
        """(?m)^(\s*(?:val\s+${Regex.escape(propertyName)}\s*=\s*|extra\[\s*["']${Regex.escape(propertyName)}["']\s*]\s*=\s*["']))([^"']+)(["'])""",
    )
}
