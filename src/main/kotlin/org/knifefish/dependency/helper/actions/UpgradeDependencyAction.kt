package org.knifefish.dependency.helper.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import org.knifefish.dependency.helper.DependencyHelperBundle
import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.Ecosystem
import org.knifefish.dependency.helper.services.MavenSupport
import org.knifefish.dependency.helper.services.dependencyInsightService
import org.knifefish.dependency.helper.services.hasRecommendedUpgrade

class UpgradeDependencyAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val offset = editor.caretModel.offset
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val service = project.dependencyInsightService()
            val dependency = service.dependencyAt(file, offset)
            if (dependency == null) {
                showInfo(project, DependencyHelperBundle.message("Action.Upgrade.NoDependencyAtCaret"))
                return@executeOnPooledThread
            }
            val repositories = service.repositoriesFor(dependency.ecosystem)
            val latest = service.lookupLatestVersion(dependency, repositories).latestStable
            if (latest.isNullOrBlank()) {
                showWarning(project, DependencyHelperBundle.message("Action.Upgrade.NoLatestFound", dependency.displayName))
                return@executeOnPooledThread
            }
            if (!hasRecommendedUpgrade(dependency, latest)) {
                showInfo(project, DependencyHelperBundle.message("Action.Upgrade.AlreadyLatest", dependency.displayName))
                return@executeOnPooledThread
            }
            performUpgrade(project, dependency, latest)
        }
    }

    private fun performUpgrade(project: com.intellij.openapi.project.Project, dependency: DependencyCoordinate, latest: String) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val service = project.dependencyInsightService()
            val upgraded = if (dependency.ecosystem == Ecosystem.MAVEN && dependency.usesManagedVersion) {
                project.getService(MavenSupport::class.java)?.upgradeManagedDependency(dependency, latest) == true
            } else {
                service.upgradeDependency(dependency, latest)
            }
            if (!upgraded) {
                Messages.showWarningDialog(
                    project,
                    DependencyHelperBundle.message("Action.Upgrade.NoLatestFound", dependency.displayName),
                    DependencyHelperBundle.message("Plugin.Name"),
                )
            }
        }
    }

    private fun showInfo(project: com.intellij.openapi.project.Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                Messages.showInfoMessage(project, message, DependencyHelperBundle.message("Plugin.Name"))
            }
        }
    }

    private fun showWarning(project: com.intellij.openapi.project.Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                Messages.showWarningDialog(project, message, DependencyHelperBundle.message("Plugin.Name"))
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val hasEditor = e.project != null && e.getData(CommonDataKeys.EDITOR) != null && e.getData(CommonDataKeys.VIRTUAL_FILE) != null
        e.presentation.isEnabledAndVisible = hasEditor
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
