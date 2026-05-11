package org.knifefish.dependency.helper.scanner

import com.intellij.openapi.vfs.VirtualFile
import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.Ecosystem
import org.knifefish.dependency.helper.scanner.gradle.GradleDependencyScanner
import org.knifefish.dependency.helper.scanner.maven.MavenDependencyScanner
import org.knifefish.dependency.helper.scanner.npm.PackageJsonDependencyScanner
import org.knifefish.dependency.helper.scanner.python.PythonDependencyScanner
import org.knifefish.dependency.helper.scanner.rust.CargoDependencyScanner

internal class DependencyFileScanner(
    private val scanners: Map<Ecosystem, DependencyScannerContributor> = listOf(
        MavenDependencyScanner(),
        GradleDependencyScanner(),
        PackageJsonDependencyScanner(),
        PythonDependencyScanner(),
        CargoDependencyScanner(),
    ).associateBy { it.ecosystem },
) {

    fun supports(file: VirtualFile): Boolean = detectEcosystem(file) != null

    fun detectEcosystem(file: VirtualFile): Ecosystem? = when (file.name) {
        "pom.xml" -> Ecosystem.MAVEN
        "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts" -> Ecosystem.GRADLE
        "package.json" -> Ecosystem.NPM
        "requirements.txt", "pyproject.toml" -> Ecosystem.PYTHON
        "Cargo.toml" -> Ecosystem.RUST
        else -> null
    }

    fun scan(file: VirtualFile, text: String): List<DependencyCoordinate> {
        val ecosystem = detectEcosystem(file) ?: return emptyList()
        return scanners[ecosystem]?.scan(file, text).orEmpty()
    }
}
