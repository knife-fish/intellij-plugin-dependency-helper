package org.knifefish.dependency.helper.toolWindow

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.util.text.StringUtil
import kotlinx.html.TABLE
import kotlinx.html.a
import kotlinx.html.b
import kotlinx.html.classes
import kotlinx.html.div
import kotlinx.html.p
import kotlinx.html.stream.createHTML
import kotlinx.html.style
import kotlinx.html.table
import kotlinx.html.td
import kotlinx.html.tr
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
        val latest = escapeHtml(versionInfo.latestStable ?: "unavailable")
        val available = escapeHtml(versionInfo.latestAvailable ?: versionInfo.latestStable ?: "unavailable")
        val publishedAt = escapeHtml(versionInfo.publishedAt ?: "unavailable")
        val repository = escapeHtml(versionInfo.repositoryUrl ?: "unavailable")
        val currentVersion = escapeHtml(dependency.version)
        val latestRuleValue = escapeHtml(latestRule)
        val content = createHTML().div {
            classes = setOf(DocumentationMarkup.CLASS_CONTENT)
            p {
                b { +"Dependency update available" }
            }
            table {
                style = "border-spacing: 0"
                infoRow("Current", currentVersion)
                infoRow("Recommended", latest, emphasize = true)
                infoRow("Latest available", available)
                infoRow("Published", publishedAt)
                infoRow("Repository", repository)
                infoRow("Rule", latestRuleValue)
            }
            if (versionInfo.latestStable != null) {
                p {
                    a(href = "psi_element://dependency-helper-upgrade:current") {
                        +"Upgrade to ${versionInfo.latestStable}"
                    }
                    managedOptions
                        .filter { it.target.kind != ManagedUpgradeTargetKind.CURRENT }
                        .forEach { option ->
                            +" | "
                            a(href = "psi_element://dependency-helper-upgrade:${option.target.id}") {
                                +"${option.target.kind.buttonLabel()} to ${option.latestVersion}"
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
        ManagedUpgradeTargetKind.CURRENT -> "Upgrade"
        ManagedUpgradeTargetKind.PARENT -> "Upgrade Parent"
        ManagedUpgradeTargetKind.BOM -> "Upgrade BOM"
    }
}
