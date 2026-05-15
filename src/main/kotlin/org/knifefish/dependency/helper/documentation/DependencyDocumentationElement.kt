package org.knifefish.dependency.helper.documentation

import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import javax.swing.Icon

internal class DependencyDocumentationElement(
    private val lookup: DependencyDocumentationProvider.DocumentationLookupContext,
) : FakePsiElement() {

    init {
        putUserData(DependencyDocumentationProvider.LOOKUP_RESULT_KEY, lookup)
    }

    override fun getName(): String = lookup.dependency.displayName

    override fun getPresentableText(): String = lookup.dependency.displayName

    override fun getLocationString(): String = lookup.dependency.file.path

    override fun getLanguage() = DependencyDocumentationLanguage

    override fun getProject(): Project = lookup.originalElement.project

    override fun getManager(): PsiManager = lookup.originalElement.manager

    override fun getParent(): PsiElement? = lookup.originalElement.parent

    override fun getContainingFile(): PsiFile? = lookup.originalElement.containingFile

    override fun getTextRange(): TextRange = lookup.originalElement.textRange

    override fun getTextOffset(): Int = lookup.dependency.displayRange.startOffset

    override fun getText(): String = lookup.dependency.declarationText.ifBlank { lookup.dependency.displayName }

    override fun getNavigationElement(): PsiElement = lookup.originalElement.navigationElement

    override fun getOriginalElement(): PsiElement = this

    override fun isValid(): Boolean = lookup.originalElement.isValid

    override fun isPhysical(): Boolean = false

    override fun getPresentation(): ItemPresentation = this

    override fun getIcon(unused: Boolean): Icon? = null

    override fun toString(): String = "DependencyDocumentationElement(${lookup.dependency.displayName})"
}
