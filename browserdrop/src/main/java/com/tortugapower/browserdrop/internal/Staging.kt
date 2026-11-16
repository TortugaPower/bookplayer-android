package com.tortugapower.browserdrop.internal

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * One session's staging.
 *
 * [files] (`<root>/<uuid>`) holds the complete uploads at their relative paths, and its sibling [incoming]
 * (`<root>/<uuid>.incoming`) the uploads still arriving, one file each. An upload only moves into [files] once
 * complete, so [files] never holds a partial file, whatever happened to the connection, and finish can hand it
 * over as it is.
 *
 * Not thread-safe: the server serializes every call that reads or changes what's stored.
 */
internal class Staging private constructor(
    /** The session folder: every complete upload, at its relative path. */
    val files: File,
    /** Uploads still arriving. */
    val incoming: File,
    /**
     * Whether the volume treats `Book` and `book` as one name, so names that differ only in case conflict.
     * Android's internal storage is case-sensitive; a Mac's volume (where unit tests run) usually isn't.
     */
    val namesIgnoreCase: Boolean
) {
    /** Storing the path would replace something other than an earlier upload of exactly that path. */
    class PathConflict : IOException("path conflict")

    fun makeIncomingFile(): File {
        val file = File(incoming, UUID.randomUUID().toString())
        if (!file.createNewFile()) throw IOException("Couldn't create $file")
        return file
    }

    /**
     * Moves a finished upload to its relative path. An earlier upload of exactly that path is replaced, so a
     * retry never makes a duplicate; anything else already there under that name throws [PathConflict].
     */
    fun commit(incomingFile: File, relativePath: String) {
        if (conflicts(relativePath)) throw PathConflict()
        // The volume has the last word on which names are the same, so nothing here relies on a name being free:
        // each folder and the file must end up stored under exactly their own names, or it's refused rather than
        // land in, or replace, something else.
        val segments = relativePath.split("/")
        var folder = files
        for (segment in segments.dropLast(1)) {
            folder = File(folder, segment)
            if (!folder.mkdir()) {
                if (!folder.exists()) throw IOException("Couldn't create $folder")
                if (segment !in storedNames(folder.parentFile!!) || !folder.isDirectory) throw PathConflict()
            }
        }
        val name = segments.last()
        val destination = File(folder, name)
        if (name in storedNames(folder)) {
            Files.move(
                incomingFile.toPath(), destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
            )
        } else {
            try {
                Files.move(incomingFile.toPath(), destination.toPath())
            } catch (e: FileAlreadyExistsException) {
                throw PathConflict()
            }
        }
    }

    /**
     * Whether storing [relativePath] would replace something other than an earlier upload of exactly that path.
     * Each part of the path already on disk must have exactly that name, and be a folder for the parents and a
     * file for the last part.
     */
    fun conflicts(relativePath: String): Boolean {
        val segments = relativePath.split("/")
        var parent = files
        segments.forEachIndexed { index, segment ->
            // The names as stored, rather than a lookup by name, which a case-insensitive volume would answer
            // for `book` with `Book`.
            val names = parent.list()
                ?: return parent.exists() // No folder yet, so nothing in it; one that can't be listed refuses.
            if (segment !in names) {
                // Not there; or there under another name the volume treats as the same (`Book` for `book`).
                return namesIgnoreCase && names.any { it.equals(segment, ignoreCase = true) }
            }
            val item = File(parent, segment)
            // A folder where the file goes, or a file where a folder goes.
            if (item.isDirectory == (index == segments.size - 1)) return true
            parent = item
        }
        return false
    }

    fun containsFile(relativePath: String): Boolean = File(files, relativePath).isFile

    /** Whether [files] is known to hold nothing. A folder that can't be listed doesn't count as empty. */
    val isEmpty: Boolean get() = files.list()?.isEmpty() == true

    fun remove() {
        files.deleteRecursively()
        removeIncoming()
    }

    fun removeIncoming() {
        incoming.deleteRecursively()
    }

    /**
     * Deletes a file, or a folder with everything in it, then the folders that leaves empty, so an emptied folder
     * isn't handed over as an item of its own.
     */
    fun removeItem(relativePath: String) {
        val item = File(files, relativePath)
        if (item.exists() && !item.deleteRecursively()) throw IOException("Couldn't delete $relativePath")
        var parent = item.parentFile
        while (parent != null && parent != files && parent.path.startsWith(files.path + File.separator) &&
            parent.list()?.isEmpty() == true
        ) {
            if (!parent.delete()) throw IOException("Couldn't delete $parent")
            parent = parent.parentFile
        }
    }

    private fun storedNames(folder: File): Array<String> = folder.list() ?: throw IOException("Couldn't list $folder")

    companion object {
        fun create(parent: File): Staging {
            val name = UUID.randomUUID().toString()
            val files = File(parent, name)
            val incoming = File(parent, "$name.incoming")
            if (!incoming.mkdirs() && !incoming.isDirectory) throw IOException("Couldn't create $incoming")
            if (!files.mkdirs() && !files.isDirectory) throw IOException("Couldn't create $files")
            return Staging(files, incoming, namesIgnoreCase = probeIgnoresCase(files))
        }

        /**
         * Asks the volume rather than assuming: a lowercase probe is looked up in uppercase. Its name starts with
         * a dot, which no upload can use, and it's deleted straight away.
         */
        private fun probeIgnoresCase(folder: File): Boolean {
            val probe = File(folder, ".case-probe")
            return try {
                probe.createNewFile()
                File(folder, ".CASE-PROBE").exists()
            } catch (e: IOException) {
                true // Assumed when the volume doesn't say, as on iOS: refusing is the safe side.
            } finally {
                probe.delete()
            }
        }
    }
}
