package org.knifefish.dependency.helper.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import org.knifefish.dependency.helper.DependencyHelperBundle
import org.knifefish.dependency.helper.model.Ecosystem
import org.knifefish.dependency.helper.services.MavenSupport
import org.knifefish.dependency.helper.services.dependencyInsightService

class UpgradeDependencyAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val dependency = project.dependencyInsightService().dependencyAt(file, editor.caretModel.offset)
        if (dependency == null) {
            Messages.showInfoMessage(project, DependencyHelperBundle.message("Action.Upgrade.NoDependencyAtCaret"), DependencyHelperBundle.message("Plugin.Name"))
            return
        }
        val repositories = project.dependencyInsightService().repositoriesFor(dependency.ecosystem)
        val latest = project.dependencyInsightService().lookupLatestVersion(dependency, repositories).latestStable
        if (latest.isNullOrBlank()) {
            Messages.showWarningDialog(project, DependencyHelperBundle.message("Action.Upgrade.NoLatestFound", dependency.displayName), DependencyHelperBundle.message("Plugin.Name"))
            return
        }
        if (latest == dependency.version) {
            Messages.showInfoMessage(project, DependencyHelperBundle.message("Action.Upgrade.AlreadyLatest", dependency.displayName), DependencyHelperBundle.message("Plugin.Name"))
            return
        }
        if (dependency.ecosystem == Ecosystem.MAVEN && dependency.usesManagedVersion) {
            project.getService(MavenSupport::class.java)?.upgradeManagedDependency(dependency, latest)
        } else {
            project.dependencyInsightService().upgradeDependency(dependency, latest)
        }
    }

    override fun update(e: AnActionEvent) {
        val hasEditor = e.project != null && e.getData(CommonDataKeys.EDITOR) != null && e.getData(CommonDataKeys.VIRTUAL_FILE) != null
        e.presentation.isEnabledAndVisible = hasEditor
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
