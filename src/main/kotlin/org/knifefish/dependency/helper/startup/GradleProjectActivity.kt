package org.knifefish.dependency.helper.startup

import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import org.jetbrains.plugins.gradle.util.GradleConstants

class GradleProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val listener = object : ExternalSystemTaskNotificationListener {
            override fun onEnd(projectPath: String, id: ExternalSystemTaskId) {
                if (id.type == ExternalSystemTaskType.RESOLVE_PROJECT &&
                    id.projectSystemId == GradleConstants.SYSTEM_ID &&
                    id.findProject() == project
                ) {
                    refreshDependencyEditors(project)
                }
            }
        }
        ExternalSystemProgressNotificationManager.getInstance().addNotificationListener(listener, project)
    }
}
