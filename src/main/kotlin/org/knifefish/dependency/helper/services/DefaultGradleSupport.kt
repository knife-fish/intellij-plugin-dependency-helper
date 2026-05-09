package org.knifefish.dependency.helper.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.TextRangeMarker
import java.nio.file.Path

class DefaultGradleSupport(private val project: Project) : GradleSupport {

    override fun enrichDependencies(file: VirtualFile, dependencies: List<DependencyCoordinate>): List<DependencyCoordinate> {
        if (!isGradleFile(file)) {
            return dependencies
        }
        val buildText = file.inputStream.bufferedReader().use { it.readText() }
        val gradleProperties = findGradlePropertiesFile(file)?.inputStream?.bufferedReader()?.use { it.readText() }
        return enrichGradleDependencies(buildText, gradleProperties, dependencies)
    }

    override fun upgradeDependency(dependency: DependencyCoordinate, newVersion: String): Boolean {
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

    private companion object {
        val GRADLE_SYSTEM_ID = ProjectSystemId("GRADLE")
        val GRADLE_FILE_NAMES = setOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
        val GRADLE_SETTINGS_FILE_NAMES = setOf("settings.gradle", "settings.gradle.kts")
        fun GRADLE_PROPERTY_LINE_REGEX(name: String) = Regex("""(?m)^(\s*${Regex.escape(name)}\s*=\s*)([^\r\n#]+)""")
    }
}

internal fun enrichGradleDependencies(
    buildText: String,
    gradlePropertiesText: String?,
    dependencies: List<DependencyCoordinate>,
): List<DependencyCoordinate> {
    val context = parseGradleVersionContext(buildText, gradlePropertiesText)
    return dependencies.map { dependency ->
        if (dependency.ecosystem != org.knifefish.dependency.helper.model.Ecosystem.GRADLE) {
            dependency
        } else {
            val resolvedVersion = resolveGradleDependencyVersion(context, dependency)
            if (resolvedVersion.isNullOrBlank()) {
                dependency
            } else {
                dependency.copy(
                    version = resolvedVersion,
                    declaredVersion = dependency.declaredVersion,
                )
            }
        }
    }
}

internal data class GradleVersionContext(
    val properties: Map<String, String>,
    val kotlinPluginVersion: String?,
    val ktorPluginVersion: String?,
)

internal fun parseGradleVersionContext(buildText: String, gradlePropertiesText: String?): GradleVersionContext {
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

    return GradleVersionContext(
        properties = properties,
        kotlinPluginVersion = kotlinPluginVersion,
        ktorPluginVersion = ktorPluginVersion,
    )
}

internal fun resolveGradleDependencyVersion(
    context: GradleVersionContext,
    dependency: DependencyCoordinate,
): String? {
    val declared = dependency.declaredVersion
    if (!declared.isNullOrBlank()) {
        val propertyName = gradlePropertyName(declared)
        if (propertyName != null) {
            return context.properties[propertyName] ?: dependency.version.takeIf { it.isNotBlank() }
        }
        return dependency.version.takeIf { it.isNotBlank() }
    }
    return when {
        dependency.group == "io.ktor" -> context.ktorPluginVersion
        dependency.group == "org.jetbrains.kotlin" -> context.kotlinPluginVersion
        else -> dependency.version.takeIf { it.isNotBlank() }
    }
}

internal fun gradlePropertyName(versionExpression: String): String? {
    return Regex("""^\$\{?([A-Za-z0-9_.-]+)}?$""").matchEntire(versionExpression.trim())?.groupValues?.get(1)
}

private fun localGradlePropertyRegex(propertyName: String): Regex {
    return Regex(
        """(?m)^(\s*(?:val\s+${Regex.escape(propertyName)}\s*=\s*|extra\[\s*["']${Regex.escape(propertyName)}["']\s*]\s*=\s*["']))([^"']+)(["'])""",
    )
}
