package org.knifefish.dependency.helper.services

import com.intellij.testFramework.LightVirtualFile
import junit.framework.TestCase.*
import org.junit.Test
import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.Ecosystem

class NpmVersionUpgradeStrategyTest {

    @Test
    fun preservesCaretAndTildePrefix() {
        assertEquals("^2.0.0", npmUpgradedVersionValue("^1.5.0", "2.0.0"))
        assertEquals("~2.0.0", npmUpgradedVersionValue("~1.5.0", "2.0.0"))
    }

    @Test
    fun replacesPlainVersion() {
        assertEquals("2.0.0", npmUpgradedVersionValue("1.5.0", "2.0.0"))
    }

    @Test
    fun skipsLocalGitAndComplexRangeVersions() {
        assertNull(npmUpgradedVersionValue("workspace:*", "2.0.0"))
        assertNull(npmUpgradedVersionValue("file:../local-lib", "2.0.0"))
        assertNull(npmUpgradedVersionValue("git+https://github.com/foo/bar.git", "2.0.0"))
        assertNull(npmUpgradedVersionValue(">=1.0.0 <2.0.0", "2.0.0"))
        assertNull(npmUpgradedVersionValue("^1.0.0 || ^2.0.0", "2.0.0"))
    }

    @Test
    fun npmCaretAndTildeAreTreatedAsAlreadyLatestWhenBaseEqualsLatest() {
        val file = LightVirtualFile("package.json", "{}")
        val caret = DependencyCoordinate(
            ecosystem = Ecosystem.NPM,
            group = null,
            name = "foo",
            version = "^4.3.1",
            scope = "dependencies",
            file = file,
            declarationText = "\"foo\": \"^4.3.1\"",
            lineNumber = 1,
            versionRange = null,
        )
        val tilde = caret.copy(version = "~4.3.1", declarationText = "\"foo\": \"~4.3.1\"")
        assertFalse(hasRecommendedUpgrade(caret, "4.3.1"))
        assertFalse(hasRecommendedUpgrade(tilde, "4.3.1"))
        assertTrue(hasRecommendedUpgrade(caret, "4.4.0"))
    }
}
