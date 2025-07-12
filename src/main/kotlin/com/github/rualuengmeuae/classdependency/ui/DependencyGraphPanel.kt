package com.github.rualuengmeuae.classdependency.ui // <-- Updated package

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.Disposable // For MessageBusConnection
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener // Added import
import com.intellij.openapi.project.Project
// import com.intellij.openapi.vfs.LocalFileSystem // Not directly used here, but analyzer uses it
import com.intellij.psi.JavaPsiFacade
// import com.intellij.psi.PsiClass // Not directly used here
import com.intellij.psi.search.GlobalSearchScope
import com.github.rualuengmeuae.classdependency.analyzer.DependencyAnalyzer // <-- Updated import
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Line2D
import javax.swing.*
import java.awt.BorderLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.LinkedList
import java.util.Queue


data class Node(val id: String, val fqName: String, var x: Int, var y: Int, var width: Int = 150, val height: Int = 40) {
    fun getRect(): Rectangle = Rectangle(x, y, width, height)
}

data class Edge(val from: String, val to: String)

class DependencyGraphPanel(
    private val project: Project,
    private val analyzer: DependencyAnalyzer
) : JPanel(BorderLayout()), FileEditorManagerListener, Disposable {
    private var nodes = mutableMapOf<String, Node>()
    private var edges = mutableListOf<Edge>()
    private var draggedNode: Node? = null
    private var dragStartPoint: Point? = null

    private val graphPanel = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            this@DependencyGraphPanel.paintGraph(g)
        }

        override fun getPreferredSize(): java.awt.Dimension {
            val maxX = nodes.values.map { it.x + it.width }.maxOrNull() ?: 0
            val maxY = nodes.values.map { it.y + it.height }.maxOrNull() ?: 0
            return java.awt.Dimension(maxX + 50, maxY + 50)
        }
    }

    private val modeComboBox = JComboBox(arrayOf("Current File", "Multiple Files", "All Project Files"))
    private val pathsTextArea = JTextArea(5, 50)
    private val analyzeButton = JButton("Analyze")
    private val pathsScrollPane = JScrollPane(pathsTextArea)

    init {
        val topPanel = JPanel(BorderLayout())
        topPanel.add(modeComboBox, BorderLayout.NORTH)
        topPanel.add(pathsScrollPane, BorderLayout.CENTER)
        topPanel.add(analyzeButton, BorderLayout.SOUTH)

        add(topPanel, BorderLayout.NORTH)
        add(JScrollPane(graphPanel), BorderLayout.CENTER)

        graphPanel.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                draggedNode = nodes.values.find { it.getRect().contains(e.point) }
                dragStartPoint = e.point
                if (draggedNode != null) {
                    if (e.clickCount == 1) {
                        if (e.isControlDown) {
                            openClassInEditor(draggedNode!!.fqName)
                        } else {
                            copyToClipboard(draggedNode!!.fqName)
                        }
                    }
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                draggedNode = null
                dragStartPoint = null
                graphPanel.repaint()
            }
        })

        graphPanel.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                draggedNode?.let { node ->
                    dragStartPoint?.let { start ->
                        node.x += e.x - start.x
                        node.y += e.y - start.y
                        dragStartPoint = e.point
                        graphPanel.repaint()
                    }
                }
            }
        })

        modeComboBox.addActionListener {
            val selectedMode = modeComboBox.selectedIndex
            pathsScrollPane.isVisible = selectedMode == 1
            analyzeButton.isVisible = selectedMode == 1 || selectedMode == 2
            refreshGraph()
        }

        analyzeButton.addActionListener {
            refreshGraph(false)
        }

        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, this)

        // Initial setup
        val selectedMode = modeComboBox.selectedIndex
        pathsScrollPane.isVisible = selectedMode == 1
        analyzeButton.isVisible = selectedMode == 1 || selectedMode == 2
    }

    // FileEditorManagerListener methods
    override fun fileOpened(source: FileEditorManager, file: com.intellij.openapi.vfs.VirtualFile) {
        // Potentially refresh if a Java file is opened, though selectionChanged is often more relevant
        // refreshGraph()
    }

    override fun fileClosed(source: FileEditorManager, file: com.intellij.openapi.vfs.VirtualFile) {
        // If the closed file was the one being analyzed, maybe clear the graph or show a default state
        // For now, selectionChanged will handle moving to a new file or no file.
    }

    override fun selectionChanged(event: com.intellij.openapi.fileEditor.FileEditorManagerEvent) {
        refreshGraph(true)
    }

    fun refreshGraph(fromSelectionChange: Boolean = false) {
        if (fromSelectionChange && modeComboBox.selectedIndex != 0) {
            return
        }

        val result = when (modeComboBox.selectedIndex) {
            0 -> analyzer.analyzeCurrentEditor()
            1 -> {
                val paths = pathsTextArea.text.split("\n").filter { it.isNotBlank() }
                analyzer.analyzeDependenciesByPaths(paths)
            }
            2 -> analyzer.analyzeAllProjectClasses()
            else -> null
        }

        if (result != null) {
            updateGraph(result.first, result.second)
        } else {
            updateGraph(emptyMap(), emptyList())
        }
    }

    fun updateGraph(newNodes: Map<String, Node>, newEdges: List<Edge>) {
        this.nodes = newNodes.toMutableMap()
        this.edges = newEdges.toMutableList()

        val nodeLevels = mutableMapOf<String, Int>()
        val nodesByLevel = mutableMapOf<Int, MutableList<Node>>()
        val allNodeIds = newNodes.keys.toMutableSet()

        if (allNodeIds.isEmpty()) {
            SwingUtilities.invokeLater { graphPanel.repaint() }
            return
        }

        // Determine levels using BFS/queue based approach for layout
        // Roots are nodes that are not targets in any *displayed* edges
        // or whose source is not among the displayed nodes.
        var currentRoots = allNodeIds.filter { nodeId ->
            newEdges.none { edge -> edge.to == nodeId && newNodes.containsKey(edge.from) }
        }.toMutableList()

        if (currentRoots.isEmpty() && newNodes.isNotEmpty()) {
            // Fallback: if all nodes have incoming edges from other displayed nodes (e.g., a cycle involving all)
            // Try to find the "ultimate target" of the original analysis if possible, or just pick one.
            val originallyAnalyzedClassFqName = analyzer.analyzeCurrentEditor()?.first?.values?.firstOrNull { node ->
                newEdges.none { it.from == node.fqName } // A node that nothing in the graph depends on
            }?.fqName

            if (originallyAnalyzedClassFqName != null && newNodes.containsKey(originallyAnalyzedClassFqName)) {
                currentRoots.add(originallyAnalyzedClassFqName)
            } else {
                 newEdges.map{it.to}.distinct().firstOrNull{ edgeTarget ->
                    !newEdges.any{it.from == edgeTarget} && newNodes.containsKey(edgeTarget)
                 }?.let { mainTarget -> currentRoots.add(mainTarget) }
            }
            if(currentRoots.isEmpty()) { // Still no root, pick any
                currentRoots.add(newNodes.keys.first())
            }
        }

        val queue: Queue<Pair<String, Int>> = LinkedList()
        currentRoots.forEach { rootId ->
            newNodes[rootId]?.let {
                queue.add(rootId to 0)
                nodeLevels[rootId] = 0
                nodesByLevel.getOrPut(0) { mutableListOf() }.add(it)
            }
        }

        var maxLevel = 0
        val visitedForLayout = mutableSetOf<String>()
        visitedForLayout.addAll(currentRoots)

        while (queue.isNotEmpty()) {
            val (currentId, level) = queue.poll()
            maxLevel = maxOf(maxLevel, level)

            // Find nodes that depend on currentId (edges from X to currentId)
            // For layout, we want to place these dependents at the next level "up" (level+1)
            newEdges.filter { it.to == currentId && newNodes.containsKey(it.from) }.forEach { edge ->
                val dependentNodeId = edge.from
                if (dependentNodeId !in visitedForLayout) {
                    newNodes[dependentNodeId]?.let {
                        val nextLevel = level + 1
                        nodeLevels[dependentNodeId] = nextLevel
                        nodesByLevel.getOrPut(nextLevel) { mutableListOf() }.add(it)
                        queue.add(dependentNodeId to nextLevel)
                        visitedForLayout.add(dependentNodeId)
                    }
                }
            }
        }

        // Position unreached nodes (e.g. disconnected components or complex cycles not hit by above)
        allNodeIds.forEach { nodeId ->
            if (nodeId !in nodeLevels) {
                newNodes[nodeId]?.let {
                    val fallbackLevel = maxLevel + 1
                    nodeLevels[nodeId] = fallbackLevel
                    nodesByLevel.getOrPut(fallbackLevel) { mutableListOf() }.add(it)
                }
            }
        }

        val levelHeight = 150
        val nodeSpacingHorizontal = 180 // Slightly reduced spacing
        val initialX = 50
        val initialY = 50

        // Position nodes. Levels are reversed for drawing (level 0 at bottom)
        val sortedLevels = nodesByLevel.keys.sortedDescending()
        var currentY = initialY

        for (levelKey in sortedLevels) {
            val nodesAtVisualLevel = nodesByLevel[levelKey] ?: continue
            val totalWidthAtLevel = nodesAtVisualLevel.sumOf { it.width } + maxOf(0, nodesAtVisualLevel.size - 1) * nodeSpacingHorizontal
            var currentX = initialX + (graphPanel.width.coerceAtLeast(totalWidthAtLevel) - totalWidthAtLevel) / 2
            if (currentX < initialX) currentX = initialX

            nodesAtVisualLevel.forEach { node ->
                node.x = currentX
                node.y = currentY
                currentX += node.width + nodeSpacingHorizontal
            }
            currentY += levelHeight
        }
        SwingUtilities.invokeLater {
            graphPanel.revalidate()
            graphPanel.repaint()
        }
    }

    private fun copyToClipboard(text: String) {
        val selection = StringSelection(text)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
    }

    private fun openClassInEditor(fqName: String) {
        ApplicationManager.getApplication().invokeLater {
            val virtualFile = analyzer.findClassFile(fqName)
            if (virtualFile != null) {
                 ApplicationManager.getApplication().runReadAction { // Ensure read action for file operations
                    FileEditorManager.getInstance(project).openFile(virtualFile, true)
                 }
            } else {
                 ApplicationManager.getApplication().runReadAction {
                    val psiFacade = JavaPsiFacade.getInstance(project)
                    val psiClass = psiFacade.findClass(fqName, GlobalSearchScope.allScope(project))
                    if (psiClass != null && psiClass.canNavigate()) {
                        psiClass.navigate(true)
                    } else {
                        println("DependencyGraphPanel: Could not find class or file: $fqName")
                    }
                 }
            }
        }
    }

    // Disposable method
    override fun dispose() {
        // Nothing specific to dispose here for this panel itself,
        // but message bus connection handled by connect(this) will be auto-disconnected.
    }

    private fun paintGraph(g: Graphics) {
        val g2d = g as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.color = graphPanel.background

        g2d.fillRect(0, 0, graphPanel.width, graphPanel.height)

        for (node in nodes.values) {
            val fm = g2d.fontMetrics
            val textWidth = fm.stringWidth(node.id)
            node.width = textWidth + 40
        }

        g2d.color = Color.DARK_GRAY
        g2d.stroke = BasicStroke(1.5f)
        for (edge in edges) {
            val fromNode = nodes[edge.from]
            val toNode = nodes[edge.to]
            if (fromNode != null && toNode != null) {
                val x1 = fromNode.x + fromNode.width / 2
                val y1 = fromNode.y
                val x2 = toNode.x + toNode.width / 2
                val y2 = toNode.y + toNode.height

                g2d.draw(Line2D.Double(x1.toDouble(), y1.toDouble(), x2.toDouble(), y2.toDouble()))

                val dx = x2 - x1
                val dy = y2 - y1
                val angle = Math.atan2(dy.toDouble(), dx.toDouble())
                val arrowSize = 8.0
                val endX = x2.toDouble()
                val endY = y2.toDouble()

                val arrowX1 = endX - arrowSize * Math.cos(angle - Math.PI / 6)
                val arrowY1 = endY - arrowSize * Math.sin(angle - Math.PI / 6)
                val arrowX2 = endX - arrowSize * Math.cos(angle + Math.PI / 6)
                val arrowY2 = endY - arrowSize * Math.sin(angle + Math.PI / 6)

                g2d.draw(Line2D.Double(endX, endY, arrowX1, arrowY1))
                g2d.draw(Line2D.Double(endX, endY, arrowX2, arrowY2))
            }
        }

        for (node in nodes.values) {
            g2d.color = Color.decode("#E0E0E0")
            g2d.fillRoundRect(node.x, node.y, node.width, node.height, 10, 10)
            g2d.color = Color.BLACK
            g2d.drawRoundRect(node.x, node.y, node.width, node.height, 10, 10)

            val fm = g2d.fontMetrics
            val textWidth = fm.stringWidth(node.id)
            val textX = node.x + (node.width - textWidth) / 2
            val textY = node.y + (node.height - fm.height) / 2 + fm.ascent
            g2d.drawString(node.id, textX, textY)
        }

        draggedNode?.let {
            g2d.color = Color.BLUE
            g2d.stroke = BasicStroke(2f)
            g2d.drawRoundRect(it.x, it.y, it.width, it.height, 10, 10)
        }
    }
}
