package org.knifefish.dependency.helper.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.*
import com.intellij.ui.content.ContentFactory
import kotlinx.html.b
import kotlinx.html.body
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import org.knifefish.dependency.helper.model.Ecosystem
import org.knifefish.dependency.helper.model.LatestVersionPolicy
import org.knifefish.dependency.helper.model.MavenDependencyNodeView
import org.knifefish.dependency.helper.model.PackageSearchResult
import org.knifefish.dependency.helper.services.DependencyInsightService
import org.knifefish.dependency.helper.services.MavenSupport
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel

class DependencyToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DependencyAnalyzerPanel(
            project = project,
            service = project.service(),
            mavenSupport = requireNotNull(project.getService(MavenSupport::class.java)),
        )
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

class DependencyAnalyzerPanel(
    private val project: Project,
    private val service: DependencyInsightService,
    private val mavenSupport: MavenSupport,
) : SimpleToolWindowPanel(true, true) {

    private val treeModeButton = JRadioButton("Tree", true)
    private val listModeButton = JRadioButton("List")
    private val conflictOnlyCheckbox = JBCheckBox("Conflicts only")
    private val hideTestScopeCheckbox = JBCheckBox("Hide test scope")
    private val showGroupIdCheckbox = JBCheckBox("Show groupId")
    private val showSizeCheckbox = JBCheckBox("Show size")
    private val filterField = SearchTextField()
    private val searchField = SearchTextField()
    private val ecosystemCombo = JComboBox(DefaultComboBoxModel(Ecosystem.entries.toTypedArray()))
    private val latestPolicyCombo = JComboBox(DefaultComboBoxModel(LatestVersionPolicy.entries.toTypedArray()))
    private val analysisSummaryArea = JBTextArea()
    private val analysisRelationTree = JTree(DefaultMutableTreeNode("Dependency Relations"))
    private val searchDetailArea = JBTextArea()

    private val listModel = DefaultListModel<MavenDependencyNodeView>()
    private val dependencyList = JBList(listModel)
    private val dependencyTree = JTree(DefaultMutableTreeNode("root"))
    private val analysisCardLayout = CardLayout()
    private val analysisCard = JPanel(analysisCardLayout)
    private var dependencyTreeHeaderActions: JPanel? = null

    private val searchModel = DefaultListModel<PackageSearchResult>()
    private val searchList = JBList(searchModel)
    private val searchDebounceTimer = Timer(1000) { runSearch() }.apply { isRepeats = false }

    private var roots: List<MavenDependencyNodeView> = emptyList()
    private var latestVersionByKey: Map<String, String> = emptyMap()
    private var conflictKeys: Set<String> = emptySet()
    private val artifactSizeLabelByCoordinate = mutableMapOf<String, String>()

    init {
        configureDetailArea(analysisSummaryArea)
        configureDetailArea(searchDetailArea)
        analysisRelationTree.isRootVisible = false
        analysisRelationTree.cellRenderer = relationTreeRenderer()

        val tabs = JBTabbedPane()
        tabs.add("Package Analysis", buildAnalysisPanel())
        tabs.add("Package Search", buildSearchPanel())
        setContent(tabs)

        reloadDependencies()
    }

    private fun buildAnalysisPanel(): JPanel {
        val toolbar = buildAnalysisToolbar()

        dependencyList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        dependencyList.cellRenderer = listRenderer()
        dependencyList.addListSelectionListener { updateAnalysisDetails(selectedNode()) }
        dependencyList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                updateAnalysisDetails(selectedNode())
            }
        })
        dependencyList.addMouseListener(popupHandler { showAnalysisPopup(it.component, it.x, it.y) })

        dependencyTree.isRootVisible = false
        dependencyTree.cellRenderer = treeRenderer()
        dependencyTree.addTreeSelectionListener { updateAnalysisDetails(selectedNode()) }
        ButtonGroup().apply {
            add(treeModeButton)
            add(listModeButton)
        }
        treeModeButton.addActionListener { switchAnalysisMode(true) }
        listModeButton.addActionListener { switchAnalysisMode(false) }
        dependencyTree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                dependencyTree.getPathForLocation(e.x, e.y)?.let { dependencyTree.selectionPath = it }
                updateAnalysisDetails(selectedNode())
            }
        })
        dependencyTree.addMouseListener(popupHandler { event ->
            dependencyTree.getPathForLocation(event.x, event.y)?.let { dependencyTree.selectionPath = it }
            showAnalysisPopup(event.component, event.x, event.y)
        })

        analysisCard.add(JBScrollPane(dependencyTree), "tree")
        analysisCard.add(JBScrollPane(dependencyList), "list")

        return JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                JPanel(BorderLayout()).apply {
                    add(titledPanelHeader("Resolved Maven dependencies", dependencyTree), BorderLayout.NORTH)
                    add(analysisCard, BorderLayout.CENTER)
                },
                JPanel(BorderLayout()).apply {
                    add(JBLabel("Details and dependency tree"), BorderLayout.NORTH)
                    add(JSplitPane(JSplitPane.VERTICAL_SPLIT,
                        JPanel(BorderLayout()).apply {
                            add(JBLabel("Summary"), BorderLayout.NORTH)
                            add(JBScrollPane(analysisSummaryArea), BorderLayout.CENTER)
                        },
                        JPanel(BorderLayout()).apply {
                            add(
                                titledPanelHeader("Relations", analysisRelationTree),
                                BorderLayout.NORTH,
                            )
                            add(JBScrollPane(analysisRelationTree), BorderLayout.CENTER)
                        }
                    ).apply { resizeWeight = 0.38 }, BorderLayout.CENTER)
                }
            ).apply { resizeWeight = 0.58 }, BorderLayout.CENTER)
        }
    }

    private fun buildAnalysisToolbar(): JPanel {
        filterField.preferredSize = Dimension(220, filterField.preferredSize.height)
        filterField.addKeyboardListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent) {
                refreshAnalysisView()
            }
        })
        conflictOnlyCheckbox.addActionListener { refreshAnalysisView() }
        hideTestScopeCheckbox.addActionListener { refreshAnalysisView() }
        showGroupIdCheckbox.addActionListener { repaintDependencyViews() }
        showSizeCheckbox.addActionListener { repaintDependencyViews() }
        latestPolicyCombo.selectedItem = service.latestVersionPolicy()
        latestPolicyCombo.addActionListener {
            val selected = latestPolicyCombo.selectedItem as? LatestVersionPolicy ?: return@addActionListener
            service.setLatestVersionPolicy(selected)
            reloadDependencies()
        }

        val firstRow = JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                add(JButton("Refresh").apply { addActionListener { reloadDependencies() } })
                add(treeModeButton)
                add(listModeButton)
                add(JBLabel("Filter"))
                add(filterField)
            }, BorderLayout.WEST)
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                    add(
                        JButton("Donate").apply {
                            toolTipText = "Support Dependency Helper"
                            addActionListener {
                                Messages.showInfoMessage(
                                    project,
                                    "Buy me a coffee: https://buymeacoffee.com/knifefish",
                                    "Dependency Helper",
                                )
                            }
                        },
                    )
                },
                BorderLayout.EAST,
            )
        }

        val secondRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(conflictOnlyCheckbox)
            add(hideTestScopeCheckbox)
            add(showGroupIdCheckbox)
            add(showSizeCheckbox)
            add(JBLabel("Latest"))
            add(latestPolicyCombo)
        }

        return JPanel(GridLayout(2, 1, 0, 4)).apply {
            add(firstRow)
            add(secondRow)
        }
    }

    private fun buildSearchPanel(): JPanel {
        searchList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        searchList.cellRenderer = searchRenderer()
        searchList.addListSelectionListener { searchDetailArea.text = searchDetails(searchList.selectedValue) }

        val toolbar = JPanel(BorderLayout()).apply {
            add(JPanel().apply {
                add(JBLabel("Search"))
                add(ecosystemCombo)
                searchField.preferredSize = Dimension(240, searchField.preferredSize.height)
                searchField.addKeyboardListener(object : KeyAdapter() {
                    override fun keyReleased(e: KeyEvent) {
                        if (e.keyCode == KeyEvent.VK_ENTER) {
                            searchDebounceTimer.stop()
                            runSearch()
                        } else {
                            scheduleSearch()
                        }
                    }
                })
                add(searchField)
            }, BorderLayout.EAST)
        }

        return JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                JPanel(BorderLayout()).apply {
                    add(JBLabel("Search results"), BorderLayout.NORTH)
                    add(JBScrollPane(searchList), BorderLayout.CENTER)
                },
                JPanel(BorderLayout()).apply {
                    add(JBLabel("Details"), BorderLayout.NORTH)
                    add(JBScrollPane(searchDetailArea), BorderLayout.CENTER)
                }
            ).apply { resizeWeight = 0.45 }, BorderLayout.CENTER)
        }
    }

    private fun reloadDependencies() {
        roots = mavenSupport.analyze()
        artifactSizeLabelByCoordinate.clear()
        conflictKeys = flatten(roots)
            .filter { it.path.size > 1 }
            .groupBy { it.key }
            .filterValues { nodes -> nodes.map { it.version }.distinct().size > 1 }
            .keys
        refreshAnalysisView()
        resolveLatestVersions()
    }

    private fun refreshAnalysisView() {
        val filter = filterField.text.trim().lowercase()
        val hideTest = hideTestScopeCheckbox.isSelected
        val conflictOnly = conflictOnlyCheckbox.isSelected
        val filteredRoots = roots.mapNotNull { filterNode(it, filter, hideTest, conflictOnly) }
        rebuildList(filteredRoots)
        rebuildTree(filteredRoots)
        analysisSummaryArea.text = "Loaded ${flatten(filteredRoots).count { it.path.size > 1 }} Maven dependencies including transitive nodes."
        analysisRelationTree.model = DefaultTreeModel(DefaultMutableTreeNode("Dependency Relations"))
    }

    private fun filterNode(node: MavenDependencyNodeView, filter: String, hideTest: Boolean, conflictOnly: Boolean): MavenDependencyNodeView? {
        if (hideTest && node.isTestScope && node.path.size > 1) {
            return null
        }
        val children = node.children.mapNotNull { filterNode(it, filter, hideTest, conflictOnly) }.toMutableList()
        val matches = filter.isBlank() ||
            node.displayName.lowercase().contains(filter) ||
            node.path.joinToString(" -> ").lowercase().contains(filter)
        val passesConflict = !conflictOnly || node.path.size == 1 || conflictKeys.contains(node.key) || children.isNotEmpty()
        if ((!matches && children.isEmpty()) || !passesConflict) {
            return null
        }
        return node.copy(children = children)
    }

    private fun rebuildList(filteredRoots: List<MavenDependencyNodeView>) {
        listModel.clear()
        flatten(filteredRoots).filter { it.path.size > 1 }.forEach(listModel::addElement)
    }

    private fun rebuildTree(filteredRoots: List<MavenDependencyNodeView>) {
        val root = DefaultMutableTreeNode("root")
        filteredRoots.forEach { root.add(asTreeNode(it)) }
        dependencyTree.model = DefaultTreeModel(root)
        if (treeModeButton.isSelected) {
            expandAll(dependencyTree)
        }
    }

    private fun asTreeNode(view: MavenDependencyNodeView): DefaultMutableTreeNode {
        val treeNode = DefaultMutableTreeNode(view)
        view.children.forEach { treeNode.add(asTreeNode(it)) }
        return treeNode
    }

    private fun flatten(nodes: List<MavenDependencyNodeView>): List<MavenDependencyNodeView> {
        val results = mutableListOf<MavenDependencyNodeView>()
        fun visit(node: MavenDependencyNodeView) {
            results += node
            node.children.forEach(::visit)
        }
        nodes.forEach(::visit)
        return results
    }

    private fun resolveLatestVersions() {
        latestVersionByKey = emptyMap()
        val nodes = flatten(roots).filter { it.path.size > 1 }
        if (nodes.isEmpty()) {
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val versions = nodes.associate { node ->
                val latest = service.lookupLatestVersion(
                    mavenSupport.toDependencyCoordinate(node),
                    service.repositoriesFor(Ecosystem.MAVEN),
                ).latestStable
                node.key to (latest ?: "")
            }
            ApplicationManager.getApplication().invokeLater {
                latestVersionByKey = versions
                dependencyList.repaint()
                dependencyTree.repaint()
            }
        }
    }

    private fun useLatestOnSelected() {
        val node = selectedNode() ?: return
        val source = node.sourceDependency ?: return
        val latest = service.lookupLatestVersion(source, service.repositoriesFor(Ecosystem.MAVEN)).latestStable ?: return
        if (source.usesManagedVersion) {
            mavenSupport.upgradeManagedDependency(source, latest)
            mavenSupport.refreshMavenProject(source.file) {
                reloadDependencies()
                analysisSummaryArea.text = "Updated ${node.groupId}:${node.artifactId} to $latest."
            }
        } else {
            service.upgradeDependency(source, latest)
            reloadDependencies()
            analysisSummaryArea.text = "Updated ${node.groupId}:${node.artifactId} to $latest."
        }
    }

    private fun excludeSelected() {
        val node = selectedNode() ?: return
        if (mavenSupport.exclude(node)) {
            reloadDependencies()
            analysisSummaryArea.text = "Excluded ${node.groupId}:${node.artifactId} from ${node.path.getOrNull(1)}."
        }
    }

    private fun jumpToSource() {
        selectedNode()?.let(mavenSupport::jumpToSource)
    }

    private fun selectedNode(): MavenDependencyNodeView? {
        return if (treeModeButton.isSelected) {
            (dependencyTree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? MavenDependencyNodeView
        } else {
            dependencyList.selectedValue
        }
    }

    private fun analysisDetails(node: MavenDependencyNodeView?): String {
        if (node == null) {
            return "Select a Maven dependency to inspect dependency path, latest version, source jump, or exclusion options."
        }
        val coordinate = mavenSupport.toDependencyCoordinate(node)
        val latest = service.lookupLatestVersion(coordinate, service.repositoriesFor(Ecosystem.MAVEN))
        return buildString {
            appendLine("Package: ${node.displayName}")
            appendLine("Scope: ${node.scope ?: "-"}")
            appendLine("Owner project: ${node.ownerProjectName}")
            appendLine("Recommended latest: ${latest.latestStable ?: "unavailable"}")
            appendLine("Latest available: ${latest.latestAvailable ?: latest.latestStable ?: "unavailable"}")
            appendLine("Latest rule: ${service.latestVersionPolicy().displayName}")
            appendLine("Version source: ${if (node.sourceDependency?.usesManagedVersion == true) "managed by parent/BOM" else "declared or resolved"}")
            appendLine()
            appendLine("Actions:")
            appendLine("- Right click for Use Latest")
            appendLine("- Right click for Jump to Source")
            appendLine("- Right click for Exclude")
        }
    }

    private fun updateAnalysisDetails(node: MavenDependencyNodeView?) {
        analysisSummaryArea.text = analysisDetails(node)
        analysisRelationTree.model = DefaultTreeModel(buildRelationRoot(node))
        collapseAll(analysisRelationTree)
        expandSelectedRelationBranch(node)
    }

    private fun buildRelationRoot(node: MavenDependencyNodeView?): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode("Dependency Relations")
        if (node == null) {
            return root
        }
        val matches = collectMatchingOccurrences(node)
        matches.forEach { match -> root.add(buildReverseRelationTreeNode(match)) }
        return root
    }

    private fun collectMatchingOccurrences(node: MavenDependencyNodeView): List<MavenDependencyNodeView> {
        return flatten(roots)
            .filter {
                it.ownerProjectFile.path == node.ownerProjectFile.path &&
                    it.groupId == node.groupId &&
                    it.artifactId == node.artifactId
            }
            .sortedWith(compareBy<MavenDependencyNodeView>({ it.version }, { it.path.joinToString(" -> ") }))
    }

    private fun buildReverseRelationTreeNode(node: MavenDependencyNodeView): DefaultMutableTreeNode {
        val chain = node.path.drop(1)
        val root = DefaultMutableTreeNode(RelationOccurrenceLabel(node.versionAndScopeText(), node.path))
        if (chain.size <= 1) {
            return root
        }

        var current = root
        for (index in 0 until chain.size - 1) {
            val parentGa = chain[index]
            val childGa = if (index == chain.size - 2) {
                node.key
            } else {
                chain[index + 1]
            }
            val parentNode = findOccurrenceNode(node.ownerProjectFile.path, parentGa, index + 1, node.path, childGa)
            val parentTreeNode = DefaultMutableTreeNode(parentNode ?: buildSyntheticRelationNode(node, parentGa))
            current.add(parentTreeNode)
            current = parentTreeNode
        }
        return root
    }

    private fun findOccurrenceNode(
        ownerProjectPath: String,
        groupArtifact: String,
        pathIndex: Int,
        fullPath: List<String>,
        childGa: String,
    ): MavenDependencyNodeView? {
        return flatten(roots).firstOrNull {
            it.ownerProjectFile.path == ownerProjectPath &&
                "${it.groupId}:${it.artifactId}" == groupArtifact &&
                it.path.size > pathIndex &&
                it.path[pathIndex] == groupArtifact &&
                it.children.any { child -> "${child.groupId}:${child.artifactId}" == childGa } &&
                it.path.take(pathIndex + 1) == fullPath.take(pathIndex + 1)
        }
    }

    private fun buildSyntheticRelationNode(reference: MavenDependencyNodeView, groupArtifact: String): MavenDependencyNodeView {
        val groupId = groupArtifact.substringBefore(':')
        val artifactId = groupArtifact.substringAfter(':')
        return MavenDependencyNodeView(
            ownerProjectName = reference.ownerProjectName,
            ownerProjectFile = reference.ownerProjectFile,
            groupId = groupId,
            artifactId = artifactId,
            version = "",
            scope = null,
            packaging = null,
            path = emptyList(),
            sourceDependency = null,
        )
    }

    private fun runSearch() {
        val query = searchField.text.trim()
        if (query.length < 2) {
            searchModel.clear()
            return
        }
        searchModel.clear()
        val ecosystem = ecosystemCombo.selectedItem as Ecosystem
        service.searchPackages(ecosystem, query).forEach(searchModel::addElement)
        if (searchModel.isEmpty()) {
            searchDetailArea.text = "No search results. For private registries, exact names work best."
        }
    }

    private fun searchDetails(result: PackageSearchResult?): String {
        if (result == null) {
            return searchDetailArea.text
        }
        return buildString {
            appendLine("Package: ${result.displayName}")
            appendLine("Latest version: ${result.latestVersion ?: "unknown"}")
            appendLine("Repository: ${result.repositoryUrl ?: "unknown"}")
            appendLine()
            append(result.description ?: "No description available.")
        }
    }

    private fun scheduleSearch() {
        val query = searchField.text.trim()
        if (query.length < 2) {
            searchDebounceTimer.stop()
            searchModel.clear()
            return
        }
        searchDebounceTimer.restart()
    }

    private fun showAnalysisPopup(component: Component, x: Int, y: Int) {
        val selected = selectedNode() ?: return
        val source = selected.sourceDependency
        val managedOptions = if (source?.usesManagedVersion == true) {
            mavenSupport.resolveManagedUpgradeOptions(source)
        } else {
            emptyList()
        }
        val latest = source?.let { service.lookupLatestVersion(it, service.repositoriesFor(Ecosystem.MAVEN)).latestStable }
        if (selectedNode() == null) {
            return
        }
        JPopupMenu().apply {
            if (source != null && !latest.isNullOrBlank()) {
                if (managedOptions.isEmpty()) {
                    add("Use Latest").addActionListener { service.upgradeDependency(source, latest) }
                } else {
                    managedOptions.forEach { option ->
                        val label = when (option.target.kind.name) {
                            "CURRENT" -> "Use Latest"
                            "PARENT" -> "Use Latest via Parent (${option.latestVersion})"
                            "BOM" -> "Use Latest via BOM (${option.latestVersion})"
                            else -> "Use Latest"
                        }
                        add(label).addActionListener {
                            mavenSupport.executeManagedUpgradeTarget(option.target, option.latestVersion)
                            reloadDependencies()
                        }
                    }
                }
            }
            add("Jump to Source").addActionListener { jumpToSource() }
            add("Exclude").addActionListener { excludeSelected() }
        }.show(component, x, y)
    }

    private fun popupHandler(showPopup: (MouseEvent) -> Unit) = object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
            if (e.isPopupTrigger) {
                showPopup(e)
            }
        }

        override fun mouseReleased(e: MouseEvent) {
            if (e.isPopupTrigger) {
                showPopup(e)
            }
        }
    }

    private fun listRenderer() = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ) = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
            val node = value as? MavenDependencyNodeView ?: return@apply
            text = renderNodeHtml(node)
            foreground = when {
                !isSelected && conflictKeys.contains(node.key) -> JBColor(0xC62828, 0xFF6B6B)
                !isSelected && node.isTestScope -> JBColor(0xB85C00, 0xFFB86C)
                else -> foreground
            }
        }
    }

    private fun treeRenderer() = object : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(
            tree: JTree?,
            value: Any?,
            sel: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus).apply {
            val node = (value as? DefaultMutableTreeNode)?.userObject as? MavenDependencyNodeView ?: return@apply
            text = renderNodeHtml(node)
            foreground = when {
                !sel && conflictKeys.contains(node.key) -> JBColor(0xC62828, 0xFF6B6B)
                !sel && node.isTestScope -> JBColor(0xB85C00, 0xFFB86C)
                else -> foreground
            }
        }
    }

    private fun searchRenderer() = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ) = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
            val result = value as? PackageSearchResult ?: return@apply
            text = "${result.displayName} ${result.latestVersion.orEmpty()}".trim()
        }
    }

    private fun relationTreeRenderer() = object : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(
            tree: JTree?,
            value: Any?,
            sel: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus).apply {
            val userObject = (value as? DefaultMutableTreeNode)?.userObject
            when (userObject) {
                is MavenDependencyNodeView -> {
                    text = renderNodeHtml(userObject)
                    foreground = when {
                        !sel && conflictKeys.contains(userObject.key) -> JBColor(0xC62828, 0xFF6B6B)
                        !sel && userObject.isTestScope -> JBColor(0xB85C00, 0xFFB86C)
                        else -> foreground
                    }
                }
                is RelationOccurrenceLabel -> text = userObject.label
                is String -> text = userObject
            }
        }
    }

    private fun configureDetailArea(area: JBTextArea) {
        area.isEditable = false
        area.lineWrap = true
        area.wrapStyleWord = true
    }

    private fun repaintDependencyViews() {
        dependencyList.repaint()
        dependencyTree.repaint()
        analysisRelationTree.repaint()
    }

    private fun switchAnalysisMode(treeMode: Boolean) {
        analysisCardLayout.show(analysisCard, if (treeMode) "tree" else "list")
        dependencyTreeHeaderActions?.isVisible = treeMode
    }

    private fun renderNodeText(node: MavenDependencyNodeView): String {
        return node.renderText(
            latest = latestVersionByKey[node.key],
            showGroupId = showGroupIdCheckbox.isSelected,
            showSize = showSizeCheckbox.isSelected,
            sizeLabel = if (showSizeCheckbox.isSelected) artifactSizeLabel(node) else null,
        )
    }

    private fun renderNodeHtml(node: MavenDependencyNodeView): String {
        return node.renderHtml(
            showGroupId = showGroupIdCheckbox.isSelected,
            latest = latestVersionByKey[node.key],
            showSize = showSizeCheckbox.isSelected,
            sizeLabel = if (showSizeCheckbox.isSelected) artifactSizeLabel(node) else null,
        )
    }

    private fun expandSelectedRelationBranch(node: MavenDependencyNodeView?) {
        if (node == null) {
            return
        }
        val root = analysisRelationTree.model.root as? DefaultMutableTreeNode ?: return
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index) as? DefaultMutableTreeNode ?: continue
            val childUserObject = child.userObject as? RelationOccurrenceLabel ?: continue
            if (childUserObject.path == node.path && childUserObject.label == node.versionAndScopeText()) {
                expandBranch(analysisRelationTree, child)
                break
            }
        }
    }

    private fun expandAll(tree: JTree) {
        val root = tree.model.root as? DefaultMutableTreeNode ?: return
        expandBranch(tree, root)
    }

    private fun collapseAll(tree: JTree) {
        for (row in tree.rowCount - 1 downTo 1) {
            tree.collapseRow(row)
        }
    }

    private fun expandBranch(tree: JTree, node: DefaultMutableTreeNode) {
        val path = javax.swing.tree.TreePath(node.path)
        tree.expandPath(path)
        for (index in 0 until node.childCount) {
            val child = node.getChildAt(index) as? DefaultMutableTreeNode ?: continue
            expandBranch(tree, child)
        }
    }

    private fun artifactSizeLabel(node: MavenDependencyNodeView): String? {
        val coordinate = "${node.groupId}:${node.artifactId}:${node.version}:${node.packaging.orEmpty()}"
        return artifactSizeLabelByCoordinate.getOrPut(coordinate) {
            resolveArtifactSizeLabel(node).orEmpty()
        }.ifBlank { null }
    }

    private fun resolveArtifactSizeLabel(node: MavenDependencyNodeView): String? {
        if (node.groupId.isBlank() || node.artifactId.isBlank() || node.version.isBlank()) {
            return null
        }
        val versionDir = Paths.get(
            System.getProperty("user.home"),
            ".m2",
            "repository",
            *node.groupId.split('.').toTypedArray(),
            node.artifactId,
            node.version,
        )
        if (!Files.isDirectory(versionDir)) {
            return null
        }
        val extension = artifactExtension(node.packaging)
        val expectedPrefix = "${node.artifactId}-${node.version}"
        val artifactPath = Files.list(versionDir).use { stream ->
            stream
                .filter { candidate ->
                    Files.isRegularFile(candidate) &&
                        candidate.fileName.toString().startsWith(expectedPrefix) &&
                        candidate.fileName.toString().endsWith(".$extension") &&
                        !candidate.fileName.toString().endsWith("-sources.$extension") &&
                        !candidate.fileName.toString().endsWith("-javadoc.$extension")
                }
                .findFirst()
                .orElse(null)
        } ?: return null
        return formatSizeKilobytes(Files.size(artifactPath))
    }

    private fun artifactExtension(packaging: String?): String {
        return when (packaging?.lowercase()) {
            "pom" -> "pom"
            "war" -> "war"
            "ear" -> "ear"
            "zip" -> "zip"
            else -> "jar"
        }
    }

    private fun formatSizeKilobytes(sizeBytes: Long): String {
        val kilobytes = sizeBytes / 1024.0
        return if (kilobytes >= 100) {
            "${kilobytes.toInt()} KB"
        } else {
            String.format("%.1f KB", kilobytes)
        }
    }

    private fun titledPanelHeader(title: String, tree: JTree): JPanel {
        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            add(iconButton(AllIcons.Actions.Expandall, "Expand all", { expandAll(tree) }))
            add(iconButton(AllIcons.Actions.Collapseall, "Collapse all", { collapseAll(tree) }))
        }
        if (tree === dependencyTree) {
            dependencyTreeHeaderActions = actionsPanel
            actionsPanel.isVisible = treeModeButton.isSelected
        }
        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(preferredSize.width, 24)
            add(JBLabel(title), BorderLayout.WEST)
            add(actionsPanel, BorderLayout.EAST)
        }
    }

    private fun iconButton(icon: Icon, toolTip: String, action: () -> Unit): JButton {
        return JButton(icon).apply {
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            isOpaque = false
            margin = Insets(0, 0, 0, 0)
            preferredSize = Dimension(18, 18)
            minimumSize = preferredSize
            toolTipText = toolTip
            addActionListener { action() }
        }
    }
}

private data class RelationOccurrenceLabel(
    val label: String,
    val path: List<String>,
)

private fun MavenDependencyNodeView.renderText(latest: String?, showGroupId: Boolean, showSize: Boolean, sizeLabel: String?): String {
    val idText = if (showGroupId || groupId.isBlank()) "$groupId:$artifactId" else artifactId
    val versionText = if (version.isBlank()) "(unknown version)" else version
    val scopeText = scope?.takeIf { it.isNotBlank() }?.let { " [$it]" }.orEmpty()
    val latestSuffix = if (!latest.isNullOrBlank() && latest != version) " -> $latest" else ""
    val sizeSuffix = if (showSize && !sizeLabel.isNullOrBlank()) " ($sizeLabel)" else ""
    return "$idText : $versionText$scopeText$latestSuffix$sizeSuffix"
}

private fun MavenDependencyNodeView.renderHtml(showGroupId: Boolean, latest: String?, showSize: Boolean, sizeLabel: String?): String {
    val versionText = if (version.isBlank()) "(unknown version)" else version
    val latestSuffix = if (!latest.isNullOrBlank() && latest != version) " -> $latest" else ""
    val scopeText = scope?.takeIf { it.isNotBlank() }?.let { " [$it]" }.orEmpty()
    val sizeSuffix = if (showSize && !sizeLabel.isNullOrBlank()) " ($sizeLabel)" else ""
    return createHTML().html {
        body {
            if (showGroupId && groupId.isNotBlank()) {
                +groupId
                +":"
            }
            b {
                +artifactId
            }
            +" : $versionText$scopeText$latestSuffix$sizeSuffix"
        }
    }
}

private fun MavenDependencyNodeView.versionAndScopeText(): String {
    return buildString {
        append(if (version.isBlank()) "(unknown version)" else version)
        scope?.takeIf { it.isNotBlank() }?.let { append(" [$it]") }
    }
}
