package org.knifefish.dependency.helper.startup

import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
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
