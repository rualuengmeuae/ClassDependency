package com.github.rualuengmeuae.classdependency.analyzer // <-- Updated package

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiJavaFile
// import com.intellij.psi.search.FileTypeIndex // Not strictly needed for current logic
// import com.intellij.psi.search.GlobalSearchScope // Used in findClassFile
// import com.intellij.lang.java.JavaLanguage // Not strictly needed
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.JavaFileType
import com.github.rualuengmeuae.classdependency.ui.Node // <-- Updated import
import com.github.rualuengmeuae.classdependency.ui.Edge // <-- Updated import
import java.util.LinkedList
import java.util.Queue
import com.intellij.psi.search.GlobalSearchScope // Ensure this is imported

class DependencyAnalyzer(private val project: Project) {

    data class ClassInfo(val fqName: String, val simpleName: String, val file: VirtualFile)
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
        val classes = psiFile.classes
        val mainClass = classes.firstOrNull { it.name == psiFile.virtualFile.nameWithoutExtension }

        return mainClass?.let {
            val fqName = if (packageName.isNotEmpty()) "$packageName.${it.name}" else it.name ?: ""
            if (fqName.isNotEmpty()) {
                 ClassInfo(fqName, it.name!!, psiFile.virtualFile)
            } else null
        }
    }


    private fun analyzeDependencies(targetFqName: String): Pair<Map<String, Node>, List<Edge>> {
        val allJavaFiles = mutableListOf<PsiJavaFile>()
        ApplicationManager.getApplication().runReadAction {
            ProjectRootManager.getInstance(project).fileIndex.iterateContent { virtualFile ->
                if (virtualFile.fileType is JavaFileType && !virtualFile.isDirectory) {
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
                getClassInfoFromPsiFile(psiJavaFile)?.let { classInfo ->
                    allProjectClasses[classInfo.fqName] = classInfo
                    classCache[classInfo.fqName] = classInfo // Populate cache

                    val imports = psiJavaFile.importList?.allImportStatements?.mapNotNull { it.importReference?.qualifiedName } ?: emptyList()
                    // val importWildcards = psiJavaFile.importList?.allImportStatements?.filter{ it.isOnDemand }?.mapNotNull { it.importReference?.qualifiedName } ?: emptyList() // Not used for now


                    // Check direct import or import of inner class/static member
                    if (imports.any { it == targetFqName || it.startsWith("$targetFqName.") }) {
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
                for (psiJavaFile in allJavaFiles) {
                    getClassInfoFromPsiFile(psiJavaFile)?.let { potentialDependentInfo ->
                        if (potentialDependentInfo.fqName == currentFqName) return@let // Skip self-reference check here

                        val imports = psiJavaFile.importList?.allImportStatements?.mapNotNull { it.importReference?.qualifiedName } ?: emptyList()
                        if (imports.any { it == currentFqName || it.startsWith("$currentFqName.") }) {
                            nodes.putIfAbsent(potentialDependentInfo.fqName, Node(potentialDependentInfo.simpleName, potentialDependentInfo.fqName, 0, 0))

                            if (edges.none { it.from == potentialDependentInfo.fqName && it.to == currentFqName }) {
                                edges.add(Edge(potentialDependentInfo.fqName, currentFqName))
                            }

                            // Add to queue only if it hasn't been deeply processed yet.
                            // Being in 'processed' means its own dependents were (or are being) explored.
                            // However, it might be added to queue if it's a direct dependent of another branch.
                            if (potentialDependentInfo.fqName !in processed || directDependents.containsKey(potentialDependentInfo.fqName)) {
                                 if(queue.none {it.first == potentialDependentInfo.fqName}) { // Avoid adding duplicates to queue
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
            val psiFacade = com.intellij.psi.JavaPsiFacade.getInstance(project)
            val psiClass = psiFacade.findClass(fqName, GlobalSearchScope.allScope(project))
            psiClass?.containingFile?.virtualFile
        }
    }
}
