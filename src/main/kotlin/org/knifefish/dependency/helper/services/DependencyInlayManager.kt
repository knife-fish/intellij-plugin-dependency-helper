package org.knifefish.dependency.helper.services

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import org.knifefish.dependency.helper.documentation.DependencyDocumentationProvider
import org.knifefish.dependency.helper.model.DependencyLookupResult
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

object DependencyInlayManager {

    private val inlayKey = Key.create<MutableList<Inlay<*>>>("dependency.helper.inlays")
    private val payloadKey = Key.create<ClickableInlayPayload>("dependency.helper.click.payload")
    private val listenerInstalledKey = Key.create<Boolean>("dependency.helper.click.listener.installed")

    fun render(editor: Editor, results: List<DependencyLookupResult>) {
        clear(editor)
        installClickListener(editor)
        val inlays = mutableListOf<Inlay<*>>()
        results.forEach { result ->
            val presentation = buildPresentation(result)
            val inlay = editor.inlayModel.addAfterLineEndElement(
                result.dependency.displayRange.endOffset,
                false,
                LatestVersionRenderer(presentation),
            )
            if (inlay != null) {
                if (presentation.emphasized) {
                    inlay.putUserData(payloadKey, ClickableInlayPayload(result))
                }
                inlays += inlay
            }
        }
        editor.putUserData(inlayKey, inlays)
    }

    fun clear(editor: Editor) {
        editor.getUserData(inlayKey)?.forEach { it.dispose() }
        editor.putUserData(inlayKey, mutableListOf())
    }

    private fun buildPresentation(result: DependencyLookupResult): InlayPresentation {
        val latest = result.versionInfo.latestStable
        return when {
            latest == null -> InlayPresentation("  latest unavailable", false)
            latest == result.dependency.version -> InlayPresentation("  up to date", false)
            else -> InlayPresentation("  latest $latest", true)
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

    private fun installClickListener(editor: Editor) {
        if (editor.getUserData(listenerInstalledKey) == true) {
            return
        }
        editor.contentComponent.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(event: MouseEvent) {
                val inlay = editor.inlayModel.getElementAt(event.point) ?: return
                val payload = inlay.getUserData(payloadKey) ?: return
                val latestRule = editor.project?.dependencyInsightService()?.latestVersionPolicy()?.displayName.orEmpty()
                DependencyDocumentationProvider.showQuickDoc(editor, payload.result, latestRule, event.point)
            }
        })
        editor.putUserData(listenerInstalledKey, true)
    }

    private data class InlayPresentation(
        val text: String,
        val emphasized: Boolean,
    )

    private data class ClickableInlayPayload(
        val result: DependencyLookupResult,
    )
}
