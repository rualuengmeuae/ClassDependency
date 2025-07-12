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
import com.intellij.ide.highlighter.JavaFileType // Corrected import
import com.github.rualuengmeuae.classdependency.ui.Node
import com.github.rualuengmeuae.classdependency.ui.Edge
import java.util.LinkedList
import java.util.Queue
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch // Added for finding references
import com.intellij.psi.util.PsiTreeUtil // Added for PSI tree traversal
import com.intellij.psi.PsiElement // Added for reference element
import com.intellij.psi.PsiReference // Added for reference type

class DependencyAnalyzer(private val project: Project) {

    data class ClassInfo(val fqName: String, val simpleName: String, val file: VirtualFile, val psiClass: PsiClass? = null)
    private val classCache = mutableMapOf<String, ClassInfo>() // Cache FQNameToClassInfo

    fun analyzeCurrentEditor(): Pair<Map<String, Node>, List<Edge>>? {
        var currentPsiFile: PsiJavaFile? = null
        ApplicationManager.getApplication().runReadAction {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            if (editor != null) {
                val virtualFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
                if (virtualFile != null && virtualFile.fileType is JavaFileType) { // Use imported class
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
                if (virtualFile.fileType is JavaFileType && !virtualFile.isDirectory && virtualFile.isValid) { // Use imported class & isValid check
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
            // First, populate allProjectClasses and cache
            for (psiJavaFile in allJavaFiles) {
                if (!psiJavaFile.isValid) continue
                getClassInfoFromPsiFile(psiJavaFile)?.let { classInfo ->
                    allProjectClasses[classInfo.fqName] = classInfo
                    classCache[classInfo.fqName] = classInfo
                }
            }

            // Find the PsiClass for the targetFqName
            val psiFacade = JavaPsiFacade.getInstance(project)
            val targetPsiClass = psiFacade.findClass(targetFqName, GlobalSearchScope.allScope(project))

            if (targetPsiClass != null) {
                // Use ReferencesSearch to find all usages of the targetPsiClass
                val searchScope = GlobalSearchScope.projectScope(project)
                ReferencesSearch.search(targetPsiClass, searchScope).forEach { psiReference ->
                    val referencingElement = psiReference.element
                    val referencingFile = PsiTreeUtil.getParentOfType(referencingElement, PsiJavaFile::class.java)

                    if (referencingFile != null && referencingFile.isValid) {
                        getClassInfoFromPsiFile(referencingFile)?.let { dependentClassInfo ->
                            // Ensure the dependent is not the target class itself (unless it's a self-reference within the class)
                            if (dependentClassInfo.fqName != targetFqName) {
                                directDependents[dependentClassInfo.fqName] = dependentClassInfo
                            } else {
                                // Handle self-references if necessary, or decide if they should be part of "dependents"
                                // For now, we are looking for *other* classes depending on the target.
                                // If ClassA uses ClassA, it's not typically a "reverse dependency" in this context.
                            }
                        }
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

            // Find classes that depend on currentFqName using ReferencesSearch
            ApplicationManager.getApplication().runReadAction {
                val currentPsiClass = JavaPsiFacade.getInstance(project).findClass(currentFqName, GlobalSearchScope.allScope(project))
                if (currentPsiClass != null) {
                    val searchScope = GlobalSearchScope.projectScope(project)
                    ReferencesSearch.search(currentPsiClass, searchScope).forEach { psiReference ->
                        val referencingElement = psiReference.element
                        val referencingFile = PsiTreeUtil.getParentOfType(referencingElement, PsiJavaFile::class.java)

                        if (referencingFile != null && referencingFile.isValid) {
                            getClassInfoFromPsiFile(referencingFile)?.let { potentialDependentInfo ->
                                if (potentialDependentInfo.fqName == currentFqName) return@let // Skip self-reference

                                nodes.putIfAbsent(potentialDependentInfo.fqName, Node(potentialDependentInfo.simpleName, potentialDependentInfo.fqName, 0, 0))

                                if (edges.none { edge -> edge.from == potentialDependentInfo.fqName && edge.to == currentFqName }) {
                                    edges.add(Edge(potentialDependentInfo.fqName, currentFqName))
                                }

                                if (potentialDependentInfo.fqName !in processed) {
                                    if (queue.none { it.first == potentialDependentInfo.fqName }) {
                                        // currentClassInfo here is the class *that currentFqName depends on*,
                                        // but for the queue, we need the ClassInfo of currentFqName itself,
                                        // or rather, the ClassInfo of potentialDependentInfo to be used in the next iteration's "currentClassInfo"
                                        // The second element of the pair in queue is `ClassInfo?` of the class it depends on.
                                        // So, it should be potentialDependentInfo depending on currentFqName.
                                        // The original queue.add was (dependent, dependency_it_depends_on)
                                        // Here, potentialDependentInfo is the dependent, currentFqName is the dependency.
                                        // We need ClassInfo for currentFqName if not null.
                                        val dependencyClassInfo = allProjectClasses[currentFqName]
                                        queue.add(potentialDependentInfo.fqName to dependencyClassInfo)
                                    }
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
