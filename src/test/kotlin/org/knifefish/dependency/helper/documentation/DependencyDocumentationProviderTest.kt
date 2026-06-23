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

    fun testNpmDocumentationElementGeneratesUpgradeLink() {
        val file = LightVirtualFile("package.json", """{"dependencies":{"vue":"^2.6.0"}}""")
        val psiFile = psiManager.findFile(file) ?: error("Missing psi file")
        val dependency = DependencyCoordinate(
            ecosystem = Ecosystem.NPM,
            group = null,
            name = "vue",
            version = "^2.6.0",
            scope = "dependencies",
            file = file,
            declarationText = """"vue":"^2.6.0"""",
            lineNumber = 1,
            versionRange = TextRangeMarker(22, 28),
            inspectionRange = TextRangeMarker(17, 29),
        )
        val lookup = DependencyDocumentationProvider.DocumentationLookupContext(
            dependency = dependency,
            versionInfo = VersionInfo("3.5.0", "3.5.0", "https://registry.npmjs.org/", status = LookupStatus.OK),
            latestRule = "Release only",
            managedOptions = emptyList(),
            metadataPsi = null,
            originalElement = psiFile,
        )
        val element = DependencyDocumentationElement(lookup)

        val html = DependencyDocumentationProvider().generateDoc(element, element)

        assertNotNull(html)
        assertTrue(html!!.contains("vue"))
        assertTrue(html.contains("dependency-helper-upgrade:current"))
        assertTrue(html.contains("Upgrade to 3.5.0"))
    }

    fun testNpmQuickDocumentationFromEditorLookupGeneratesUpgradeLink() {
        val psiFile = myFixture.configureByText("package.json", """{"dependencies":{"vue":"^2.6.0"}}""")
        val virtualFile = psiFile.virtualFile
        val dependency = DependencyCoordinate(
            ecosystem = Ecosystem.NPM,
            group = null,
            name = "vue",
            version = "^2.6.0",
            scope = "dependencies",
            file = virtualFile,
            declarationText = """"vue":"^2.6.0"""",
            lineNumber = 1,
            versionRange = TextRangeMarker(24, 30),
            inspectionRange = TextRangeMarker(18, 31),
        )
        DependencyDocumentationProvider.setEditorLookups(
            myFixture.editor,
            listOf(
                DependencyLookupResult(
                    dependency,
                    VersionInfo("3.5.0", "3.5.0", "https://registry.npmjs.org/", status = LookupStatus.OK),
                ),
            ),
            "Release only",
        )

        val element = DependencyDocumentationProvider().getCustomDocumentationElement(
            myFixture.editor,
            psiFile,
            psiFile.findElementAt(25),
            25,
        )
        val html = DependencyDocumentationProvider().generateDoc(element!!, psiFile.findElementAt(25))

        assertNotNull(html)
        assertTrue(html!!.contains("dependency-helper-upgrade:current"))
        assertTrue(html.contains("Upgrade to 3.5.0"))
    }

    fun testNpmDocumentationElementDoesNotGenerateUpgradeLinkWhenAlreadyLatest() {
        val file = LightVirtualFile("package.json", """{"dependencies":{"vue":"^3.5.0"}}""")
        val psiFile = psiManager.findFile(file) ?: error("Missing psi file")
        val dependency = DependencyCoordinate(
            ecosystem = Ecosystem.NPM,
            group = null,
            name = "vue",
            version = "^3.5.0",
            scope = "dependencies",
            file = file,
            declarationText = """"vue":"^3.5.0"""",
            lineNumber = 1,
            versionRange = TextRangeMarker(22, 28),
            inspectionRange = TextRangeMarker(17, 29),
        )
        val lookup = DependencyDocumentationProvider.DocumentationLookupContext(
            dependency = dependency,
            versionInfo = VersionInfo("3.5.0", "3.5.0", "https://registry.npmjs.org/", status = LookupStatus.OK),
            latestRule = "Release only",
            managedOptions = emptyList(),
            metadataPsi = null,
            originalElement = psiFile,
        )
        val element = DependencyDocumentationElement(lookup)

        val html = DependencyDocumentationProvider().generateDoc(element, element)

        assertNotNull(html)
        assertFalse(html!!.contains("dependency-helper-upgrade:current"))
    }

    fun testNpmDocumentationElementUsesLatestAvailableWhenStableIsMissing() {
        val file = LightVirtualFile("package.json", """{"dependencies":{"vue":"^2.6.0"}}""")
        val psiFile = psiManager.findFile(file) ?: error("Missing psi file")
        val dependency = DependencyCoordinate(
            ecosystem = Ecosystem.NPM,
            group = null,
            name = "vue",
            version = "^2.6.0",
            scope = "dependencies",
            file = file,
            declarationText = """"vue":"^2.6.0"""",
            lineNumber = 1,
            versionRange = TextRangeMarker(22, 28),
            inspectionRange = TextRangeMarker(17, 29),
        )
        val lookup = DependencyDocumentationProvider.DocumentationLookupContext(
            dependency = dependency,
            versionInfo = VersionInfo(null, "3.5.0", "https://registry.npmjs.org/", status = LookupStatus.OK),
            latestRule = "Release only",
            managedOptions = emptyList(),
            metadataPsi = null,
            originalElement = psiFile,
        )
        val element = DependencyDocumentationElement(lookup)

        val html = DependencyDocumentationProvider().generateDoc(element, element)

        assertNotNull(html)
        assertTrue(html!!.contains("dependency-helper-upgrade:current"))
        assertTrue(html.contains("Upgrade to 3.5.0"))
    }
}
