package org.knifefish.dependency.helper.services.external

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.Ecosystem
import org.knifefish.dependency.helper.model.TextRangeMarker
import org.rust.cargo.project.model.CargoProject
import org.rust.cargo.project.model.CargoProjectsService
import org.rust.cargo.project.workspace.CargoWorkspace
import java.nio.file.Paths

/** Cargo 外部系统适配器，负责 Rust 依赖的读取、刷新与版本升级。 */
internal class CargoExternalSystemAdapter : ExternalDependencySystem {
    /** 当前适配器生态。 */
    override val ecosystem: Ecosystem = Ecosystem.RUST

    /** 仅处理 Cargo.toml。 */
    override fun supports(file: VirtualFile): Boolean = file.name == "Cargo.toml"

    /** 从 Cargo 项目模型读取依赖并映射为统一依赖坐标。 */
    override fun scan(project: Project, file: VirtualFile): List<DependencyCoordinate> {
        val source = file.inputStream.bufferedReader().use { it.readText() }
        val resolved = resolvedDirectDependencies(project, file)
        return declaredCargoDependencies(file, source, resolved)
    }

    /** 刷新 Rust/Cargo 项目模型。 */
    override fun refresh(project: Project, file: VirtualFile, afterRefresh: (() -> Unit)?): Boolean {
        val cargoProjectsService = project.getService(CargoProjectsService::class.java) ?: return false
        cargoProjectsService.refreshAllProjects(false).whenComplete { _, _ ->
            afterRefresh?.invoke()
        }
        return true
    }

    /** 在 Cargo.toml 中定位并替换依赖版本。 */
    override fun upgrade(project: Project, dependency: DependencyCoordinate, newVersion: String): Boolean {
        if (dependency.file.name != "Cargo.toml") return false
        if (dependency.ecosystem != Ecosystem.RUST) return false
        val document = FileDocumentManager.getInstance().getDocument(dependency.file) ?: return false
        val target = findCargoDependencyLocation(document.text, dependency.name) ?: return false
        if (target.version == newVersion) return true
        val versionRange = target.versionRange ?: return false
        val start = versionRange.startOffset
        val end = versionRange.endOffset
        if (start < 0 || end > document.textLength || start > end) return false
        WriteCommandAction.runWriteCommandAction(project, Runnable {
            document.replaceString(start, end, newVersion)
            FileDocumentManager.getInstance().saveDocument(document)
        })
        return true
    }

    /** 根据 manifest 文件路径在 workspace 中定位对应 package。 */
    private fun findPackageForManifest(cargoProject: CargoProject, file: VirtualFile): CargoWorkspace.Package? {
        val workspace = cargoProject.workspace ?: return null
        // 关键变量：规范化后的目标 manifest 路径。
        val manifestPath = Paths.get(file.path).normalize()
        return workspace.packages.firstOrNull { pkg ->
            pkg.contentRootPath.resolve("Cargo.toml").normalize() == manifestPath
        }
    }

    private fun resolvedDirectDependencies(project: Project, file: VirtualFile): Map<String, ResolvedCargoDependency> {
        val cargoProjectsService = project.getService(CargoProjectsService::class.java) ?: return emptyMap()
        val targetPackage = cargoProjectsService.findPackageForFile(file)
            ?: cargoProjectsService.findProjectForFile(file)?.let { findPackageForManifest(it, file) }
            ?: cargoProjectsService.allProjects.firstNotNullOfOrNull { findPackageForManifest(it, file) }
            ?: return emptyMap()
        return targetPackage.dependencies.associate { dep ->
            dep.name to ResolvedCargoDependency(
                version = dep.pkg.version.takeIf { it.isNotBlank() },
                scope = dep.depKinds.firstOrNull()?.kind?.name ?: CargoWorkspace.DepKind.Unclassified.name,
            )
        }
    }

    private fun declaredCargoDependencies(
        file: VirtualFile,
        source: String,
        resolved: Map<String, ResolvedCargoDependency>,
    ): List<DependencyCoordinate> {
        val declarations = cargoDependencyLocations(source)
        return declarations.mapNotNull { location ->
            val resolvedDependency = resolved[location.manifestName] ?: resolved[location.packageName]
            val version = location.version ?: resolvedDependency?.version ?: return@mapNotNull null
            val displayRange = location.versionRange ?: location.declarationRange
            DependencyCoordinate(
                ecosystem = Ecosystem.RUST,
                group = null,
                name = location.packageName,
                version = location.version ?: version,
                declaredVersion = location.version,
                scope = location.scope ?: resolvedDependency?.scope,
                file = file,
                declarationText = source.substring(location.declarationRange.startOffset, location.declarationRange.endOffset).trim(),
                lineNumber = source.take(displayRange.startOffset).count { it == '\n' } + 1,
                versionRange = location.versionRange,
                displayRange = displayRange,
                inspectionRange = location.declarationRange,
            )
        }
    }

    private fun findCargoDependencyLocation(source: String, dependencyName: String): CargoDependencyLocation? =
        cargoDependencyLocations(source).firstOrNull { it.packageName == dependencyName || it.manifestName == dependencyName }

    private fun cargoDependencyLocations(source: String): List<CargoDependencyLocation> {
        var inDependencySection = false
        var currentTableDependencyName: String? = null
        var currentTablePackageName: String? = null
        var currentScope: String? = null
        var lineStart = 0
        val out = mutableListOf<CargoDependencyLocation>()
        while (lineStart <= source.length) {
            val lineEnd = source.indexOf('\n', lineStart).let { if (it < 0) source.length else it }
            val line = source.substring(lineStart, lineEnd)
            val trimmed = line.substringBefore('#').trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                val section = trimmed.trim('[', ']').trim()
                currentTableDependencyName = cargoDependencyTableName(section)
                currentTablePackageName = null
                inDependencySection = currentTableDependencyName == null && cargoDependencyListSection(section)
                currentScope = cargoDependencyScope(section)
            } else if (currentTableDependencyName != null) {
                val keyValue = parseCargoKeyValue(line)
                if (keyValue != null) {
                    if (keyValue.first == "package") {
                        currentTablePackageName = findQuotedValue(line, keyValue.second + 1)?.value
                    } else if (keyValue.first == "version") {
                        parseCargoDependencyTableVersionLine(
                            manifestName = currentTableDependencyName,
                            packageName = currentTablePackageName ?: currentTableDependencyName,
                            line = line,
                            lineStart = lineStart,
                            lineEnd = lineEnd,
                            valueStart = keyValue.second + 1,
                            scope = currentScope,
                        )?.let { out += it }
                    }
                }
            } else if (inDependencySection) {
                parseCargoDependencyLine(line, lineStart, lineEnd, currentScope)?.let { out += it }
            }
            if (lineEnd == source.length) break
            lineStart = lineEnd + 1
        }
        return out
    }

    private fun cargoDependencyListSection(section: String): Boolean =
        section == "dependencies" ||
            section == "dev-dependencies" ||
            section == "build-dependencies" ||
            section.endsWith(".dependencies")

    private fun cargoDependencyTableName(section: String): String? {
        val markers = listOf("dependencies.", "dev-dependencies.", "build-dependencies.", ".dependencies.")
        markers.forEach { marker ->
            val index = section.indexOf(marker)
            if (index >= 0) {
                return section.substring(index + marker.length).trim().trim('"', '\'').takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun cargoDependencyScope(section: String): String? {
        return when {
            section.contains("dev-dependencies") -> CargoWorkspace.DepKind.Development.name
            section.contains("build-dependencies") -> CargoWorkspace.DepKind.Build.name
            section.endsWith("dev-dependencies") -> CargoWorkspace.DepKind.Development.name
            section.endsWith("build-dependencies") -> CargoWorkspace.DepKind.Build.name
            section.contains("dependencies") -> CargoWorkspace.DepKind.Normal.name
            else -> null
        }
    }

    private fun parseCargoKeyValue(line: String): Pair<String, Int>? {
        val eq = line.indexOf('=')
        if (eq <= 0) return null
        val key = line.substring(0, eq).trim().trim('"', '\'')
        if (key.isBlank()) return null
        return key to eq
    }

    private fun parseCargoDependencyLine(
        line: String,
        lineStart: Int,
        lineEnd: Int,
        scope: String?,
    ): CargoDependencyLocation? {
        val eq = line.indexOf('=')
        if (eq <= 0) return null
        val key = line.substring(0, eq).trim().trim('"', '\'')
        if (key.isBlank()) return null
        val value = line.substring(eq + 1).trim()
        val declarationRange = TextRangeMarker(lineStart, lineEnd)
        if (!value.startsWith("{")) {
            val quoted = findQuotedValue(line, eq + 1) ?: return null
            return CargoDependencyLocation(
                manifestName = key,
                packageName = key,
                version = quoted.value,
                versionRange = TextRangeMarker(lineStart + quoted.startOffset, lineStart + quoted.endOffset),
                declarationRange = declarationRange,
                scope = scope,
            )
        }
        val packageName = findInlineTableString(line, eq + 1, "package") ?: key
        val versionIndex = line.indexOf("version", eq + 1)
        if (versionIndex < 0) {
            return CargoDependencyLocation(
                manifestName = key,
                packageName = packageName,
                version = null,
                versionRange = null,
                declarationRange = declarationRange,
                scope = scope,
            )
        }
        val versionEq = line.indexOf('=', versionIndex)
        if (versionEq < 0) return null
        val quoted = findQuotedValue(line, versionEq + 1) ?: return null
        return CargoDependencyLocation(
            manifestName = key,
            packageName = packageName,
            version = quoted.value,
            versionRange = TextRangeMarker(lineStart + quoted.startOffset, lineStart + quoted.endOffset),
            declarationRange = declarationRange,
            scope = scope,
        )
    }

    private fun parseCargoDependencyTableVersionLine(
        manifestName: String,
        packageName: String,
        line: String,
        lineStart: Int,
        lineEnd: Int,
        valueStart: Int,
        scope: String?,
    ): CargoDependencyLocation? {
        val quoted = findQuotedValue(line, valueStart) ?: return null
        return CargoDependencyLocation(
            manifestName = manifestName,
            packageName = packageName,
            version = quoted.value,
            versionRange = TextRangeMarker(lineStart + quoted.startOffset, lineStart + quoted.endOffset),
            declarationRange = TextRangeMarker(lineStart, lineEnd),
            scope = scope,
        )
    }

    private fun findInlineTableString(line: String, start: Int, key: String): String? {
        val keyIndex = line.indexOf(key, start)
        if (keyIndex < 0) return null
        val eq = line.indexOf('=', keyIndex)
        if (eq < 0) return null
        return findQuotedValue(line, eq + 1)?.value
    }

    private fun findQuotedValue(line: String, start: Int): QuotedValue? {
        val quote = line.indexOfAny(charArrayOf('"', '\''), start)
        if (quote < 0) return null
        val end = line.indexOf(line[quote], quote + 1)
        if (end <= quote) return null
        return QuotedValue(
            value = line.substring(quote + 1, end),
            startOffset = quote + 1,
            endOffset = end,
        )
    }

    private data class QuotedValue(
        val value: String,
        val startOffset: Int,
        val endOffset: Int,
    )

    private data class ResolvedCargoDependency(
        val version: String?,
        val scope: String?,
    )

    private data class CargoDependencyLocation(
        val manifestName: String,
        val packageName: String,
        val version: String?,
        val versionRange: TextRangeMarker?,
        val declarationRange: TextRangeMarker,
        val scope: String?,
    )
}
