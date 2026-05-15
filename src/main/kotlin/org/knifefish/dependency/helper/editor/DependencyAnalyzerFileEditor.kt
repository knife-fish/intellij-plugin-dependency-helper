package org.knifefish.dependency.helper.editor

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import org.knifefish.dependency.helper.DependencyHelperBundle
import org.knifefish.dependency.helper.services.DependencyInsightService
import org.knifefish.dependency.helper.services.GradleSupport
import org.knifefish.dependency.helper.services.MavenSupport
import org.knifefish.dependency.helper.toolWindow.DependencyAnalyzerPanel
import java.beans.PropertyChangeListener
import javax.swing.JComponent

class DependencyAnalyzerFileEditor(
    private val project: Project,
    private val file: VirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val panel = DependencyAnalyzerPanel(
        project = project,
        service = project.service<DependencyInsightService>(),
        mavenSupport = project.getService(MavenSupport::class.java),
        gradleSupport = project.getService(GradleSupport::class.java),
        currentFile = file,
    )

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = panel

    override fun getFile(): VirtualFile = file

    override fun getName(): String = DependencyHelperBundle.message("Plugin.Name")

    fun refreshDependencies() {
        panel.refreshDependencies()
    }

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = file.isValid

    override fun selectNotify() {
        refreshDependencies()
    }

    override fun deselectNotify() = Unit

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun getCurrentLocation(): FileEditorLocation? = null

    override fun dispose() = Unit

    override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE
}
