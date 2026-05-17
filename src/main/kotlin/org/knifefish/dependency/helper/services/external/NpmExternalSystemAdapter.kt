package org.knifefish.dependency.helper.services.external

import com.intellij.javascript.nodejs.PackageJsonData
import com.intellij.javascript.nodejs.packageJson.PackageJsonFileManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.knifefish.dependency.helper.model.*

/** NPM 外部系统适配器，负责 package.json 的依赖读取与刷新。 */
internal class NpmExternalSystemAdapter : ExternalDependencySystem {
    /** 当前适配器生态。 */
    override val ecosystem: Ecosystem = Ecosystem.NPM

    override fun supports(file: VirtualFile): Boolean =
        DependencyFiles.kindOf(file) == DependencyFileKind.NPM_PACKAGE_JSON

    /** 基于 NodeJS 平台模型读取依赖并映射为统一依赖坐标。 */
    override fun scan(project: Project, file: VirtualFile): List<DependencyCoordinate> {
        // supports(file) 由 ExternalDependencySystems 分发前统一判断，这里不再重复校验。
        // 关键变量：package.json 管理器，负责有效文件判断。
        val fileManager = PackageJsonFileManager.getInstance(project)
        if (!fileManager.getValidPackageJsonFiles().contains(file)) {
            return emptyList()
        }
        // 关键变量：IDE 解析后的 package.json 语义模型。
        val packageJsonData = PackageJsonData.getOrCreateWithPreferredProject(project, file) ?: return emptyList()
        val source = file.inputStream.bufferedReader().use { it.readText() }
        // 关键变量：最终输出依赖集合。
        val out = mutableListOf<DependencyCoordinate>()
        packageJsonData.allDependencyEntries.forEach { (name, entry) ->
            val scope = entry.dependencyType.name
            val location = findJsonDependencyLocation(source, scope, name) ?: return@forEach
            val version = entry.versionRange
            out += DependencyCoordinate(
                ecosystem = Ecosystem.NPM,
                group = null,
                name = name,
                version = version,
                declaredVersion = version,
                scope = scope,
                file = file,
                declarationText = source.substring(location.declarationRange.startOffset, location.declarationRange.endOffset).trim(),
                lineNumber = source.take(location.valueRange.startOffset).count { it == '\n' } + 1,
                versionRange = location.valueRange,
                displayRange = location.valueRange,
                inspectionRange = location.declarationRange,
            )
        }
        return out
    }

    /** 通知 NodeJS 文件管理器刷新 package.json 内容。 */
    override fun refresh(project: Project, file: VirtualFile, afterRefresh: (() -> Unit)?): Boolean {
        PackageJsonFileManager.getInstance(project).onPackageJsonContentChanged(file)
        afterRefresh?.invoke()
        return true
    }

    private fun findJsonDependencyLocation(source: String, sectionName: String, dependencyName: String): JsonDependencyLocation? {
        val sectionNameRange = findJsonString(source, sectionName, 0) ?: return null
        val sectionColon = source.indexOf(':', sectionNameRange.endOffset).takeIf { it >= 0 } ?: return null
        val objectStart = source.indexOf('{', sectionColon).takeIf { it >= 0 } ?: return null
        val objectEnd = findMatching(source, objectStart, '{', '}') ?: return null
        val propertyNameRange = findJsonString(source, dependencyName, objectStart + 1, objectEnd) ?: return null
        val colon = source.indexOf(':', propertyNameRange.endOffset).takeIf { it in propertyNameRange.endOffset until objectEnd } ?: return null
        val valueRangeWithQuotes = findNextJsonString(source, colon + 1, objectEnd) ?: return null
        val declarationEnd = source.indexOf(',', valueRangeWithQuotes.endOffset)
            .takeIf { it in valueRangeWithQuotes.endOffset until objectEnd }
            ?: valueRangeWithQuotes.endOffset
        return JsonDependencyLocation(
            valueRange = TextRangeMarker(valueRangeWithQuotes.startOffset + 1, valueRangeWithQuotes.endOffset - 1),
            declarationRange = TextRangeMarker(propertyNameRange.startOffset, declarationEnd),
        )
    }

    private fun findJsonString(source: String, value: String, start: Int, end: Int = source.length): TextRangeMarker? {
        var cursor = start
        while (cursor < end) {
            val range = findNextJsonString(source, cursor, end) ?: return null
            if (source.substring(range.startOffset + 1, range.endOffset - 1) == value) {
                return range
            }
            cursor = range.endOffset
        }
        return null
    }

    private fun findNextJsonString(source: String, start: Int, end: Int): TextRangeMarker? {
        var cursor = start
        while (cursor < end) {
            if (source[cursor] == '"') {
                var close = cursor + 1
                var escaped = false
                while (close < end) {
                    val ch = source[close]
                    if (ch == '"' && !escaped) {
                        return TextRangeMarker(cursor, close + 1)
                    }
                    escaped = ch == '\\' && !escaped
                    if (ch != '\\') escaped = false
                    close++
                }
                return null
            }
            cursor++
        }
        return null
    }

    private fun findMatching(source: String, start: Int, open: Char, close: Char): Int? {
        var depth = 0
        var quote = false
        var escaped = false
        for (index in start until source.length) {
            val ch = source[index]
            if (quote) {
                if (ch == '"' && !escaped) quote = false
                escaped = ch == '\\' && !escaped
                if (ch != '\\') escaped = false
                continue
            }
            when (ch) {
                '"' -> quote = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private data class JsonDependencyLocation(
        val valueRange: TextRangeMarker,
        val declarationRange: TextRangeMarker,
    )
}
