package org.knifefish.dependency.helper.services

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import org.knifefish.dependency.helper.DependencyHelperBundle
import org.knifefish.dependency.helper.documentation.DependencyDocumentationProvider
import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.DependencyLookupResult
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle

object DependencyInlayManager {

    private val inlayKey = Key.create<MutableList<Inlay<*>>>("dependency.helper.inlays")

    fun render(
        editor: Editor,
        results: List<DependencyLookupResult>,
        latestRule: String,
        managedOptions: Map<DependencyCoordinate, List<ManagedUpgradeOption>> = emptyMap(),
    ) {
        clear(editor)
        DependencyDocumentationProvider.setEditorLookups(editor, results, latestRule, managedOptions)
        val inlays = mutableListOf<Inlay<*>>()
        val renderTargets = buildRenderTargets(editor, results)
        renderTargets.forEach { target ->
            val presentation = buildPresentation(target.result, target.leadingSpaces)
            val inlay = editor.inlayModel.addAfterLineEndElement(
                target.lineEndOffset,
                false,
                LatestVersionRenderer(presentation),
            )
            if (inlay != null) {
                inlays += inlay
            }
        }
        editor.putUserData(inlayKey, inlays)
    }

    fun clear(editor: Editor) {
        editor.getUserData(inlayKey)?.forEach { it.dispose() }
        editor.putUserData(inlayKey, mutableListOf())
        DependencyDocumentationProvider.clearEditorLookups(editor)
    }

    private fun buildRenderTargets(editor: Editor, results: List<DependencyLookupResult>): List<RenderTarget> {
        val document = editor.document
        val textLength = document.textLength
        val lineTargets = results.mapNotNull { result ->
            val offset = result.dependency.displayRange.endOffset
            if (offset !in 0..textLength) {
                return@mapNotNull null
            }
            val line = document.getLineNumber(offset)
            val lineEndOffset = document.getLineEndOffset(line)
            LineTarget(
                result = result,
                lineEndOffset = lineEndOffset,
                visualEndColumn = editor.offsetToVisualPosition(lineEndOffset).column,
            )
        }
        val hintColumn = lineTargets.maxOfOrNull { it.visualEndColumn }?.plus(MIN_HINT_GAP_COLUMNS)
            ?: MIN_HINT_GAP_COLUMNS
        return lineTargets.map { target ->
            RenderTarget(
                result = target.result,
                lineEndOffset = target.lineEndOffset,
                leadingSpaces = (hintColumn - target.visualEndColumn).coerceAtLeast(MIN_HINT_GAP_COLUMNS),
            )
        }
    }

    private fun buildPresentation(result: DependencyLookupResult, leadingSpaces: Int): InlayPresentation {
        val latest = result.versionInfo.latestStable
        val prefix = " ".repeat(leadingSpaces)
        return when {
            latest == null -> InlayPresentation(prefix + DependencyHelperBundle.message("Inlay.LatestUnavailable"), false)
            !hasRecommendedUpgrade(result.dependency, latest) -> InlayPresentation(prefix + DependencyHelperBundle.message("Inlay.UpToDate"), false)
            else -> InlayPresentation(prefix + DependencyHelperBundle.message("Inlay.Latest", latest), true)
        }
    }

    private class LatestVersionRenderer(private val presentation: InlayPresentation) : com.intellij.openapi.editor.EditorCustomElementRenderer {
        override fun calcWidthInPixels(inlay: Inlay<*>): Int =
            inlay.editor.contentComponent.getFontMetrics(font(inlay.editor)).stringWidth(presentation.text)

        override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
            g.font = font(inlay.editor)
            val metrics = inlay.editor.contentComponent.getFontMetrics(g.font)
            val baseline = targetRegion.y + metrics.ascent
            g.color = if (presentation.emphasized) JBColor(0x1A73E8, 0x4EA1FF) else JBColor.GRAY
            g.drawString(presentation.text, targetRegion.x, baseline)
        }

        private fun font(editor: Editor): Font = editor.colorsScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN)
    }

    private data class InlayPresentation(
        val text: String,
        val emphasized: Boolean,
    )

    private data class LineTarget(
        val result: DependencyLookupResult,
        val lineEndOffset: Int,
        val visualEndColumn: Int,
    )

    private data class RenderTarget(
        val result: DependencyLookupResult,
        val lineEndOffset: Int,
        val leadingSpaces: Int,
    )

    private const val MIN_HINT_GAP_COLUMNS = 2
}
