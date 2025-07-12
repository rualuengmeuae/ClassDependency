package com.github.rualuengmeuae.classdependency.ui

import com.github.rualuengmeuae.classdependency.analyzer.DependencyAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.mxgraph.layout.hierarchical.mxHierarchicalLayout
import com.mxgraph.swing.mxGraphComponent
import com.mxgraph.util.mxEvent
import com.mxgraph.util.mxEventObject
import com.mxgraph.view.mxGraph
import java.awt.BorderLayout
import java.awt.Toolkit // Needed for copyToClipboard
import java.awt.datatransfer.StringSelection // Needed for copyToClipboard
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer // Needed for click feedback

// Data classes Node and Edge are used by DependencyAnalyzer and passed to this panel.
// Their 'x', 'y', 'width', 'height' fields are no longer used by this panel's JGraphX implementation.
data class Node(val id: String, val fqName: String, var x: Int, var y: Int, val width: Int = 150, val height: Int = 40)
data class Edge(val from: String, val to: String)

class DependencyGraphPanel(
    private val project: Project,
    private val analyzer: DependencyAnalyzer
) : JPanel(BorderLayout()), FileEditorManagerListener, Disposable {

    private lateinit var graph: mxGraph
    private lateinit var graphComponent: mxGraphComponent

    init {
        // JGraphX setup
        graph = object : mxGraph() {
            override fun convertValueToString(cell: Any?): String {
                if (cell is com.mxgraph.model.mxCell && cell.isVertex) {
                    // Assuming the value of the vertex is its fqName,
                    // we want to display the simple name as the label.
                    val fqName = model.getValue(cell) as? String
                    return fqName?.substringAfterLast('.') ?: super.convertValueToString(cell)
                }
                return super.convertValueToString(cell)
            }
        }
        graphComponent = mxGraphComponent(graph)
        graphComponent.isDragEnabled = true // Ensure drag is enabled on the component
        graphComponent.graphControl.background = java.awt.Color.WHITE // Set background color
        add(graphComponent, BorderLayout.CENTER)

        // Configure graph properties & styles
        graph.isCellsEditable = false
        graph.isCellsSelectable = true
        graph.isCellsMovable = true
        graph.isCellsLocked = false // Explicitly unlock cells
        graph.isEdgeLabelsMovable = false
        graph.isVertexLabelsMovable = false

        // Set default edge style
        val edgeStyle = mutableMapOf<String, Any>()
        edgeStyle[com.mxgraph.util.mxConstants.STYLE_EDGE] = com.mxgraph.view.mxEdgeStyle.ElbowConnector
        edgeStyle[com.mxgraph.util.mxConstants.STYLE_ROUNDED] = true // Usually for vertices, but can affect edge waypoints if applicable
        edgeStyle[com.mxgraph.util.mxConstants.STYLE_STROKECOLOR] = "#606060" // Dark gray for edges
        edgeStyle[com.mxgraph.util.mxConstants.STYLE_STROKEWIDTH] = 1.5 // Set edge width
        edgeStyle[com.mxgraph.util.mxConstants.STYLE_ENDARROW] = com.mxgraph.util.mxConstants.ARROW_CLASSIC // Add classic arrow
        graph.stylesheet.defaultEdgeStyle = edgeStyle

        // Set default vertex style
        val vertexStyle = mutableMapOf<String, Any>()
        vertexStyle[com.mxgraph.util.mxConstants.STYLE_SHAPE] = com.mxgraph.util.mxConstants.SHAPE_RECTANGLE // or SHAPE_ROUNDED_RECTANGLE
        vertexStyle[com.mxgraph.util.mxConstants.STYLE_ROUNDED] = true
        vertexStyle[com.mxgraph.util.mxConstants.STYLE_FILLCOLOR] = "#E0E0E0" // Light gray fill
        vertexStyle[com.mxgraph.util.mxConstants.STYLE_STROKECOLOR] = "#000000" // Black border
        vertexStyle[com.mxgraph.util.mxConstants.STYLE_FONTCOLOR] = "#000000" // Black font
        vertexStyle[com.mxgraph.util.mxConstants.STYLE_FONTSIZE] = 12
        vertexStyle[com.mxgraph.util.mxConstants.STYLE_SPACING_TOP] = 4
        vertexStyle[com.mxgraph.util.mxConstants.STYLE_SPACING_BOTTOM] = 4
        vertexStyle[com.mxgraph.util.mxConstants.STYLE_SPACING_LEFT] = 8
        vertexStyle[com.mxgraph.util.mxConstants.STYLE_SPACING_RIGHT] = 8
        vertexStyle[com.mxgraph.util.mxConstants.STYLE_AUTOSIZE] = 1 // Enable autosize based on content
        // vertexStyle[com.mxgraph.util.mxConstants.STYLE_OVERFLOW] = "width" // If wrapping is preferred over autosize width
        graph.stylesheet.defaultVertexStyle = vertexStyle

        // Add mouse listener for interactions using graph's event mechanism
        graph.addListener(mxEvent.CLICK, object : com.mxgraph.util.mxEventSource.mxIEventListener {
            override fun invoke(sender: Any?, evt: mxEventObject?) {
                val me = evt?.getProperty("event") as? MouseEvent ?: return // "event" is the MouseEvent
                val cell = evt.getProperty("cell") // "cell" is the cell that was clicked on
                // val cell = graphComponent.getCellAt(me.x, me.y) // Alternative if "cell" property is not reliable

                if (cell != null && graph.model.isVertex(cell)) {
                    val fqName = graph.model.getValue(cell) as? String ?: return
                    if (me.clickCount == 1) { // Ensure it's a single click
                        if (me.isControlDown) {
                            openClassInEditor(fqName)
                        } else {
                            copyToClipboard(fqName)
                            // Visual feedback for copy
                            val cellsToUpdate = arrayOf(cell)
                            val originalStyle = graph.getCellStyle(cell)
                            val originalFillColor = originalStyle[com.mxgraph.util.mxConstants.STYLE_FILLCOLOR] ?: "#E0E0E0"

                            // Apply highlight
                            graph.setCellStyles(com.mxgraph.util.mxConstants.STYLE_FILLCOLOR, "#A9D0F5", cellsToUpdate) // Light blue
                            graphComponent.refresh()

                            // Revert to original color after a delay
                            Timer(500) {
                                graph.setCellStyles(com.mxgraph.util.mxConstants.STYLE_FILLCOLOR, originalFillColor.toString(), cellsToUpdate)
                                graphComponent.refresh()
                            }.apply { isRepeats = false; start() }
                        }
                    }
                }
            }
        })

        // Register listener for editor changes
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, this)
    }

    // FileEditorManagerListener methods
    override fun fileOpened(source: FileEditorManager, file: com.intellij.openapi.vfs.VirtualFile) {
        // Potentially refresh if a Java file is opened, though selectionChanged is often more relevant
    }

    override fun fileClosed(source: FileEditorManager, file: com.intellij.openapi.vfs.VirtualFile) {
        // If the closed file was the one being analyzed, maybe clear the graph or show a default state
    }

    override fun selectionChanged(event: FileEditorManagerEvent) {
        // This is the primary trigger for graph updates
        SwingUtilities.invokeLater { // Ensure UI updates on EDT
            refreshGraph()
        }
    }

    fun refreshGraph() {
        val result = analyzer.analyzeCurrentEditor()
        if (result != null) {
            // Pass Node and Edge data classes from analyzer directly
            updateGraph(result.first, result.second)
        } else {
            updateGraph(emptyMap(), emptyList())
        }
    }

    // This method will be completely rewritten in the next step to use JGraphX
    fun updateGraph(newNodes: Map<String, Node>, newEdges: List<Edge>) {
        graph.model.beginUpdate()
        try {
            graph.removeCells(graph.getChildVertices(graph.defaultParent)) // Clear previous graph

            if (newNodes.isEmpty()) return

            val jgraphxNodes = mutableMapOf<String, Any>() // Store fqName to JGraphX cell

            // Create vertices
            newNodes.forEach { (fqName, nodeData) ->
                // The value of the vertex is its fqName.
                // The label (display text) is derived from this fqName by the overridden convertValueToString.
                // Width and height (80.0, 30.0) are placeholders; actual size will be affected by style and label content.
                val vertex = graph.insertVertex(graph.defaultParent, null, fqName, 0.0, 0.0, 80.0, 30.0)
                jgraphxNodes[fqName] = vertex
            }

            // Create edges
            newEdges.forEach { edgeData ->
                val sourceVertex = jgraphxNodes[edgeData.from]
                val targetVertex = jgraphxNodes[edgeData.to]
                if (sourceVertex != null && targetVertex != null) {
                    graph.insertEdge(graph.defaultParent, null, "", sourceVertex, targetVertex)
                }
            }

        } finally {
            graph.model.endUpdate()
        }

        // Apply layout - Choose one of the layouts below

        // Hierarchical Layout
        // val layout = mxHierarchicalLayout(graph)
        // layout.intraCellSpacing = 50.0 // Horizontal spacing between nodes in the same rank
        // layout.interRankCellSpacing = 100.0 // Vertical spacing between ranks
        // layout.execute(graph.defaultParent)

        // Organic Layout (Force-directed)
        val layout = com.mxgraph.layout.mxOrganicLayout(graph)
        // layout.isNodeDistributionCost = true // This property might not exist in this jgraphx version
        layout.minDistanceLimit = 1.2
        layout.maxIterations = 200
        layout.execute(graph.defaultParent)

        SwingUtilities.invokeLater { graphComponent.refresh() }
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
        // If graphComponent or graph need explicit disposal, do it here.
    }
}
