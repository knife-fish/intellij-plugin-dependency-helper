package org.knifefish.dependency.helper.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.knifefish.dependency.helper.services.dependencyInsightService

class MyProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        MavenProjectsManager.getInstance(project).addManagerListener(object : MavenProjectsManager.Listener {
            override fun projectImportCompleted() {
                project.dependencyInsightService().refreshOpenEditors()
            }
        }, project)
    }
}
