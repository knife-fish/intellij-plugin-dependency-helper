package org.knifefish.dependency.helper.documentation

import com.intellij.lang.documentation.psi.createPsiDocumentationTarget
import com.intellij.model.Pointer
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiElement

internal class DependencyDocumentationTarget(
    internal val lookup: DependencyDocumentationProvider.DocumentationLookupContext,
) : DocumentationTarget {

    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)

    override fun computePresentation(): TargetPresentation {
        return TargetPresentation.builder(lookup.dependency.displayName)
            .presentation()
    }

    override fun computeDocumentationHint(): String = lookup.dependency.displayName

    override fun computeDocumentation(): DocumentationResult {
        lookup.metadataPsi?.putUserData(DependencyDocumentationProvider.LOOKUP_RESULT_KEY, lookup)
        lookup.originalElement.putUserData(DependencyDocumentationProvider.LOOKUP_RESULT_KEY, lookup)
        val anchor: PsiElement = lookup.metadataPsi ?: lookup.originalElement
        return createPsiDocumentationTarget(anchor, lookup.originalElement).computeDocumentation()
            ?: DocumentationResult.documentation("")
    }
}
