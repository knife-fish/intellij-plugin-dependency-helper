package org.knifefish.dependency.helper.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.*
import com.intellij.ui.treeStructure.Tree
import kotlinx.html.b
import kotlinx.html.body
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import org.knifefish.dependency.helper.DependencyHelperBundle
import org.knifefish.dependency.helper.model.Ecosystem
import org.knifefish.dependency.helper.model.LatestVersionPolicy
import org.knifefish.dependency.helper.model.MavenDependencyNodeView
import org.knifefish.dependency.helper.model.PackageSearchResult
import org.knifefish.dependency.helper.scanner.DependencyFileScanner
import org.knifefish.dependency.helper.services.DependencyInsightService
import org.knifefish.dependency.helper.services.GradleSupport
import org.knifefish.dependency.helper.services.MavenSupport
import org.knifefish.dependency.helper.services.hasRecommendedUpgrade
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

class DependencyAnalyzerPanel(
    private val project: Project,
    private val service: DependencyInsightService,
    private val mavenSupport: MavenSupport?,
    private val gradleSupport: GradleSupport?,
    private val currentFile: com.intellij.openapi.vfs.VirtualFile? = null,
) : SimpleToolWindowPanel(true, true) {

    private val treeModeButton = JRadioButton(message("Panel.Mode.Tree"), true)
    private val listModeButton = JRadioButton(message("Panel.Mode.List"))
    private val conflictOnlyCheckbox = JBCheckBox(message("Panel.Filter.ConflictsOnly"))
    private val hideTestScopeCheckbox = JBCheckBox(message("Panel.Filter.HideTestScope"))
    private val showGroupIdCheckbox = JBCheckBox(message("Panel.Filter.ShowGroupId"))
    private val showSizeCheckbox = JBCheckBox(message("Panel.Filter.ShowSize"))
    private val filterField = SearchTextField()
    private val searchField = SearchTextField()
    private val latestPolicyCombo = ComboBox(CollectionComboBoxModel(LatestVersionPolicy.entries.toList()))
    private val analysisRelationTree = Tree(DefaultMutableTreeNode(message("Panel.Relations.Title")))

    private val listModel = DefaultListModel<MavenDependencyNodeView>()
    private val dependencyList = JBList(listModel)
    private val dependencyTree = Tree(DefaultMutableTreeNode("root"))
    private val analysisCardLayout = CardLayout()
    private val analysisCard = JPanel(analysisCardLayout)
    private var dependencyTreeHeaderActions: JPanel? = null

    private val searchRows = mutableListOf<PackageSearchRow>()
    private val searchResultsPanel = JPanel()
    private val searchDebounceTimer = Timer(1000) { runSearch() }.apply { isRepeats = false }

    private var roots: List<MavenDependencyNodeView> = emptyList()
    private var latestVersionByKey: Map<String, String> = emptyMap()
    private var conflictKeys: Set<String> = emptySet()
    private val artifactSizeLabelByCoordinate = mutableMapOf<String, String>()
    private val dependencyScanner = DependencyFileScanner()

    init {
        analysisRelationTree.isRootVisible = false
        analysisRelationTree.cellRenderer = relationTreeRenderer()

        val tabs = JBTabbedPane()
        tabs.add(message("Panel.Tab.Analysis"), buildAnalysisPanel())
        tabs.add(message("Panel.Tab.Search"), buildSearchPanel())
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
                    add(titledPanelHeader(message("Panel.ResolvedDependencies"), dependencyTree), BorderLayout.NORTH)
                    add(analysisCard, BorderLayout.CENTER)
                },
                JPanel(BorderLayout()).apply {
                    add(
                        titledPanelHeader(message("Panel.Relations.Title"), analysisRelationTree),
                        BorderLayout.NORTH,
                    )
                    add(JBScrollPane(analysisRelationTree), BorderLayout.CENTER)
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
                add(JButton(message("Button.Refresh")).apply { addActionListener { reloadDependencies() } })
                add(treeModeButton)
                add(listModeButton)
                add(JBLabel(message("Label.Filter")))
                add(filterField)
            }, BorderLayout.WEST)
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                    add(
                        JButton(message("Button.Donate")).apply {
                            toolTipText = message("Tooltip.Support")
                            addActionListener {
                                Messages.showInfoMessage(
                                    project,
                                    message("Donate.Message"),
                                    message("Plugin.Name"),
                                )
                            }
                        },
                    )
                },
                BorderLayout.EAST,
            )
        }

        val secondRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            layout = BorderLayout()
            add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                add(conflictOnlyCheckbox)
                add(hideTestScopeCheckbox)
                add(showGroupIdCheckbox)
                add(showSizeCheckbox)
            }, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                add(JBLabel(message("Label.Latest")))
                add(latestPolicyCombo)
            }, BorderLayout.EAST)
        }

        return JPanel(GridLayout(2, 1, 0, 4)).apply {
            add(firstRow)
            add(secondRow)
        }
    }

    private fun buildSearchPanel(): JPanel {
        searchResultsPanel.layout = BoxLayout(searchResultsPanel, BoxLayout.Y_AXIS)

        val toolbar = JPanel(BorderLayout()).apply {
            add(JPanel().apply {
                add(JBLabel(message("Label.Search")))
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
            add(
                JPanel(BorderLayout()).apply {
                    add(JBLabel(message("Panel.SearchResults")), BorderLayout.NORTH)
                    add(JBScrollPane(searchResultsPanel), BorderLayout.CENTER)
                },
                BorderLayout.CENTER,
            )
        }
    }

    private fun reloadDependencies() {
        val targetFile = currentFile ?: activeDependencyFile()
        val effectiveTargetFile = resolveGradleDisplayFile(targetFile)
        thisLogger().info(
            "DependencyHelper reloadDependencies: currentFile=${currentFile?.path}, targetFile=${targetFile?.path}, " +
                "effectiveTargetFile=${effectiveTargetFile?.path}, " +
                "mavenSupport=${mavenSupport?.javaClass?.simpleName ?: "null"}, " +
                "gradleSupport=${gradleSupport?.javaClass?.simpleName ?: "null"}",
        )
        if (effectiveTargetFile != null && effectiveTargetFile.name in setOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")) {
            thisLogger().info("DependencyHelper reloadDependencies branch: gradleAnalyze-async file=${effectiveTargetFile.path}")
            ApplicationManager.getApplication().executeOnPooledThread {
                val resolvedRoots = gradleSupport?.analyze(effectiveTargetFile).orEmpty()
                    .ifEmpty { buildFileRoots(effectiveTargetFile) }
                ApplicationManager.getApplication().invokeLater {
                    thisLogger().info(
                        "DependencyHelper reloadDependencies gradleAnalyze-async result: file=${effectiveTargetFile.path}, roots=${resolvedRoots.size}, " +
                            "nodes=${resolvedRoots.joinToString { "${it.artifactId}:${it.children.size}" }}",
                    )
                    applyRoots(resolvedRoots)
                }
            }
            return
        }
        roots = when {
            effectiveTargetFile?.name == "pom.xml" && mavenSupport != null -> {
                val pomTarget = requireNotNull(effectiveTargetFile)
                thisLogger().info("DependencyHelper reloadDependencies branch: mavenAnalyze file=${pomTarget.path}")
                val analyzedRoots = mavenSupport.analyze()
                analyzedRoots.filter { it.ownerProjectFile.path == pomTarget.path }
                    .ifEmpty { buildFileRoots(pomTarget) }
            }
            effectiveTargetFile != null -> {
                thisLogger().info("DependencyHelper reloadDependencies branch: fileRoots file=${effectiveTargetFile.path}")
                buildFileRoots(effectiveTargetFile)
            }
            else -> {
                thisLogger().info("DependencyHelper reloadDependencies branch: projectMavenAnalyze")
                mavenSupport?.analyze().orEmpty()
            }
        }
        applyRoots(roots)
    }

    private fun activeDependencyFile(): com.intellij.openapi.vfs.VirtualFile? {
        val selectedFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return null
        thisLogger().info("DependencyHelper activeDependencyFile: selected=${selectedFile.path}, supported=${dependencyScanner.supports(selectedFile)}")
        return selectedFile.takeIf { dependencyScanner.supports(it) }
    }

    private fun resolveGradleDisplayFile(file: com.intellij.openapi.vfs.VirtualFile?): com.intellij.openapi.vfs.VirtualFile? {
        if (file == null || file.name !in setOf("settings.gradle", "settings.gradle.kts")) {
            return file
        }
        val parent = file.parent ?: return file
        return parent.findChild("build.gradle.kts")
            ?: parent.findChild("build.gradle")
            ?: file
    }

    private fun applyRoots(newRoots: List<MavenDependencyNodeView>) {
        thisLogger().info(
            "DependencyHelper applyRoots: roots=${newRoots.size}, " +
                "nodes=${newRoots.joinToString { "${it.artifactId}:${it.children.size}" }}",
        )
        roots = newRoots
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
        val displayRoots = normalizeDisplayRoots(roots)
        val filteredRoots = displayRoots.mapNotNull { filterNode(it, filter, hideTest, conflictOnly) }
        thisLogger().info(
            "DependencyHelper refreshAnalysisView: roots=${roots.size}, displayRoots=${displayRoots.size}, filteredRoots=${filteredRoots.size}, " +
                "filteredDependencyCount=${flatten(filteredRoots).count { it.path.size > 1 }}, " +
                "treeMode=${treeModeButton.isSelected}, listMode=${listModeButton.isSelected}",
        )
        rebuildList(filteredRoots)
        rebuildTree(filteredRoots)
        val dependencyCount = flatten(filteredRoots).count { it.path.size > 1 }
        publishNotification(when {
            currentFile?.name == "pom.xml" && mavenSupport != null ->
                message("Notification.Loaded.Transitive", dependencyCount)
            currentFile?.name in setOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts") && gradleSupport != null ->
                message("Notification.Loaded.Transitive", dependencyCount)
            currentFile != null ->
                message("Notification.Loaded.Declared", dependencyCount)
            else ->
                message("Notification.Loaded.Transitive", dependencyCount)
        })
        analysisRelationTree.model = DefaultTreeModel(DefaultMutableTreeNode(message("Panel.Relations.Title")))
    }

    private fun normalizeDisplayRoots(nodes: List<MavenDependencyNodeView>): List<MavenDependencyNodeView> {
        if (nodes.size != 1) {
            return nodes
        }
        val only = nodes.first()
        val isSyntheticFileRoot =
            only.groupId.isBlank() &&
                only.sourceDependency == null &&
                only.scope == "file" &&
                only.ownerProjectFile == currentDependencyTargetFile()
        return if (isSyntheticFileRoot && only.children.isNotEmpty()) only.children else nodes
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
        val items = flatten(filteredRoots).filter { it.path.size > 1 }
        items.forEach(listModel::addElement)
        thisLogger().info("DependencyHelper rebuildList: items=${items.size}")
    }

    private fun rebuildTree(filteredRoots: List<MavenDependencyNodeView>) {
        val root = DefaultMutableTreeNode("root")
        filteredRoots.forEach { root.add(asTreeNode(it)) }
        dependencyTree.model = DefaultTreeModel(root)
        thisLogger().info("DependencyHelper rebuildTree: topLevel=${root.childCount}")
        if (treeModeButton.isSelected && shouldAutoExpandTree()) {
            expandAll(dependencyTree)
        }
    }

    private fun shouldAutoExpandTree(): Boolean {
        val file = currentDependencyTargetFile() ?: currentFile
        return file?.name !in setOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
    }

    private fun asTreeNode(view: MavenDependencyNodeView): DefaultMutableTreeNode {
        val treeNode = DefaultMutableTreeNode(view)
        view.children.forEach { treeNode.add(asTreeNode(it)) }
        return treeNode
    }

    private fun flatten(nodes: List<MavenDependencyNodeView>): List<MavenDependencyNodeView> {
        val results = mutableListOf<MavenDependencyNodeView>()
        val seen = mutableSetOf<List<String>>()
        fun visit(node: MavenDependencyNodeView) {
            if (!seen.add(node.path)) {
                return
            }
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
                val coordinate = nodeCoordinate(node) ?: return@associate node.key to ""
                val latest = service.lookupLatestVersion(
                    coordinate,
                    service.repositoriesFor(coordinate.ecosystem),
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

    private fun excludeSelected() {
        val node = selectedNode() ?: return
        if (mavenSupport?.exclude(node) == true) {
            reloadDependencies()
            publishNotification(message("Notification.Excluded", "${node.groupId}:${node.artifactId}", node.path.getOrNull(1) ?: ""))
        }
    }

    private fun jumpToSource() {
        val node = selectedNode() ?: return
        mavenSupport?.jumpToSource(node)
    }

    private fun selectedNode(): MavenDependencyNodeView? {
        return if (treeModeButton.isSelected) {
            (dependencyTree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? MavenDependencyNodeView
        } else {
            dependencyList.selectedValue
        }
    }

    private fun updateAnalysisDetails(node: MavenDependencyNodeView?) {
        analysisRelationTree.model = DefaultTreeModel(buildRelationRoot(node))
        collapseAll(analysisRelationTree)
        expandSelectedRelationBranch(node)
    }

    private fun buildRelationRoot(node: MavenDependencyNodeView?): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode(message("Panel.Relations.Title"))
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
            searchRows.clear()
            renderSearchResults()
            return
        }
        val ecosystem = currentSearchEcosystem()
        if (ecosystem == null) {
            searchRows.clear()
            renderSearchResults(message("Panel.Search.OpenDependencyFile"))
            return
        }
        searchRows.clear()
        searchRows += service.searchPackages(ecosystem, query).map { result ->
            PackageSearchRow(
                result = result,
                selectedVersion = result.latestVersion.orEmpty(),
                versions = mutableListOf(result.latestVersion.orEmpty()).apply { removeAll { it.isBlank() } },
            )
        }
        renderSearchResults()
        if (searchRows.isEmpty()) {
            renderSearchResults(message("Panel.Search.NoResults"))
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            searchRows.forEachIndexed { index, row ->
                val versions = service.availableVersions(row.result.ecosystem, row.result.group, row.result.name)
                if (versions.isEmpty()) {
                    return@forEachIndexed
                }
                ApplicationManager.getApplication().invokeLater {
                    if (index >= searchRows.size) {
                        return@invokeLater
                    }
                    val target = searchRows[index]
                    target.versions.clear()
                    target.versions += versions
                    if (target.selectedVersion.isBlank() || target.selectedVersion !in target.versions) {
                        target.selectedVersion = target.versions.first()
                    }
                    renderSearchResults()
                }
            }
        }
    }

    private fun scheduleSearch() {
        val query = searchField.text.trim()
        if (query.length < 2) {
            searchDebounceTimer.stop()
            searchRows.clear()
            renderSearchResults()
            return
        }
        searchDebounceTimer.restart()
    }

    private fun currentDependencyTargetFile(): com.intellij.openapi.vfs.VirtualFile? {
        return currentFile ?: com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            .selectedFiles
            .firstOrNull { file ->
                file.name in setOf("pom.xml", "build.gradle", "build.gradle.kts", "package.json", "requirements.txt", "pyproject.toml", "Cargo.toml")
            }
    }

    private fun currentSearchEcosystem(): Ecosystem? {
        return currentDependencyTargetFile()?.let(dependencyScanner::detectEcosystem)
    }

    private fun addSearchResult(rowIndex: Int) {
        val row = searchRows.getOrNull(rowIndex) ?: return
        val targetFile = currentDependencyTargetFile()
        if (targetFile == null) {
            Messages.showInfoMessage(project, message("Dialog.OpenDependencyFileFirst"), message("Plugin.Name"))
            return
        }
        val version = row.selectedVersion.ifBlank { row.result.latestVersion.orEmpty() }
        if (version.isBlank()) {
            Messages.showWarningDialog(project, message("Dialog.NoVersionAvailable", row.result.displayName), message("Plugin.Name"))
            return
        }
        val added = service.addDependency(targetFile, row.result, version)
        if (!added) {
            Messages.showWarningDialog(project, message("Dialog.CouldNotAdd", row.result.displayName, targetFile.name), message("Plugin.Name"))
        }
    }

    private fun renderSearchResults(emptyMessage: String? = null) {
        searchResultsPanel.removeAll()
        if (searchRows.isEmpty()) {
            searchResultsPanel.add(
                JPanel(BorderLayout()).apply {
                    border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
                    add(JBLabel(emptyMessage ?: message("Panel.Search.Empty")), BorderLayout.WEST)
                },
            )
        } else {
            searchRows.forEachIndexed { index, row ->
                searchResultsPanel.add(buildSearchRowComponent(index, row))
            }
        }
        searchResultsPanel.revalidate()
        searchResultsPanel.repaint()
    }

    private fun buildSearchRowComponent(index: Int, row: PackageSearchRow): JComponent {
        val summary = buildSearchRowSummary(row)
        val versionCombo = ComboBox(CollectionComboBoxModel(row.versions, row.selectedVersion)).apply {
            preferredSize = Dimension(170, preferredSize.height)
            addActionListener {
                row.selectedVersion = selectedItem as? String ?: row.selectedVersion
            }
        }
        val addButton = JButton(message("Button.Add")).apply {
            addActionListener { addSearchResult(index) }
        }
        return JPanel(BorderLayout(8, 0)).apply {
            maximumSize = Dimension(Int.MAX_VALUE, 36)
            preferredSize = Dimension(preferredSize.width, 36)
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())
            add(summary, BorderLayout.WEST)
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                    isOpaque = false
                    add(versionCombo)
                    add(addButton)
                },
                BorderLayout.EAST,
            )
        }
    }

    private fun buildSearchRowSummary(row: PackageSearchRow): JComponent {
        val nameLabel = JBLabel(row.result.name.ifBlank { row.result.displayName }).apply {
            font = font.deriveFont(Font.BOLD)
        }
        val coordinateLabel = JBLabel(row.result.displayName).apply {
            foreground = JBColor.GRAY
        }
        val line = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(nameLabel)
            if (row.result.displayName.isNotBlank()) {
                add(Box.createHorizontalStrut(10))
                add(coordinateLabel)
            }
        }
        return JPanel(GridBagLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 10, 0, 10)
            add(line)
        }
    }

    private fun showAnalysisPopup(component: Component, x: Int, y: Int) {
        val selected = selectedNode() ?: return
        val source = selected.sourceDependency
        val managedOptions = if (source?.usesManagedVersion == true && mavenSupport != null) {
            mavenSupport.resolveManagedUpgradeOptions(source)
        } else {
            emptyList()
        }
        val latest = source?.let { service.lookupLatestVersion(it, service.repositoriesFor(it.ecosystem)).latestStable }
        if (selectedNode() == null) {
            return
        }
        JPopupMenu().apply {
            if (source != null && hasRecommendedUpgrade(source, latest)) {
                if (managedOptions.isEmpty()) {
                    add("Use Latest").addActionListener { service.upgradeDependency(source, latest!!) }
                } else {
                    managedOptions.forEach { option ->
                        val label = when (option.target.kind.name) {
                            "CURRENT" -> message("Popup.UseLatest")
                            "PARENT" -> message("Popup.UseLatestViaParent", option.latestVersion)
                            "BOM" -> message("Popup.UseLatestViaBom", option.latestVersion)
                            else -> message("Popup.UseLatest")
                        }
                        add(label).addActionListener {
                            mavenSupport?.executeManagedUpgradeTarget(option.target, option.latestVersion)
                            reloadDependencies()
                        }
                    }
                }
            }
            if (source?.ecosystem == Ecosystem.MAVEN && mavenSupport != null) {
                add(message("Popup.JumpToSource")).addActionListener { jumpToSource() }
                add(message("Popup.Exclude")).addActionListener { excludeSelected() }
            }
        }.show(component, x, y)
    }

    private fun buildFileRoots(file: com.intellij.openapi.vfs.VirtualFile): List<MavenDependencyNodeView> {
        val dependencies = service.scanFile(file)
        thisLogger().info(
            "DependencyHelper buildFileRoots: file=${file.path}, dependencies=" +
                dependencies.joinToString { "${it.declaredVersion ?: it.displayName}=>${it.displayName}:${it.version.ifBlank { "unknown" }}" },
        )
        if (dependencies.isEmpty()) {
            return emptyList()
        }
        val projectName = file.name
        val rootPath = listOf(file.path)
        val root = MavenDependencyNodeView(
            ownerProjectName = projectName,
            ownerProjectFile = file,
            groupId = "",
            artifactId = projectName,
            version = "",
            scope = "file",
            packaging = null,
            path = rootPath,
            sourceDependency = null,
        )
        dependencies.forEach { dependency ->
            root.children += MavenDependencyNodeView(
                ownerProjectName = projectName,
                ownerProjectFile = file,
                groupId = dependency.group.orEmpty(),
                artifactId = dependency.name,
                version = dependency.version,
                scope = dependency.scope,
                packaging = null,
                path = rootPath + dependency.displayName,
                sourceDependency = dependency,
            )
        }
        return listOf(root)
    }

    private fun nodeCoordinate(node: MavenDependencyNodeView): org.knifefish.dependency.helper.model.DependencyCoordinate? {
        return node.sourceDependency ?: if (currentFile == null && mavenSupport != null) {
            mavenSupport.toDependencyCoordinate(node)
        } else {
            null
        }
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

    private fun publishNotification(message: String) {
        if (message.isBlank()) return
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Dependency Helper Notifications")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
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
        val coordinate = "${node.sourceDependency?.ecosystem?.name.orEmpty()}:${node.groupId}:${node.artifactId}:${node.version}:${node.packaging.orEmpty()}"
        return artifactSizeLabelByCoordinate.getOrPut(coordinate) {
            resolveArtifactSizeLabel(node).orEmpty()
        }.ifBlank { null }
    }

    private fun resolveArtifactSizeLabel(node: MavenDependencyNodeView): String? {
        if (node.groupId.isBlank() || node.artifactId.isBlank() || node.version.isBlank()) {
            return null
        }
        val mavenVersionDir = Paths.get(
            System.getProperty("user.home"),
            ".m2",
            "repository",
            *node.groupId.split('.').toTypedArray(),
            node.artifactId,
            node.version,
        )
        findArtifactPath(mavenVersionDir, node)?.let { return formatSizeKilobytes(Files.size(it)) }

        val gradleVersionDir = Paths.get(
            System.getProperty("user.home"),
            ".gradle",
            "caches",
            "modules-2",
            "files-2.1",
            node.groupId,
            node.artifactId,
            node.version,
        )
        findArtifactPath(gradleVersionDir, node)?.let { return formatSizeKilobytes(Files.size(it)) }
        return null
    }

    private fun findArtifactPath(versionDir: java.nio.file.Path, node: MavenDependencyNodeView): java.nio.file.Path? {
        if (!Files.isDirectory(versionDir)) {
            return null
        }
        val extension = artifactExtension(node.packaging)
        val expectedPrefix = "${node.artifactId}-${node.version}"
        return Files.walk(versionDir).use { stream ->
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
        }
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
            add(iconButton(AllIcons.Actions.Expandall, message("Tooltip.ExpandAll"), { expandAll(tree) }))
            add(iconButton(AllIcons.Actions.Collapseall, message("Tooltip.CollapseAll"), { collapseAll(tree) }))
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

    private fun message(key: String, vararg params: Any): String = DependencyHelperBundle.message(key, *params)
}

private data class RelationOccurrenceLabel(
    val label: String,
    val path: List<String>,
)

private data class PackageSearchRow(
    val result: PackageSearchResult,
    val versions: MutableList<String>,
    var selectedVersion: String,
)

private fun MavenDependencyNodeView.renderHtml(showGroupId: Boolean, latest: String?, showSize: Boolean, sizeLabel: String?): String {
    val versionText = if (version.isBlank()) DependencyHelperBundle.message("Text.UnknownVersion") else version
    val latestSuffix = when {
        sourceDependency != null && hasRecommendedUpgrade(sourceDependency, latest) -> " -> $latest"
        sourceDependency == null && !latest.isNullOrBlank() && latest != version -> " -> $latest"
        else -> ""
    }
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
