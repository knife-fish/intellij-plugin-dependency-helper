package org.knifefish.dependency.helper.startup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import org.knifefish.dependency.helper.editor.DependencyAnalyzerFileEditor
import org.knifefish.dependency.helper.services.dependencyInsightService
import org.rust.cargo.project.model.CargoProjectsService

class RustProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        project.messageBus.connect(project).subscribe(
            CargoProjectsService.CARGO_PROJECTS_REFRESH_TOPIC,
            object : CargoProjectsService.CargoProjectsRefreshListener {
                override fun onRefreshFinished(status: CargoProjectsService.CargoRefreshStatus, isExplicit: Boolean) {
                    project.dependencyInsightService().refreshOpenEditors()
                    refreshDependencyAnalyzerEditors(project)
                }
            },
        )
        project.messageBus.connect(project).subscribe(
            CargoProjectsService.CARGO_PROJECTS_TOPIC,
            object : CargoProjectsService.CargoProjectsListener {
                override fun cargoProjectsUpdated(
                    service: CargoProjectsService,
                    projects: Collection<org.rust.cargo.project.model.CargoProject>,
                ) {
                    project.dependencyInsightService().refreshOpenEditors()
                    refreshDependencyAnalyzerEditors(project)
                }
            },
        )
    }

    private fun refreshDependencyAnalyzerEditors(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).allEditors
                .filterIsInstance<DependencyAnalyzerFileEditor>()
                .forEach { it.refreshDependencies() }
        }
    }
}
