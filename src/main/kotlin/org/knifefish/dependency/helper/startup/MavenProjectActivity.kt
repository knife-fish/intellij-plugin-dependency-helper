package org.knifefish.dependency.helper.startup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.knifefish.dependency.helper.editor.DependencyAnalyzerFileEditor
import org.knifefish.dependency.helper.services.dependencyInsightService

class MavenProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        ExternalSystemProgressNotificationManager.getInstance().addNotificationListener(object : ExternalSystemTaskNotificationListener {
            override fun onEnd(projectPath: String, id: ExternalSystemTaskId) {
                if (id.type != ExternalSystemTaskType.RESOLVE_PROJECT) {
                    return
                }
                if (id.projectSystemId != GradleConstants.SYSTEM_ID) {
                    return
                }
                project.dependencyInsightService().refreshOpenEditors()
                refreshDependencyAnalyzerEditors(project)
            }
        }, project)
        MavenProjectsManager.getInstance(project).addManagerListener(object : MavenProjectsManager.Listener {
            override fun projectImportCompleted() {
                project.dependencyInsightService().refreshOpenEditors()
                refreshDependencyAnalyzerEditors(project)
            }
        }, project)
    }

    private fun refreshDependencyAnalyzerEditors(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).allEditors
                .filterIsInstance<DependencyAnalyzerFileEditor>()
                .forEach { it.refreshDependencies() }
        }
    }
}
