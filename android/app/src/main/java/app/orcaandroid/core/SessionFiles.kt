package app.orcaandroid.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The files of the saved session in [dir]: the project (`session.3mf`) and its info (`session.json`) form a pair.
 * A new pair is written next to them and swapped in by [commit], so a failed save keeps the old pair. A pair the
 * app could not open is moved aside by [quarantine] (one generation) instead of being overwritten or deleted.
 * No Android or engine types, so it runs in JVM tests.
 */
class SessionFiles(private val dir: File) {
    val project get() = File(dir, "session.3mf")
    val info get() = File(dir, "session.json")
    val tmpProject get() = File(dir, "session.tmp.3mf")
    val tmpInfo get() = File(dir, "session.tmp.json")
    private val badProject get() = File(dir, "session.bad.3mf")
    private val badInfo get() = File(dir, "session.bad.json")
    private val oldProject get() = File(dir, "session.old.3mf")

    /**
     * Makes the project the caller wrote to [tmpProject] and [infoJson] the session. Returns false, with the previous
     * pair still in place, when the temporary project is missing or a step fails.
     */
    fun commit(infoJson: String): Boolean {
        if (!tmpProject.isFile) return false
        val hadProject = project.isFile
        return try {
            dir.mkdirs()
            tmpInfo.writeText(infoJson)
            oldProject.delete()
            if (hadProject && !move(project, oldProject)) return false.also { tmpInfo.delete() }
            if (!move(tmpProject, project) || !move(tmpInfo, info)) {
                // Put the previous project back; its info was not touched yet.
                project.delete()
                if (hadProject) move(oldProject, project)
                tmpInfo.delete()
                false
            } else {
                oldProject.delete()
                true
            }
        } catch (e: Exception) {
            if (hadProject && !project.isFile) move(oldProject, project)
            tmpInfo.delete()
            false
        }
    }

    /** Moves the pair to `session.bad.*`, replacing an older bad pair. Does nothing when there is no pair. */
    fun quarantine() {
        if (!project.exists() && !info.exists()) return
        badProject.delete(); badInfo.delete()
        if (project.exists()) move(project, badProject)
        if (info.exists()) move(info, badInfo)
        // A file that would not move must not stay behind as the "current" session.
        project.delete(); info.delete()
    }

    /** Deletes the session pair and the leftovers of an interrupted save. */
    fun clear() {
        project.delete(); info.delete(); tmpProject.delete(); tmpInfo.delete(); oldProject.delete()
    }

    fun hasBad(): Boolean = badProject.isFile

    /** Moves the quarantined pair back as the session (replacing any current one). False when there is none. */
    fun restoreBad(): Boolean {
        if (!hasBad()) return false
        project.delete(); info.delete()
        if (!move(badProject, project)) return false
        if (badInfo.exists()) move(badInfo, info)
        return true
    }

    fun discardBad() {
        badProject.delete(); badInfo.delete()
    }

    private fun move(from: File, to: File): Boolean = try {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        true
    } catch (e: Exception) {
        false
    }
}
