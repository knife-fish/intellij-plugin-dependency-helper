package org.knifefish.dependency.helper.startup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import org.knifefish.dependency.helper.editor.DependencyAnalyzerFileEditor
import org.knifefish.dependency.helper.services.dependencyInsightService

class DependencyProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val connection = project.messageBus.connect()
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                val editor = event.manager.selectedTextEditor ?: return
                project.dependencyInsightService().refreshEditor(editor)
            }
        })
        project.dependencyInsightService().refreshOpenEditors()
    }
}

internal fun refreshDependencyEditors(project: Project) {
    ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed) return@invokeLater

        project.dependencyInsightService().refreshOpenEditors()
        FileEditorManager.getInstance(project).allEditors
            .filterIsInstance<DependencyAnalyzerFileEditor>()
            .forEach { it.refreshDependencies() }
    }
}
