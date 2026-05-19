package org.knifefish.dependency.helper.model

import com.intellij.openapi.vfs.VirtualFile

enum class DependencyFileKind(
    val ecosystem: Ecosystem,
    val fileNames: Set<String>,
    val supportsDependencyInsertion: Boolean,
) {
    MAVEN_POM(Ecosystem.MAVEN, setOf("pom.xml"), true),
    GRADLE_BUILD(Ecosystem.GRADLE, setOf("build.gradle", "build.gradle.kts"), true),
    GRADLE_SETTINGS(Ecosystem.GRADLE, setOf("settings.gradle", "settings.gradle.kts"), false),
    NPM_PACKAGE_JSON(Ecosystem.NPM, setOf("package.json"), false);

    fun matches(file: VirtualFile): Boolean = file.name in fileNames

    companion object {
        fun fromFile(file: VirtualFile): DependencyFileKind? = fromFileName(file.name)

        fun fromFileName(fileName: String): DependencyFileKind? =
            entries.firstOrNull { fileName in it.fileNames }
    }
}

object DependencyFiles {
    fun kindOf(file: VirtualFile): DependencyFileKind? = DependencyFileKind.fromFile(file)

    fun kindOf(fileName: String): DependencyFileKind? = DependencyFileKind.fromFileName(fileName)

    fun ecosystemOf(file: VirtualFile): Ecosystem? = kindOf(file)?.ecosystem

    fun isMavenPom(file: VirtualFile): Boolean = kindOf(file) == DependencyFileKind.MAVEN_POM

    fun isGradle(file: VirtualFile): Boolean = kindOf(file)?.ecosystem == Ecosystem.GRADLE

    fun isGradleSettings(file: VirtualFile): Boolean = kindOf(file) == DependencyFileKind.GRADLE_SETTINGS

    fun supportsDependencyInsertion(fileName: String, ecosystem: Ecosystem): Boolean {
        val kind = kindOf(fileName) ?: return false
        return kind.ecosystem == ecosystem && kind.supportsDependencyInsertion
    }

    fun findChild(directory: VirtualFile, kind: DependencyFileKind): VirtualFile? =
        kind.fileNames.firstNotNullOfOrNull(directory::findChild)
}
