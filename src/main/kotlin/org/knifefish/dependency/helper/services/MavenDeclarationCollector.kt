package org.knifefish.dependency.helper.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.model.MavenDomDependency
import org.jetbrains.idea.maven.dom.model.MavenDomPlugin
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.Ecosystem
import org.knifefish.dependency.helper.model.TextRangeMarker

internal object MavenDeclarationCollector {

    const val PLUGIN_SCOPE = "plugin"
    const val PLUGIN_MANAGEMENT_SCOPE = "pluginManagement"
    private const val DEFAULT_PLUGIN_GROUP = "org.apache.maven.plugins"

    fun collect(project: Project, file: VirtualFile, includePlugins: Boolean): List<DependencyCoordinate> {
        val context = context(project, file) ?: return emptyList()
        return buildList {
            addAll(collectDependencies(context))
            if (includePlugins) {
                addAll(collectPlugins(context))
            }
        }
    }

    fun collectDependencies(project: Project, file: VirtualFile): List<DependencyCoordinate> {
        val context = context(project, file) ?: return emptyList()
        return collectDependencies(context)
    }

    fun collectPlugins(project: Project, file: VirtualFile): List<DependencyCoordinate> {
        val context = context(project, file) ?: return emptyList()
        return collectPlugins(context)
    }

    private fun context(project: Project, file: VirtualFile): MavenDeclarationContext? {
        val model = MavenDomUtil.getMavenDomProjectModel(project, file) ?: return null
        val rootTag = model.xmlTag ?: return null
        val xmlFile = rootTag.containingFile as? XmlFile
            ?: PsiManager.getInstance(project).findFile(file) as? XmlFile
            ?: return null
        val mavenProject = MavenProjectsManager.getInstance(project).findProject(file)
        return MavenDeclarationContext(
            file = file,
            model = model,
            source = xmlFile.text.orEmpty(),
            resolver = model.propertyResolver(rootTag),
            mavenProject = mavenProject,
        )
    }

    private fun collectDependencies(context: MavenDeclarationContext): List<DependencyCoordinate> {
        return context.model.dependencies.dependencies.mapNotNull { dependency ->
            dependency.toCoordinate(context.file, context.source)
        }
    }

    private fun collectPlugins(context: MavenDeclarationContext): List<DependencyCoordinate> {
        val resolvedPluginVersions = context.mavenProject.resolvedPluginVersions()
        val managedPluginVersions = context.model.build.pluginManagement.plugins.plugins
            .mapNotNull { plugin -> plugin.managedVersion(context.resolver) }
            .toMap()
        val plugins = mutableListOf<DependencyCoordinate>()
        context.model.build.let { build ->
            build.plugins.plugins
                .mapNotNullTo(plugins) {
                    it.toCoordinate(context, resolvedPluginVersions + managedPluginVersions, PLUGIN_SCOPE)
                }
            build.pluginManagement.plugins.plugins
                .mapNotNullTo(plugins) {
                    it.toCoordinate(context, resolvedPluginVersions, PLUGIN_MANAGEMENT_SCOPE)
                }
        }
        return plugins.distinctBy { "${it.group}:${it.name}:${it.versionRange?.startOffset}" }
    }

    private fun MavenDomDependency.toCoordinate(file: VirtualFile, source: String): DependencyCoordinate? {
        val group = groupId.stringValue?.trim()
        val artifact = artifactId.stringValue?.trim()
        if (group.isNullOrBlank() || artifact.isNullOrBlank()) {
            return null
        }

        val declaredVersion = version.stringValue?.trim()
        val versionRange = version.xmlTag?.value?.textRange?.let { TextRangeMarker(it.startOffset, it.endOffset) }
        val fallbackOffset = artifactId.xmlTag?.value?.textRange?.endOffset ?: xmlTag?.textRange?.endOffset ?: 0
        val displayRange = versionRange ?: TextRangeMarker(fallbackOffset, fallbackOffset)
        val declarationRange = xmlTag?.textRange?.let { TextRangeMarker(it.startOffset, it.endOffset) } ?: displayRange
        return DependencyCoordinate(
            ecosystem = Ecosystem.MAVEN,
            group = group,
            name = artifact,
            version = declaredVersion.orEmpty(),
            declaredVersion = declaredVersion,
            scope = scope.stringValue?.trim(),
            file = file,
            declarationText = xmlTag?.text?.trim().orEmpty(),
            lineNumber = source.take(displayRange.startOffset).count { it == '\n' } + 1,
            versionRange = versionRange,
            displayRange = displayRange,
            inspectionRange = declarationRange,
        )
    }

    private fun MavenDomPlugin.toCoordinate(
        context: MavenDeclarationContext,
        resolvedPluginVersions: Map<String, String>,
        scope: String,
    ): DependencyCoordinate? {
        val artifact = context.resolver.resolve(artifactId.stringValue?.trim())
            .takeUnless { it.isNullOrBlank() }
            ?: return null
        val group = context.resolver.resolve(groupId.stringValue?.trim())
            .takeUnless { it.isNullOrBlank() }
            ?: DEFAULT_PLUGIN_GROUP
        val versionTag = version.xmlTag
        val declaredVersion = versionTag?.value?.text?.trim()?.takeIf { it.isNotBlank() }
        val key = "$group:$artifact"
        val resolvedVersion = declaredVersion
            ?.let(context.resolver::resolve)
            ?.takeIf { it.isNotBlank() }
            ?: resolvedPluginVersions[key]
            ?: return null
        val versionRange = versionTag?.value?.textRange?.let { TextRangeMarker(it.startOffset, it.endOffset) }
        val fallbackOffset = artifactId.xmlTag?.value?.textRange?.endOffset ?: xmlTag?.textRange?.endOffset ?: 0
        val displayRange = versionRange ?: TextRangeMarker(fallbackOffset, fallbackOffset)
        val declarationRange = xmlTag?.textRange?.let { TextRangeMarker(it.startOffset, it.endOffset) } ?: displayRange
        return DependencyCoordinate(
            ecosystem = Ecosystem.MAVEN,
            group = group,
            name = artifact,
            version = resolvedVersion,
            declaredVersion = declaredVersion ?: resolvedVersion,
            scope = scope,
            file = context.file,
            declarationText = xmlTag?.text?.trim().orEmpty(),
            lineNumber = context.source.take(displayRange.startOffset).count { it == '\n' } + 1,
            versionRange = versionRange,
            displayRange = displayRange,
            inspectionRange = declarationRange,
        )
    }

    private fun MavenDomPlugin.managedVersion(resolver: PomPropertyResolver): Pair<String, String>? {
        val artifact = resolver.resolve(artifactId.stringValue?.trim())
            .takeUnless { it.isNullOrBlank() }
            ?: return null
        val group = resolver.resolve(groupId.stringValue?.trim())
            .takeUnless { it.isNullOrBlank() }
            ?: DEFAULT_PLUGIN_GROUP
        val declaredVersion = version.xmlTag?.value?.text?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val resolvedVersion = resolver.resolve(declaredVersion)?.takeIf { it.isNotBlank() } ?: declaredVersion
        return "$group:$artifact" to resolvedVersion
    }

    private fun MavenProject?.resolvedPluginVersions(): Map<String, String> {
        if (this == null) {
            return emptyMap()
        }
        return (plugins + declaredPlugins)
            .mapNotNull { plugin ->
                val version = plugin.version?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                "${plugin.groupId}:${plugin.artifactId}" to version
            }
            .toMap()
    }

    private fun MavenDomProjectModel.propertyResolver(rootTag: XmlTag): PomPropertyResolver {
        val parent = mavenParent
        val groupId = parent.groupId.stringValue?.trim()
        val artifactId = parent.artifactId.stringValue?.trim()
        val version = parent.version.stringValue?.trim()
        val rawParent = if (groupId.isNullOrBlank() || artifactId.isNullOrBlank() || version.isNullOrBlank()) {
            null
        } else {
            PomReference(groupId, artifactId, version)
        }
        return PomPropertyResolver.from(rootTag, rawParent)
    }

    private data class MavenDeclarationContext(
        val file: VirtualFile,
        val model: MavenDomProjectModel,
        val source: String,
        val resolver: PomPropertyResolver,
        val mavenProject: MavenProject?,
    )
}
