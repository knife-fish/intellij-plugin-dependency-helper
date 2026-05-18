package org.knifefish.dependency.helper.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.knifefish.dependency.helper.model.DependencyFiles

class DependencyAnalyzerFileEditorProvider : FileEditorProvider {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        DependencyFiles.isMavenPom(file) || (DependencyFiles.isGradle(file) && !DependencyFiles.isGradleSettings(file))

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        DependencyAnalyzerFileEditor(project, file)

    override fun getEditorTypeId(): String = "dependency-helper-analyzer"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR

    override fun readState(sourceElement: org.jdom.Element, project: Project, file: VirtualFile): FileEditorState =
        FileEditorState.INSTANCE
}
