package org.knifefish.dependency.helper.documentation

import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.knifefish.dependency.helper.model.*

class DependencyDocumentationProviderTest : BasePlatformTestCase() {

    fun testDocumentationElementGeneratesUpgradeLink() {
        val file = LightVirtualFile("pom.xml", "<project/>")
        val psiFile = psiManager.findFile(file) ?: error("Missing psi file")
        val dependency = DependencyCoordinate(
            ecosystem = Ecosystem.MAVEN,
            group = "org.example",
            name = "demo",
            version = "1.0.0",
            scope = null,
            file = file,
            declarationText = "<dependency/>",
            lineNumber = 1,
            versionRange = TextRangeMarker(0, 0),
        )
        val lookup = DependencyDocumentationProvider.DocumentationLookupContext(
            dependency = dependency,
            versionInfo = VersionInfo("2.0.0", "2.0.0", "https://repo.example", status = LookupStatus.OK),
            latestRule = "Release only",
            managedOptions = emptyList(),
            metadataPsi = null,
            originalElement = psiFile,
        )
        val element = DependencyDocumentationElement(lookup)

        val html = DependencyDocumentationProvider().generateDoc(element, element)

        assertNotNull(html)
        assertTrue(html!!.contains("org.example:demo"))
        assertTrue(html.contains("dependency-helper-upgrade:current"))
    }
}
