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

    fun analyzeCurrentEditor(depth: Int): Pair<Map<String, Node>, List<Edge>>? {
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

        return analyzeDependencies(targetClass.fqName, depth)
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


    fun analyzeDependenciesByPaths(paths: List<String>, depth: Int): Pair<Map<String, Node>, List<Edge>>? {
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

        return analyzeDependencies(targetFqNames, depth)
    }

    private fun analyzeDependencies(targetFqName: String, depth: Int): Pair<Map<String, Node>, List<Edge>> {
        return analyzeDependencies(listOf(targetFqName), depth)
    }
    private fun analyzeDependencies(targetFqNames: List<String>, depth: Int): Pair<Map<String, Node>, List<Edge>> {
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

            for (classInfo in allProjectClasses.values) {
                val psiClass = classInfo.psiClass ?: continue
                val importList = (psiClass.containingFile as? PsiJavaFile)?.importList ?: continue
                importList.allImportStatements.forEach { importStatement ->
                    importStatement.resolve()?.let { resolvedElement ->
                        if (resolvedElement is PsiClass) {
                            val fqName = resolvedElement.qualifiedName
                            if (fqName != null && allProjectClasses.containsKey(fqName)) {
                                val file = resolvedElement.containingFile.virtualFile
                                if (file != null && ProjectRootManager.getInstance(project).fileIndex.isInContent(file)) {
                                    directDependents.getOrPut(classInfo.fqName) { mutableSetOf() }.add(fqName)
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
        val queue: Queue<Pair<String, Int>> = LinkedList()

        for (targetFqName in targetFqNames) {
            val targetSimpleName = targetFqName.substringAfterLast('.')
            nodes[targetFqName] = Node(targetSimpleName, targetFqName, 0, 0)
            processed.add(targetFqName)
            queue.add(targetFqName to 0)
        }

        while (queue.isNotEmpty()) {
            val (currentFqName, currentDepth) = queue.poll()

            if (currentDepth >= depth) continue

            val currentClassInfo = allProjectClasses[currentFqName] ?: continue
            nodes.putIfAbsent(currentFqName, Node(currentClassInfo.simpleName, currentFqName, 0, 0))

            directDependents[currentFqName]?.forEach { dependentFqName ->
                val dependentClassInfo = allProjectClasses[dependentFqName] ?: return@forEach
                nodes.putIfAbsent(dependentFqName, Node(dependentClassInfo.simpleName, dependentFqName, 0, 0))
                if (edges.none { it.from == currentFqName && it.to == dependentFqName }) {
                    edges.add(Edge(currentFqName, dependentFqName))
                }
                if (dependentFqName !in processed) {
                    processed.add(dependentFqName)
                    queue.add(dependentFqName to currentDepth + 1)
                }
            }
        }
        return Pair(nodes, edges.distinct())
    }

    fun analyzeAllProjectClasses(depth: Int): Pair<Map<String, Node>, List<Edge>> {
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
        return analyzeDependencies(allFqNames, depth)
    }

     fun findClassFile(fqName: String): VirtualFile? {
        return classCache[fqName]?.file ?: ApplicationManager.getApplication().runReadAction<VirtualFile?> {
            val psiFacade = JavaPsiFacade.getInstance(project)
            val psiClass = psiFacade.findClass(fqName, GlobalSearchScope.allScope(project))
            psiClass?.containingFile?.virtualFile
        }
    }
}
