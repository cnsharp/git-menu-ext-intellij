package com.cnsharp.ext.git

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Key
import java.awt.Window
import java.awt.datatransfer.StringSelection
import javax.swing.JTree

private val BRANCH_NAME_KEY = Key.create<String>("CopyBranchName.branchName")

class CopyBranchNameAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        if (e.project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val name = findSelectedBranchName()
        e.presentation.isEnabledAndVisible = name != null
        e.presentation.putClientProperty(BRANCH_NAME_KEY, name)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val name = e.presentation.getClientProperty(BRANCH_NAME_KEY) ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(name))
    }

    private fun findSelectedBranchName(): String? {
        for (window in Window.getWindows()) {
            if (!window.isShowing) continue
            val trees = mutableListOf<JTree>()
            findComponents(window, JTree::class.java, trees)
            for (tree in trees) {
                val node = tree.selectionPath?.lastPathComponent ?: continue
                val className = node.javaClass.name
                // Match GitLocalBranch, GitRemoteBranch, or any git4idea branch type
                if (className.contains("GitBranch") || className.contains("GitLocalBranch") || className.contains("GitRemoteBranch")) {
                    try {
                        val name = node.javaClass.getMethod("getName").invoke(node) as? String
                        if (!name.isNullOrBlank()) return name
                    } catch (_: Exception) {}
                }
            }
        }
        return null
    }

    private fun <T> findComponents(container: java.awt.Container, clazz: Class<T>, result: MutableList<T>) {
        for (comp in container.components) {
            if (clazz.isInstance(comp)) {
                @Suppress("UNCHECKED_CAST")
                result.add(comp as T)
            }
            if (comp is java.awt.Container) {
                findComponents(comp, clazz, result)
            }
        }
    }
}
