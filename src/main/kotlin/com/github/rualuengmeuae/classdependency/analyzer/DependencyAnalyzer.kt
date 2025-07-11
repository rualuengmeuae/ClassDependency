package com.github.rualuengmeuae.classdependency.analyzer // <-- Updated package

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiJavaFile // Added
import com.intellij.psi.PsiImportList // Added
import com.intellij.psi.PsiImportStatement // Added
import com.intellij.psi.PsiClass // Added for mainClass type
import com.intellij.psi.JavaPsiFacade // Added
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.JavaFileType // Added
import com.github.rualuengmeuae.classdependency.ui.Node
import com.github.rualuengmeuae.classdependency.ui.Edge
import java.util.LinkedList
import java.util.Queue
import com.intellij.psi.search.GlobalSearchScope

class DependencyAnalyzer(private val project: Project) {

    data class ClassInfo(val fqName: String, val simpleName: String, val file: VirtualFile, val psiClass: PsiClass? = null)
    private val classCache = mutableMapOf<String, ClassInfo>() // Cache FQNameToClassInfo

    fun analyzeCurrentEditor(): Pair<Map<String, Node>, List<Edge>>? {
        var currentPsiFile: PsiJavaFile? = null
        ApplicationManager.getApplication().runReadAction {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            if (editor != null) {
                val virtualFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
                if (virtualFile != null && virtualFile.fileType is JavaFileType) {
                    currentPsiFile = PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile
                }
            }
        }

        val psiJavaFile = currentPsiFile ?: return null
        val targetClass = getClassInfoFromPsiFile(psiJavaFile) ?: return null

        return analyzeDependencies(targetClass.fqName)
    }

    private fun getClassInfoFromPsiFile(psiFile: PsiJavaFile): ClassInfo? {
        val packageName = psiFile.packageName
        val classes = psiFile.classes // This gives PsiClass[]
        val mainClass: PsiClass? = classes.firstOrNull { it.name == psiFile.virtualFile.nameWithoutExtension }

        return mainClass?.let { mc ->
            val className = mc.name
            if (className != null) {
                val fqName = if (packageName.isNotEmpty()) "$packageName.$className" else className
                ClassInfo(fqName, className, psiFile.virtualFile, mc)
            } else null
        }
    }


    private fun analyzeDependencies(targetFqName: String): Pair<Map<String, Node>, List<Edge>> {
        val allJavaFiles = mutableListOf<PsiJavaFile>()
        ApplicationManager.getApplication().runReadAction {
            ProjectRootManager.getInstance(project).fileIndex.iterateContent { virtualFile ->
                if (virtualFile.fileType is JavaFileType && !virtualFile.isDirectory && virtualFile.isValid) { // Added isValid check
                    val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                    if (psiFile is PsiJavaFile) {
                        allJavaFiles.add(psiFile)
                    }
                }
                true
            }
        }

        val directDependents = mutableMapOf<String, ClassInfo>() // Dependent FQName -> ClassInfo
        val allProjectClasses = mutableMapOf<String, ClassInfo>() // FQName -> ClassInfo

        ApplicationManager.getApplication().runReadAction {
            for (psiJavaFile in allJavaFiles) {
                if (!psiJavaFile.isValid) continue // Check validity
                getClassInfoFromPsiFile(psiJavaFile)?.let { classInfo ->
                    allProjectClasses[classInfo.fqName] = classInfo
                    classCache[classInfo.fqName] = classInfo // Populate cache

                    val importList: PsiImportList? = psiJavaFile.importList
                    val imports: List<String> = importList?.allImportStatements?.mapNotNull { importStatement ->
                        importStatement.importReference?.qualifiedName
                    } ?: emptyList()

                    // Check direct import or import of inner class/static member
                    if (imports.any { anImport -> anImport == targetFqName || anImport.startsWith("$targetFqName.") }) {
                        directDependents[classInfo.fqName] = classInfo
                    } else {
                        // Check if targetFqName is used without explicit import (same package or java.lang)
                        // This requires more sophisticated PSI analysis (resolving references)
                        // For now, we rely on explicit imports as per the problem description
                    }
                }
            }
        }


        val nodes = mutableMapOf<String, Node>()
        val edges = mutableListOf<Edge>()
        val processed = mutableSetOf<String>()
        val queue: Queue<Pair<String, ClassInfo?>> = LinkedList() // Pair of FQName and its direct dependent (for edge direction)

        // Add the target class node first
        val targetSimpleName = targetFqName.substringAfterLast('.')
        nodes[targetFqName] = Node(targetSimpleName, targetFqName, 0, 0) // Initial position, layout will adjust
        processed.add(targetFqName)

        // Add direct dependents to the queue
        directDependents.forEach { (dependentFqName, dependentClassInfo) ->
            queue.add(dependentFqName to allProjectClasses[targetFqName])
            if (!nodes.containsKey(dependentFqName)) {
                 nodes[dependentFqName] = Node(dependentClassInfo.simpleName, dependentFqName, 0, 0)
            }
            edges.add(Edge(dependentFqName, targetFqName)) // Dependent -> Target
        }


        while (queue.isNotEmpty()) {
            val (currentFqName, _) = queue.poll()
            // if (currentFqName in processed && currentFqName != targetFqName) continue
            // The above line was too aggressive, it prevented chains like D->C, C->A, E->C (E would be skipped if C processed via D)
            if (currentFqName in processed && !directDependents.containsKey(currentFqName) && currentFqName != targetFqName) continue


            val currentClassInfo = allProjectClasses[currentFqName] ?: continue
            nodes.putIfAbsent(currentFqName, Node(currentClassInfo.simpleName, currentFqName, 0, 0))
            processed.add(currentFqName)

            // Find classes that depend on currentFqName
            ApplicationManager.getApplication().runReadAction {
                for (psiJavaFileLoopVar in allJavaFiles) { // Renamed to avoid conflict
                    if (!psiJavaFileLoopVar.isValid) continue
                    getClassInfoFromPsiFile(psiJavaFileLoopVar)?.let { potentialDependentInfo ->
                        if (potentialDependentInfo.fqName == currentFqName) return@let // Skip self-reference check here

                        val importList: PsiImportList? = psiJavaFileLoopVar.importList
                        val imports: List<String> = importList?.allImportStatements?.mapNotNull { importStatement ->
                            importStatement.importReference?.qualifiedName
                        } ?: emptyList()

                        if (imports.any { anImport -> anImport == currentFqName || anImport.startsWith("$currentFqName.") }) {
                            nodes.putIfAbsent(potentialDependentInfo.fqName, Node(potentialDependentInfo.simpleName, potentialDependentInfo.fqName, 0, 0))

                            if (edges.none { edge -> edge.from == potentialDependentInfo.fqName && edge.to == currentFqName }) {
                                edges.add(Edge(potentialDependentInfo.fqName, currentFqName))
                            }

                            // Add to queue only if it hasn't been deeply processed yet.
                            if (potentialDependentInfo.fqName !in processed) { // Simplified condition
                                 if(queue.none {it.first == potentialDependentInfo.fqName}) {
                                    queue.add(potentialDependentInfo.fqName to currentClassInfo)
                                 }
                            }
                        }
                    }
                }
            }
        }
        return Pair(nodes, edges.distinct())
    }
     fun findClassFile(fqName: String): VirtualFile? {
        return classCache[fqName]?.file ?: ApplicationManager.getApplication().runReadAction<VirtualFile?> {
            val psiFacade = JavaPsiFacade.getInstance(project) // Use imported JavaPsiFacade
            val psiClass = psiFacade.findClass(fqName, GlobalSearchScope.allScope(project))
            psiClass?.containingFile?.virtualFile
        }
    }
}
