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
import com.intellij.openapi.vfs.LocalFileSystem
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


    fun analyzeDependenciesByPaths(paths: List<String>): Pair<Map<String, Node>, List<Edge>>? {
        val targetFqNames = paths.mapNotNull { path ->
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(path)
            if (virtualFile != null) {
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                if (psiFile is PsiJavaFile) {
                    getClassInfoFromPsiFile(psiFile)?.fqName
                } else {
                    null
                }
            } else {
                null
            }
        }
        if (targetFqNames.isEmpty()) return null

        return analyzeDependencies(targetFqNames)
    }

    private fun analyzeDependencies(targetFqName: String): Pair<Map<String, Node>, List<Edge>> {
        return analyzeDependencies(listOf(targetFqName))
    }
    private fun analyzeDependencies(targetFqNames: List<String>): Pair<Map<String, Node>, List<Edge>> {
        val allJavaFiles = mutableListOf<PsiJavaFile>()
        ApplicationManager.getApplication().runReadAction {
            ProjectRootManager.getInstance(project).fileIndex.iterateContent { virtualFile ->
                if (virtualFile.fileType is JavaFileType && !virtualFile.isDirectory && virtualFile.isValid) {
                    val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                    if (psiFile is PsiJavaFile) {
                        allJavaFiles.add(psiFile)
                    }
                }
                true
            }
        }

        val directDependents = mutableMapOf<String, MutableSet<String>>()
        val allProjectClasses = mutableMapOf<String, ClassInfo>()

        ApplicationManager.getApplication().runReadAction {
            for (psiJavaFile in allJavaFiles) {
                if (!psiJavaFile.isValid) continue
                getClassInfoFromPsiFile(psiJavaFile)?.let { classInfo ->
                    allProjectClasses[classInfo.fqName] = classInfo
                    classCache[classInfo.fqName] = classInfo
                }
            }

            val psiFacade = JavaPsiFacade.getInstance(project)
            for (targetFqName in targetFqNames) {
                val targetPsiClass = psiFacade.findClass(targetFqName, GlobalSearchScope.allScope(project))
                if (targetPsiClass != null) {
                    val searchScope = GlobalSearchScope.projectScope(project)
                    ReferencesSearch.search(targetPsiClass, searchScope).forEach { psiReference ->
                        val referencingElement = psiReference.element
                        val referencingFile = PsiTreeUtil.getParentOfType(referencingElement, PsiJavaFile::class.java)

                        if (referencingFile != null && referencingFile.isValid) {
                            getClassInfoFromPsiFile(referencingFile)?.let { dependentClassInfo ->
                                if (dependentClassInfo.fqName != targetFqName) {
                                    directDependents.getOrPut(targetFqName) { mutableSetOf() }.add(dependentClassInfo.fqName)
                                }
                            }
                        }
                    }
                }
            }
        }

        val nodes = mutableMapOf<String, Node>()
        val edges = mutableListOf<Edge>()
        val processed = mutableSetOf<String>()
        val queue: Queue<String> = LinkedList()

        for (targetFqName in targetFqNames) {
            val targetSimpleName = targetFqName.substringAfterLast('.')
            nodes[targetFqName] = Node(targetSimpleName, targetFqName, 0, 0)
            processed.add(targetFqName)
            queue.add(targetFqName)
        }

        while (queue.isNotEmpty()) {
            val currentFqName = queue.poll()
            val currentClassInfo = allProjectClasses[currentFqName] ?: continue
            nodes.putIfAbsent(currentFqName, Node(currentClassInfo.simpleName, currentFqName, 0, 0))

            ApplicationManager.getApplication().runReadAction {
                val currentPsiClass = JavaPsiFacade.getInstance(project).findClass(currentFqName, GlobalSearchScope.allScope(project))
                if (currentPsiClass != null) {
                    val searchScope = GlobalSearchScope.projectScope(project)
                    ReferencesSearch.search(currentPsiClass, searchScope).forEach { psiReference ->
                        val referencingElement = psiReference.element
                        val referencingFile = PsiTreeUtil.getParentOfType(referencingElement, PsiJavaFile::class.java)

                        if (referencingFile != null && referencingFile.isValid) {
                            getClassInfoFromPsiFile(referencingFile)?.let { potentialDependentInfo ->
                                if (potentialDependentInfo.fqName != currentFqName) {
                                    nodes.putIfAbsent(potentialDependentInfo.fqName, Node(potentialDependentInfo.simpleName, potentialDependentInfo.fqName, 0, 0))
                                    if (edges.none { it.from == potentialDependentInfo.fqName && it.to == currentFqName }) {
                                        edges.add(Edge(potentialDependentInfo.fqName, currentFqName))
                                    }
                                    if (potentialDependentInfo.fqName !in processed) {
                                        processed.add(potentialDependentInfo.fqName)
                                        queue.add(potentialDependentInfo.fqName)
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

    fun analyzeAllProjectClasses(): Pair<Map<String, Node>, List<Edge>> {
        val allJavaFiles = mutableListOf<PsiJavaFile>()
        ApplicationManager.getApplication().runReadAction {
            ProjectRootManager.getInstance(project).fileIndex.iterateContent { virtualFile ->
                if (virtualFile.fileType is JavaFileType && !virtualFile.isDirectory && virtualFile.isValid) {
                    val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                    if (psiFile is PsiJavaFile) {
                        allJavaFiles.add(psiFile)
                    }
                }
                true
            }
        }

        val allFqNames = allJavaFiles.mapNotNull { getClassInfoFromPsiFile(it)?.fqName }
        return analyzeDependencies(allFqNames)
    }

     fun findClassFile(fqName: String): VirtualFile? {
        return classCache[fqName]?.file ?: ApplicationManager.getApplication().runReadAction<VirtualFile?> {
            val psiFacade = JavaPsiFacade.getInstance(project)
            val psiClass = psiFacade.findClass(fqName, GlobalSearchScope.allScope(project))
            psiClass?.containingFile?.virtualFile
        }
    }
}
