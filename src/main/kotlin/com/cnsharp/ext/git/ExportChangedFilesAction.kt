package com.cnsharp.ext.git

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.vcs.log.VcsLogDataKeys
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.JComponent
import javax.swing.JPanel

private const val KEY_EXTENSIONS = "com.cnsharp.ext.git.ExportChangedFiles.extensions"
private const val KEY_OUTPUT_DIR = "com.cnsharp.ext.git.ExportChangedFiles.outputDir"
private const val KEY_AS_ZIP = "com.cnsharp.ext.git.ExportChangedFiles.asZip"

class ExportChangedFilesAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selection = e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION) ?: return
        val commits = selection.commits
        if (commits.size < 2) return
        val basePath = project.basePath ?: return
        val hashes = commits.map { it.hash.asString() }

        ApplicationManager.getApplication().invokeLater {
            val dialog = ExportChangedFilesDialog(project)
            if (!dialog.showAndGet()) return@invokeLater

            val extensions = dialog.extensions.trim()
            val outputDir = dialog.outputDir.trim()
            val asZip = dialog.asZip
            if (outputDir.isEmpty()) return@invokeLater

            val props = PropertiesComponent.getInstance()
            props.setValue(KEY_EXTENSIONS, extensions)
            props.setValue(KEY_OUTPUT_DIR, outputDir)
            props.setValue(KEY_AS_ZIP, asZip.toString())

            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Export Changed Files", false) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        indicator.text = "Resolving commit range..."
                        val logResult = runGit("git", "log", "--oneline", *hashes.toTypedArray(), dir = basePath)
                        val orderedHashes = logResult.output.lines()
                            .map { it.trim().split(" ").first() }
                            .filter { h -> hashes.any { it.startsWith(h) || h.startsWith(it.take(7)) } }

                        val newest = orderedHashes.firstOrNull() ?: hashes[0]
                        val oldest = orderedHashes.lastOrNull() ?: hashes[1]

                        indicator.text = "Collecting changed files..."
                        val diffResult = runGit(
                            "git", "diff", "--name-only", "--diff-filter=ACMR",
                            "${oldest}~1", newest,
                            dir = basePath
                        )

                        if (diffResult.exitCode != 0 || diffResult.output.isEmpty()) {
                            ApplicationManager.getApplication().invokeLater {
                                Messages.showInfoMessage(project, "No changed files found in the selected range.", "Export Changed Files")
                            }
                            return
                        }

                        val extList = extensions.split(Regex("[,\\s]+")).map { it.trim().trimStart('.') }.filter { it.isNotEmpty() }
                        val allFiles = diffResult.output.lines().filter { it.isNotBlank() }
                        val filteredFiles = if (extList.isEmpty()) allFiles
                        else allFiles.filter { f -> extList.any { ext -> f.endsWith(".$ext") } }

                        if (filteredFiles.isEmpty()) {
                            ApplicationManager.getApplication().invokeLater {
                                Messages.showInfoMessage(project, "No files matched the specified extensions.", "Export Changed Files")
                            }
                            return
                        }

                        val shortNewest = newest.take(7)
                        val shortOldest = oldest.take(7)
                        val projectName = project.name

                        if (asZip) {
                            indicator.text = "Creating zip archive..."
                            val zipFile = File(outputDir, "${projectName}-${shortOldest}-${shortNewest}.zip")
                            ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                                for (relativePath in filteredFiles) {
                                    indicator.text = "Adding: $relativePath"
                                    val file = File(basePath, relativePath)
                                    if (!file.exists()) continue
                                    zip.putNextEntry(ZipEntry(relativePath))
                                    file.inputStream().use { it.copyTo(zip) }
                                    zip.closeEntry()
                                }
                            }
                            ApplicationManager.getApplication().invokeLater {
                                Messages.showInfoMessage(project, "Exported ${filteredFiles.size} file(s) to:\n${zipFile.absolutePath}", "Export Changed Files")
                            }
                        } else {
                            indicator.text = "Copying files..."
                            val destRoot = File(outputDir, "${projectName}-${shortOldest}-${shortNewest}")
                            for (relativePath in filteredFiles) {
                                indicator.text = "Copying: $relativePath"
                                val src = File(basePath, relativePath)
                                if (!src.exists()) continue
                                val dest = File(destRoot, relativePath)
                                dest.parentFile?.mkdirs()
                                src.copyTo(dest, overwrite = true)
                            }
                            ApplicationManager.getApplication().invokeLater {
                                Messages.showInfoMessage(project, "Exported ${filteredFiles.size} file(s) to:\n${destRoot.absolutePath}", "Export Changed Files")
                            }
                        }
                    } catch (ex: Exception) {
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showErrorDialog(project, "Export failed: ${ex.message}", "Export Changed Files")
                        }
                    }
                }
            })
        }
    }

    override fun update(e: AnActionEvent) {
        val selection = e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION)
        val count = selection?.commits?.size ?: 0
        e.presentation.isEnabledAndVisible = e.project != null && count >= 2
    }
}

class ExportChangedFilesDialog(project: Project) : DialogWrapper(project) {

    private val props = PropertiesComponent.getInstance()
    private val extensionsField = JBTextField(30).apply {
        text = props.getValue(KEY_EXTENSIONS, "")
    }
    private val outputDirField = TextFieldWithBrowseButton().apply {
        text = props.getValue(KEY_OUTPUT_DIR, "")
    }
    private val zipCheckBox = JBCheckBox("Export as zip", props.getValue(KEY_AS_ZIP, "true").toBoolean())

    val extensions: String get() = extensionsField.text
    val outputDir: String get() = outputDirField.text
    val asZip: Boolean get() = zipCheckBox.isSelected

    init {
        title = "Export Changed Files"
        outputDirField.addBrowseFolderListener(
            "Select Output Directory", null, project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
        )
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.preferredSize = Dimension(500, 120)
        val gc = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4)
            fill = GridBagConstraints.HORIZONTAL
        }

        gc.gridx = 0; gc.gridy = 0; gc.weightx = 0.0
        panel.add(JBLabel("File extensions (e.g. vue ts js, leave empty for all):"), gc)
        gc.gridx = 1; gc.weightx = 1.0
        panel.add(extensionsField, gc)

        gc.gridx = 0; gc.gridy = 1; gc.weightx = 0.0
        panel.add(JBLabel("Output directory:"), gc)
        gc.gridx = 1; gc.weightx = 1.0
        panel.add(outputDirField, gc)

        gc.gridx = 0; gc.gridy = 2; gc.weightx = 0.0; gc.gridwidth = 2
        panel.add(zipCheckBox, gc)

        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = extensionsField
}
