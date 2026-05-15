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

        // Gradle 解析后的依赖树不一定保留“直接声明依赖”的边界，
        // 这里先从脚本文本提取直接依赖，再把解析树节点映射回这些直接依赖。
        val directDependencies = declaredDependencies(file)
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
        val resolvedDirectNodes = mutableListOf<MavenDependencyNodeView>()
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
                resolvedDirectNodes += occurrence
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
        resolvedDirectNodes.forEach { occurrence ->
            val directKey = dependencyKey(occurrence.groupId, occurrence.artifactId)
            if (directDependenciesByKey.containsKey(directKey)) {
                return@forEach
            }
            mergeInto(root.children, occurrence)
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

    override fun declaredDependencies(file: VirtualFile): List<DependencyCoordinate> {
        if (!isGradleFile(file)) {
            return emptyList()
        }
        return enrichDependencies(file, collectDeclaredGradleDependencies(file, resolveAnalysisFile(file)))
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

    /** Gradle 解析模型不可用时，基于脚本文本构建声明依赖树。 */
    private fun buildDeclaredDependencyRoots(file: VirtualFile): List<MavenDependencyNodeView> {
        // Gradle 模型不可用时的兜底：直接展示脚本文本中的声明依赖。
        // 关键变量：从脚本提取出的声明依赖列表。
        val dependencies = declaredDependencies(file)
        if (dependencies.isEmpty()) {
            return emptyList()
        }
        // 关键变量：分析根节点显示名称。
        val projectName = file.name
        // 关键变量：树路径起点（当前文件路径）。
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

    /** 轻量提取 Gradle 脚本中的直接依赖声明，用于源码定位和 editor inlay。 */
    private fun collectDeclaredGradleDependencies(ownerFile: VirtualFile, sourceFile: VirtualFile): List<DependencyCoordinate> {
        if (!isGradleFile(sourceFile)) return emptyList()
        val text = runCatching { sourceFile.inputStream.bufferedReader().use { it.readText() } }.getOrNull() ?: return emptyList()
        val dependencies = mutableListOf<DependencyCoordinate>()
        var lineStart = 0
        var lineNumber = 1
        while (lineStart < text.length) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val line = text.substring(lineStart, lineEnd)
            dependencyFromGradleLine(ownerFile, line, lineStart, lineEnd, lineNumber)?.let(dependencies::add)
            lineStart = lineEnd + 1
            lineNumber++
        }
        return dependencies.distinctBy { "${it.scope}:${it.declaredVersion}:${it.inspectionRange.startOffset}" }
    }

    private fun dependencyFromGradleLine(
        ownerFile: VirtualFile,
        line: String,
        lineStart: Int,
        lineEnd: Int,
        lineNumber: Int,
    ): DependencyCoordinate? {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
            return null
        }
        val scope = trimmed.takeWhile { it.isLetterOrDigit() || it == '_' || it == '.' }
        if (scope.isBlank()) {
            return null
        }
        val rest = trimmed.drop(scope.length).trimStart()
        val declaration = line.trim()
        val inspectionRange = TextRangeMarker(lineStart, lineEnd)
        val coordinate = firstQuotedValue(rest)?.takeIf { it.count { ch -> ch == ':' } >= 1 }
        if (coordinate != null) {
            val parts = coordinate.split(':')
            if (parts.size >= 2) {
                val version = parts.getOrNull(2).orEmpty()
                val displayOffset = if (version.isNotBlank()) {
                    line.lastIndexOf(version).takeIf { it >= 0 }?.let { lineStart + it }
                } else {
                    line.lastIndexOf(parts[1]).takeIf { it >= 0 }?.let { lineStart + it + parts[1].length }
                } ?: lineEnd
                val range = if (version.isNotBlank()) TextRangeMarker(displayOffset, displayOffset + version.length) else null
                return DependencyCoordinate(
                    ecosystem = org.knifefish.dependency.helper.model.Ecosystem.GRADLE,
                    group = parts[0],
                    name = parts[1],
                    version = version,
                    declaredVersion = version.takeIf { it.isNotBlank() },
                    scope = scope,
                    file = ownerFile,
                    declarationText = declaration,
                    lineNumber = lineNumber,
                    versionRange = range,
                    displayRange = range ?: TextRangeMarker(displayOffset, displayOffset),
                    inspectionRange = inspectionRange,
                )
            }
        }

        val accessor = GRADLE_CATALOG_ACCESSOR_PATTERN.find(rest)?.groupValues?.get(1) ?: return null
        val accessorStart = line.indexOf(accessor).takeIf { it >= 0 } ?: return null
        val displayOffset = lineStart + accessorStart + accessor.length
        return DependencyCoordinate(
            ecosystem = org.knifefish.dependency.helper.model.Ecosystem.GRADLE,
            group = null,
            name = accessor.substringAfterLast('.'),
            version = "",
            declaredVersion = accessor,
            scope = scope,
            file = ownerFile,
            declarationText = declaration,
            lineNumber = lineNumber,
            versionRange = null,
            displayRange = TextRangeMarker(displayOffset, displayOffset),
            inspectionRange = inspectionRange,
        )
    }

    private fun firstQuotedValue(text: String): String? {
        val quote = text.indexOfAny(charArrayOf('"', '\''))
        if (quote < 0) return null
        val end = text.indexOf(text[quote], quote + 1)
        if (end <= quote) return null
        return text.substring(quote + 1, end)
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
        val GRADLE_CATALOG_ACCESSOR_PATTERN = Regex("""(?:\w+\(\s*)?([A-Za-z][A-Za-z0-9_]*\.[A-Za-z0-9_.-]+)""")
        const val MAX_GRADLE_TREE_DEPTH = 4
        const val MAX_GRADLE_CHILDREN_PER_NODE = 50
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

private fun parseSettingsCatalogMappings(settingsFile: VirtualFile, settingsText: String): Map<String, String> {
    val baseDir = Paths.get(settingsFile.parent.path)
    val result = linkedMapOf<String, String>()
    val lines = settingsText.lines()
    lines.forEachIndexed { index, raw ->
        val line = raw.trim()
        if (!(line.startsWith("create(") || line.startsWith("maybeCreate("))) return@forEachIndexed
        val name = betweenFirstQuotes(line) ?: return@forEachIndexed
        val fromPart = line.substringAfter(".from(", "").substringBeforeLast(")", "")
        if (fromPart.isNotBlank()) {
            parseCatalogFromClause(fromPart, baseDir)?.let { result[name] = it }
            return@forEachIndexed
        }
        var j = index + 1
        while (j < lines.size && !lines[j].contains("}")) {
            val candidate = lines[j].trim()
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
