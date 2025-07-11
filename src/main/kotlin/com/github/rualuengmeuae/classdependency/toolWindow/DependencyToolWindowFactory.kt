package com.github.rualuengmeuae.classdependency.toolWindow // <-- Updated package

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.github.rualuengmeuae.classdependency.analyzer.DependencyAnalyzer // <-- Updated import
import com.github.rualuengmeuae.classdependency.ui.DependencyGraphPanel // <-- Updated import
import javax.swing.JScrollPane

class DependencyToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val analyzer = DependencyAnalyzer(project)
        val dependencyGraphPanel = DependencyGraphPanel(project, analyzer)

        // Wrap the panel in a JScrollPane for scrollability
        val scrollPane = JScrollPane(dependencyGraphPanel)
        scrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        scrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        // Set background of the panel itself, scrollpane viewport will show it.
        dependencyGraphPanel.background = java.awt.Color.WHITE


        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(scrollPane, "", false) // Add scrollPane instead of panel directly
        toolWindow.contentManager.addContent(content)

        // Initial analysis
        dependencyGraphPanel.refreshGraph()
    }
}
