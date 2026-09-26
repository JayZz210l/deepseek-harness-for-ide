package com.deepseek.dsh.ide.process

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Link-aware file operations shared by the profile transactions
 * ([DshPluginReset], [DshPluginSync]).
 *
 * A web profile's `node_modules` is link-heavy. Two traps follow, and both the
 * deletion and the move-aside paths have to avoid them.
 */
internal object DshProfileFiles {

    /**
     * Package projections a link-backend DSH release writes into a profile:
     * `<profile>/.dsh-module-fallback/node_modules/<name>` holds junctions whose
     * targets are the profile's real `node_modules/<name>` entries. DSH removes
     * the same directory itself on every profile load, so it is never state worth
     * keeping or restoring.
     */
    const val LINK_PROJECTION_DIR = ".dsh-module-fallback"

    /**
     * Deletes a tree without ever following a link out of it.
     *
     * 1. Renaming a profile aside makes every projection junction dangle, and a
     *    dangling junction cannot be traversed: `Files.walk` reports that as an
     *    `UncheckedIOException` from its iterator, which aborts the deletion and
     *    strands the directory. A visitor instead surfaces the entry through
     *    `visitFileFailed`, and the path itself is still deletable, so it is
     *    removed as a leaf.
     * 2. `walkFileTree` happily recurses *through* a junction whose target is
     *    still reachable, so a naive visitor would delete the target's contents
     *    as well — for a link into a shared store, that is every other profile's
     *    copy. Junctions are therefore never descended into: a Windows junction
     *    reports itself as neither a regular file nor a plain directory
     *    (`isOther`), which is the reliable test, since it reports
     *    `isSymbolicLink = false` even for a symlinked directory.
     */
    fun deleteTree(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    // A link is a single entry: remove it, never the tree behind it.
                    if (attrs.isOther) Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
                    deleteIfPresent(file, error)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                    if (error != null) {
                        deleteIfPresent(dir, error)
                    } else {
                        Files.deleteIfExists(dir)
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    /**
     * Removes `<profile>/.dsh-module-fallback` while the profile is still at its
     * final path, so the junctions inside it cannot be left dangling when the
     * profile is moved aside afterwards.
     */
    fun discardLinkProjections(profile: Path, log: (String) -> Unit) {
        val owned = profile.resolve(LINK_PROJECTION_DIR)
        if (!Files.exists(owned, LinkOption.NOFOLLOW_LINKS)) return
        deleteTree(owned)
        log("Removed stale link projections: $owned")
    }

    /**
     * Deletes an entry whose contents could not be read. An untraversable
     * junction is the expected case and disappears here; a real failure (a lock,
     * a permission) survives the attempt and is reported.
     */
    private fun deleteIfPresent(path: Path, cause: IOException) {
        try {
            Files.deleteIfExists(path)
        } catch (failure: IOException) {
            failure.addSuppressed(cause)
            throw failure
        }
    }
}
