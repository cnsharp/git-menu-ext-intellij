package com.cnsharp.ext.git

import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager
import java.io.File

data class GitResult(val output: String, val exitCode: Int)

fun runGit(vararg cmd: String, dir: String): GitResult {
    val proc = ProcessBuilder(*cmd)
        .directory(File(dir))
        .redirectErrorStream(true)
        .start()
    val output = proc.inputStream.bufferedReader().readText().trim()
    val exitCode = proc.waitFor()
    return GitResult(output, exitCode)
}

fun refreshGitRepos(project: Project) {
    GitRepositoryManager.getInstance(project).repositories.forEach { it.update() }
}
