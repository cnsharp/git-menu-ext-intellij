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
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

private const val KEY_KEYWORD = "com.cnsharp.ext.git.DeleteBranches.keyword"

class DeleteBranchesAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return
        val hasRemotes = runGit("bash", "-c", "git branch -r", dir = basePath).output.isNotEmpty()
        val dialog = DeleteBranchesDialog(project, hasRemotes)
        if (dialog.showAndGet()) {
            val keyword = dialog.keyword.trim()
            if (keyword.isNotEmpty()) {
                PropertiesComponent.getInstance().setValue(KEY_KEYWORD, keyword)
                deleteBranches(project, keyword, dialog.deleteRemote)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    private fun deleteBranches(project: Project, keyword: String, deleteRemote: Boolean = false) {
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

                    // Find matching remote branches if needed
                    val remoteBranchList = if (deleteRemote) {
                        val remoteBranches = runGit("bash", "-c", "git branch -r | grep '$keyword'", dir = basePath).output
                        remoteBranches.lines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.contains("HEAD ->") }
                            .mapNotNull { remote ->
                                // Convert "origin/foo" -> Pair("origin", "foo")
                                val parts = remote.split("/", limit = 2)
                                if (parts.size == 2) Pair(parts[0], parts[1]) else null
                            }
                    } else emptyList()

                    val skippedNote = if (currentBranch.contains(keyword)) "\n\n(Skipping current branch: $currentBranch)" else ""
                    val remoteNote = if (remoteBranchList.isNotEmpty())
                        "\n\nRemote branches to delete:\n${remoteBranchList.joinToString("\n") { "${it.first}/${it.second}" }}"
                    else ""

                    var confirmed = false
                    ApplicationManager.getApplication().invokeAndWait {
                        val confirm = Messages.showOkCancelDialog(
                            project,
                            "The following local branches will be deleted:\n\n${branchList.joinToString("\n")}$skippedNote$remoteNote",
                            "Confirm Branch Deletion",
                            "Delete",
                            "Cancel",
                            Messages.getWarningIcon()
                        )
                        confirmed = confirm == Messages.OK
                    }
                    if (!confirmed) return

                    indicator.text = "Deleting local branches..."
                    val result = runGit(*(listOf("git", "branch", "-D") + branchList).toTypedArray(), dir = basePath)

                    if (deleteRemote && remoteBranchList.isNotEmpty()) {
                        indicator.text = "Deleting remote branches..."
                        // Group by remote name and batch delete per remote
                        remoteBranchList.groupBy { it.first }.forEach { (remote, pairs) ->
                            val refspecs = pairs.map { ":refs/heads/${it.second}" }
                            runGit(*(listOf("git", "push", remote) + refspecs).toTypedArray(), dir = basePath)
                        }
                    }

                    refreshGitRepos(project)

                    ApplicationManager.getApplication().invokeLater {
                        if (result.exitCode == 0) {
                            val remoteMsg = if (remoteBranchList.isNotEmpty())
                                "\n\nRemote branches deleted:\n${remoteBranchList.joinToString("\n") { "${it.first}/${it.second}" }}"
                            else ""
                            Messages.showInfoMessage(project, "Deleted branches:\n\n${result.output}$remoteMsg", "Delete Branches")
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

class DeleteBranchesDialog(project: Project, hasRemotes: Boolean) : DialogWrapper(project) {

    private val textField = JBTextField(30).apply {
        text = PropertiesComponent.getInstance().getValue(KEY_KEYWORD, "")
    }
    private val deleteRemoteCheckBox = JBCheckBox("Also delete remote branches (if any)").apply {
        isEnabled = hasRemotes
    }

    val keyword: String get() = textField.text
    val deleteRemote: Boolean get() = deleteRemoteCheckBox.isSelected

    init {
        title = "Delete Branches"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.preferredSize = Dimension(400, 90)

        val inputPanel = JPanel(BorderLayout(8, 4))
        inputPanel.add(JBLabel("Branch keyword (e.g. release-):"), BorderLayout.NORTH)
        inputPanel.add(textField, BorderLayout.CENTER)

        inputPanel.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.add(inputPanel)
        panel.add(Box.createVerticalStrut(8))
        deleteRemoteCheckBox.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.add(deleteRemoteCheckBox)
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = textField
}
