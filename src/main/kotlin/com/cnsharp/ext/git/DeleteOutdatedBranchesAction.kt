package com.cnsharp.ext.git

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

data class OutdatedBranch(val name: String, val isMerged: Boolean)

class DeleteOutdatedBranchesAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Delete Outdated Branches", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Fetching remote status..."
                    runGit("git", "fetch", "--prune", dir = basePath)

                    indicator.text = "Scanning branches..."
                    val currentBranch = runGit("git", "rev-parse", "--abbrev-ref", "HEAD", dir = basePath).output
                    val vvLines = runGit("git", "branch", "-vv", dir = basePath).output.lines().filter { it.isNotBlank() }
                    val mergedBranches = runGit("git", "branch", "--merged", dir = basePath).output
                        .lines()
                        .map { it.trim().removePrefix("* ") }
                        .toSet()

                    val outdated = vvLines.mapNotNull { line ->
                        val trimmed = line.trim()
                        val name = trimmed.removePrefix("* ").trim().split(Regex("\\s+")).firstOrNull() ?: return@mapNotNull null
                        if (name == currentBranch) return@mapNotNull null
                        val isGone = Regex("\\[.*?:\\s*gone]").containsMatchIn(trimmed)
                        if (isGone) OutdatedBranch(name, mergedBranches.contains(name)) else null
                    }

                    if (outdated.isEmpty()) {
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showInfoMessage(project, "No outdated branches found.", "Delete Outdated Branches")
                        }
                        return
                    }

                    var selected: List<OutdatedBranch> = emptyList()
                    ApplicationManager.getApplication().invokeAndWait {
                        val dialog = DeleteOutdatedBranchesDialog(project, outdated)
                        if (dialog.showAndGet()) {
                            selected = dialog.getSelectedBranches()
                        }
                    }
                    if (selected.isEmpty()) return

                    indicator.text = "Deleting branches..."
                    val results = StringBuilder()
                    var hasError = false
                    for (branch in selected) {
                        val flag = if (branch.isMerged) "-d" else "-D"
                        val result = runGit("git", "branch", flag, branch.name, dir = basePath)
                        if (result.exitCode == 0) results.appendLine(result.output)
                        else { results.appendLine("Failed to delete ${branch.name}: ${result.output}"); hasError = true }
                    }

                    refreshGitRepos(project)

                    val msg = results.toString().trim()
                    ApplicationManager.getApplication().invokeLater {
                        if (hasError) Messages.showErrorDialog(project, msg, "Delete Outdated Branches")
                        else Messages.showInfoMessage(project, msg, "Delete Outdated Branches")
                    }
                } catch (ex: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, "Failed to execute git command: ${ex.message}", "Delete Outdated Branches")
                    }
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

class DeleteOutdatedBranchesDialog(
    project: Project,
    private val branches: List<OutdatedBranch>
) : DialogWrapper(project) {

    private val checkBoxList = CheckBoxList<OutdatedBranch>()

    init {
        title = "Delete Outdated Branches"
        init()
    }

    override fun createCenterPanel(): JComponent {
        branches.forEach { branch ->
            val label = if (branch.isMerged) branch.name else "${branch.name}  ⚠ not fully merged"
            checkBoxList.addItem(branch, label, true)
        }

        val panel = JPanel(BorderLayout(8, 8))
        panel.preferredSize = Dimension(520, 320)
        panel.add(
            JBLabel("Branches whose remote tracking is gone (⚠ = not fully merged):"),
            BorderLayout.NORTH
        )
        panel.add(JBScrollPane(checkBoxList), BorderLayout.CENTER)
        return panel
    }

    fun getSelectedBranches(): List<OutdatedBranch> = branches.filter { checkBoxList.isItemSelected(it) }
}
