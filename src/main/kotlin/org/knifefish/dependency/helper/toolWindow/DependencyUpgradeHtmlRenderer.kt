package org.knifefish.dependency.helper.toolWindow

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.util.text.StringUtil
import kotlinx.html.*
import kotlinx.html.stream.createHTML
import org.knifefish.dependency.helper.DependencyHelperBundle
import org.knifefish.dependency.helper.model.DependencyCoordinate
import org.knifefish.dependency.helper.model.VersionInfo
import org.knifefish.dependency.helper.services.ManagedUpgradeOption
import org.knifefish.dependency.helper.services.ManagedUpgradeTargetKind

object DependencyUpgradeHtmlRenderer {

    fun documentationMarkup(
        dependency: DependencyCoordinate,
        versionInfo: VersionInfo,
        latestRule: String,
        managedOptions: List<ManagedUpgradeOption> = emptyList(),
        path: String
    ): String {
        val latest = escapeHtml(versionInfo.latestStable ?: DependencyHelperBundle.message("Text.Unavailable"))
        val available = escapeHtml(versionInfo.latestAvailable ?: versionInfo.latestStable ?: DependencyHelperBundle.message("Text.Unavailable"))
        val publishedAt = escapeHtml(versionInfo.publishedAt ?: DependencyHelperBundle.message("Text.Unavailable"))
        val repository = escapeHtml(versionInfo.repositoryUrl ?: DependencyHelperBundle.message("Text.Unavailable"))
        val currentVersion = escapeHtml(dependency.version)
        val latestRuleValue = escapeHtml(latestRule)
        val content = createHTML().div {
            classes = setOf(DocumentationMarkup.CLASS_CONTENT)
            p {
                b { +DependencyHelperBundle.message("Doc.DependencyUpdateAvailable") }
            }
            table {
                style = "border-spacing: 0"
                infoRow(DependencyHelperBundle.message("Doc.Current"), currentVersion)
                infoRow(DependencyHelperBundle.message("Doc.Recommended"), latest, emphasize = true)
                infoRow(DependencyHelperBundle.message("Doc.LatestAvailable"), available)
                if (versionInfo.latestStable != null && versionInfo.latestAvailable != null && versionInfo.latestStable != versionInfo.latestAvailable) {
                    infoRow(DependencyHelperBundle.message("Doc.Note"), escapeHtml(DependencyHelperBundle.message("Doc.LatestAvailable.Note")))
                }
                infoRow(DependencyHelperBundle.message("Doc.Published"), publishedAt)
                infoRow(DependencyHelperBundle.message("Doc.Repository"), repository)
                infoRow(DependencyHelperBundle.message("Doc.Rule"), latestRuleValue)
            }
            if (versionInfo.latestStable != null) {
                p {
                    a(href = "psi_element://dependency-helper-upgrade:current") {
                        +DependencyHelperBundle.message("Doc.UpgradeTo", versionInfo.latestStable)
                    }
                    managedOptions
                        .filter { it.target.kind != ManagedUpgradeTargetKind.CURRENT }
                        .forEach { option ->
                            +" | "
                            a(href = "psi_element://dependency-helper-upgrade:${option.target.id}") {
                                +DependencyHelperBundle.message("Doc.UpgradeTargetTo", option.target.kind.buttonLabel(), option.latestVersion)
                            }
                        }
                }
            }
        }
        return buildString {
            append("<div class='${DocumentationMarkup.CLASS_DEFINITION}'>")
            append(path)
            append("</div>")
            append(content)
        }
    }

    private fun TABLE.infoRow(label: String, value: String, emphasize: Boolean = false) {
        tr {
            td {
                +label
                +":"
            }
            td {
                if (emphasize) {
                    b { +value }
                } else {
                    +value
                }
            }
        }
    }

    private fun escapeHtml(value: String): String {
        return StringUtil.escapeXmlEntities(value)
    }

    private fun ManagedUpgradeTargetKind.buttonLabel(): String = when (this) {
        ManagedUpgradeTargetKind.CURRENT -> DependencyHelperBundle.message("Doc.Upgrade")
        ManagedUpgradeTargetKind.PARENT -> DependencyHelperBundle.message("Doc.UpgradeParent")
        ManagedUpgradeTargetKind.BOM -> DependencyHelperBundle.message("Doc.UpgradeBom")
    }
}
