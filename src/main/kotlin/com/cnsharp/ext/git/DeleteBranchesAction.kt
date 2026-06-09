package com.cnsharp.ext.git

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

private const val KEY_KEYWORD = "com.cnsharp.ext.git.DeleteBranches.keyword"

class DeleteBranchesAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dialog = DeleteBranchesDialog(project)
        if (dialog.showAndGet()) {
            val keyword = dialog.keyword.trim()
            if (keyword.isNotEmpty()) {
                PropertiesComponent.getInstance().setValue(KEY_KEYWORD, keyword)
                deleteBranches(project, keyword)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    private fun deleteBranches(project: Project, keyword: String) {
        val basePath = project.basePath ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Delete Branches", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Listing branches..."
                    val branches = runGit("bash", "-c", "git branch | grep '$keyword'", dir = basePath).output

                    if (branches.isEmpty()) {
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showInfoMessage(project, "No branches found matching: $keyword", "Delete Branches")
                        }
                        return
                    }

                    val currentBranch = branches.lines()
                        .firstOrNull { it.trim().startsWith("* ") }
                        ?.trim()?.removePrefix("* ") ?: ""
                    val branchList = branches.lines()
                        .map { it.trim().removePrefix("* ") }
                        .filter { it.isNotEmpty() && it != currentBranch }

                    if (branchList.isEmpty()) {
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showInfoMessage(project, "No branches to delete (only the current branch '$currentBranch' matched).", "Delete Branches")
                        }
                        return
                    }

                    val skippedNote = if (currentBranch.contains(keyword)) "\n\n(Skipping current branch: $currentBranch)" else ""

                    var confirmed = false
                    ApplicationManager.getApplication().invokeAndWait {
                        val confirm = Messages.showOkCancelDialog(
                            project,
                            "The following branches will be deleted:\n\n${branchList.joinToString("\n")}$skippedNote",
                            "Confirm Branch Deletion",
                            "Delete",
                            "Cancel",
                            Messages.getWarningIcon()
                        )
                        confirmed = confirm == Messages.OK
                    }
                    if (!confirmed) return

                    indicator.text = "Deleting branches..."
                    val result = runGit(*(listOf("git", "branch", "-D") + branchList).toTypedArray(), dir = basePath)

                    refreshGitRepos(project)

                    ApplicationManager.getApplication().invokeLater {
                        if (result.exitCode == 0) {
                            Messages.showInfoMessage(project, "Deleted branches:\n\n${result.output}", "Delete Branches")
                        } else {
                            Messages.showErrorDialog(project, "Error deleting branches:\n\n${result.output}", "Delete Branches")
                        }
                    }
                } catch (ex: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, "Failed to execute git command: ${ex.message}", "Delete Branches")
                    }
                }
            }
        })
    }
}

class DeleteBranchesDialog(project: Project) : DialogWrapper(project) {

    private val textField = JBTextField(30).apply {
        text = PropertiesComponent.getInstance().getValue(KEY_KEYWORD, "")
    }

    val keyword: String get() = textField.text

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
