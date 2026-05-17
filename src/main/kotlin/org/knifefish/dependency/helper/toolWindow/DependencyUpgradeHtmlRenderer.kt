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
                +DependencyHelperBundle.message("Doc.DependencyUpdateAvailable")
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
                    val upgradeLinks = listOf(
                        UpgradeLink(
                            href = "psi_element://dependency-helper-upgrade:current",
                            text = DependencyHelperBundle.message("Doc.UpgradeTo", versionInfo.latestStable),
                        )
                    ) + managedOptions
                        .filter { it.target.kind != ManagedUpgradeTargetKind.CURRENT }
                        .map { option ->
                            UpgradeLink(
                                href = "psi_element://dependency-helper-upgrade:${option.target.id}",
                                text = DependencyHelperBundle.message("Doc.UpgradeTargetTo", option.target.kind.buttonLabel(), option.latestVersion),
                            )
                        }
                    upgradeLinks.forEachIndexed { index, link ->
                        a(href = link.href) {
                            if (index > 0) {
                                style = "margin-left: 10px"
                            }
                            +link.text
                        }
                    }
                }
            }
        }
        return buildString {
            append("<div class='${DocumentationMarkup.CLASS_DEFINITION}'>")
            append(escapeHtml(dependency.displayName))
            append("<br/><span style=\"color: #787878\">")
            append(escapeHtml(path))
            append("</span>")
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

    private data class UpgradeLink(
        val href: String,
        val text: String,
    )

    private fun ManagedUpgradeTargetKind.buttonLabel(): String = when (this) {
        ManagedUpgradeTargetKind.CURRENT -> DependencyHelperBundle.message("Doc.Upgrade")
        ManagedUpgradeTargetKind.PARENT -> DependencyHelperBundle.message("Doc.UpgradeParent")
        ManagedUpgradeTargetKind.BOM -> DependencyHelperBundle.message("Doc.UpgradeBom")
    }
}
