package org.knifefish.dependency.helper.documentation

import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.psi.PsiFile

class DependencyDocumentationTargetProvider : DocumentationTargetProvider {

    override fun documentationTargets(psiFile: PsiFile, offset: Int): List<DocumentationTarget> {
        val lookup = psiFile.getUserData(DependencyDocumentationProvider.FILE_LOOKUP_KEY) ?: return emptyList()
        val range = lookup.dependency.inspectionRange
        val forceLookup = psiFile.getUserData(DependencyDocumentationProvider.FORCE_LOOKUP_KEY) == true
        if (!forceLookup && offset !in range.startOffset..range.endOffset) {
            return emptyList()
        }
        lookup.metadataPsi?.putUserData(DependencyDocumentationProvider.LOOKUP_RESULT_KEY, lookup)
        lookup.originalElement.putUserData(DependencyDocumentationProvider.LOOKUP_RESULT_KEY, lookup)
        return listOf(DependencyDocumentationTarget(lookup))
    }
}
