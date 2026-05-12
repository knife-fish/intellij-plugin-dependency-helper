package org.knifefish.dependency.helper.toolWindow

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import org.knifefish.dependency.helper.services.DependencyInsightService
import org.knifefish.dependency.helper.services.GradleSupport
import org.knifefish.dependency.helper.services.MavenSupport

class DependencyToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DependencyAnalyzerPanel(
            project = project,
            service = project.service<DependencyInsightService>(),
            mavenSupport = project.getService(MavenSupport::class.java),
            gradleSupport = project.getService(GradleSupport::class.java),
        )
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
