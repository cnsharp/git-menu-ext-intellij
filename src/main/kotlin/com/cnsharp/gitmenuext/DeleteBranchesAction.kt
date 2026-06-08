package com.cnsharp.gitmenuext

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

class DeleteBranchesAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dialog = DeleteBranchesDialog(project)
        if (dialog.showAndGet()) {
            val keyword = dialog.keyword.trim()
            if (keyword.isNotEmpty()) {
                deleteBranches(project, keyword)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    private fun deleteBranches(project: Project, keyword: String) {
        val basePath = project.basePath ?: return

        try {
            // Step 1: list matching branches
            val listProc = ProcessBuilder("bash", "-c", "git branch | grep '$keyword'")
                .directory(java.io.File(basePath))
                .redirectErrorStream(true)
                .start()
            val branches = listProc.inputStream.bufferedReader().readText().trim()
            listProc.waitFor()

            if (branches.isEmpty()) {
                Messages.showInfoMessage(project, "No branches found matching: $keyword", "Delete Branches")
                return
            }

            val branchList = branches.lines().map { it.trim().removePrefix("* ") }.filter { it.isNotEmpty() }
            val preview = branchList.joinToString("\n")
            val confirm = Messages.showOkCancelDialog(
                project,
                "The following branches will be deleted:\n\n$preview",
                "Confirm Branch Deletion",
                "Delete",
                "Cancel",
                Messages.getWarningIcon()
            )

            if (confirm != Messages.OK) return

            // Step 2: delete branches
            val deleteProc = ProcessBuilder(
                "bash", "-c",
                "git branch | grep '${keyword.replace("'", "'\\''")}' | xargs git branch -D"
            )
                .directory(java.io.File(basePath))
                .redirectErrorStream(true)
                .start()
            val output = deleteProc.inputStream.bufferedReader().readText().trim()
            val exitCode = deleteProc.waitFor()

            if (exitCode == 0) {
                Messages.showInfoMessage(project, "Deleted branches:\n\n$output", "Delete Branches")
            } else {
                Messages.showErrorDialog(project, "Error deleting branches:\n\n$output", "Delete Branches")
            }
        } catch (ex: Exception) {
            Messages.showErrorDialog(project, "Failed to execute git command: ${ex.message}", "Delete Branches")
        }
    }
}

class DeleteBranchesDialog(project: Project) : DialogWrapper(project) {

    private val textField = JBTextField(30)

    val keyword: String
        get() = textField.text

    init {
        title = "Delete Branches"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(8, 8))
        panel.preferredSize = Dimension(400, 60)
        panel.add(JBLabel("Branch keyword (e.g. release-):"), BorderLayout.NORTH)
        panel.add(textField, BorderLayout.CENTER)
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = textField
}
