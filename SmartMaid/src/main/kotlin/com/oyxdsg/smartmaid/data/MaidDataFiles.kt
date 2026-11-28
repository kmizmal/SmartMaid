package com.oyxdsg.smartmaid.data

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/** World-local snapshots, not durable claims that an entity is alive. Called on the server thread. */
object MaidDataFiles {
    @JvmStatic
    fun dataFile(dir: Path, owner: UUID, maidId: UUID): Path = dir.resolve("$owner-$maidId.dat")

    private fun selection(dir: Path, owner: UUID): Path = dir.resolve("$owner.latest")

    internal fun hasData(file: Path): Boolean = Files.isRegularFile(file) && Files.size(file) > 0L

    internal fun selectedSnapshotId(dir: Path, owner: UUID): UUID? {
        val index = selection(dir, owner)
        if (Files.notExists(index)) return null
        val maidId = try {
            UUID.fromString(Files.readString(index).trim())
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid maid snapshot selection", e)
        }
        val selected = dataFile(dir, owner, maidId)
        if (!hasData(selected)) throw IOException("Missing selected maid snapshot: $selected")
        return maidId
    }

    internal fun recoverySource(dir: Path, legacyDir: Path, owner: UUID): Path? =
        selectedSnapshotId(dir, owner)?.let { dataFile(dir, owner, it) } ?: legacySource(dir, legacyDir, owner)

    internal fun pruneSupersededSnapshots(
        dir: Path, owner: UUID, selected: UUID, previous: UUID?, protected: Set<UUID>
    ): List<Path> = pruneSupersededSnapshots(dir, owner, selected, previous, protected) { Files.deleteIfExists(it) }

    internal fun pruneSupersededSnapshots(
        dir: Path, owner: UUID, selected: UUID, previous: UUID?, protected: Set<UUID>, delete: (Path) -> Unit
    ): List<Path> {
        if (selectedSnapshotId(dir, owner) != selected ||
            !Files.isRegularFile(dataFile(dir, owner, selected), NOFOLLOW_LINKS)
        ) return emptyList()

        val failed = mutableListOf<Path>()
        Files.newDirectoryStream(dir, "$owner-*.dat").use { entries ->
            for (entry in entries) {
                val maidId = snapshotId(entry, owner) ?: continue
                if (maidId == selected || maidId == previous || maidId in protected ||
                    !Files.isRegularFile(entry, NOFOLLOW_LINKS)
                ) continue
                try {
                    delete(entry)
                } catch (_: IOException) {
                    failed.add(entry)
                } catch (_: SecurityException) {
                    failed.add(entry)
                }
            }
        }
        return failed
    }

    private fun snapshotId(file: Path, owner: UUID): UUID? {
        val name = file.fileName.toString()
        val rawId = name.removePrefix("$owner-").removeSuffix(".dat")
        if (rawId.length != 36) return null
        val id = try {
            UUID.fromString(rawId)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return id.takeIf { name == "$owner-$id.dat" }
    }

    private fun legacySource(dir: Path, legacyDir: Path, owner: UUID): Path? =
        sequenceOf(dir.resolve("$owner.dat"), legacyDir.resolve("$owner.dat"))
            .firstOrNull { !Files.notExists(it) }

    @JvmStatic
    fun migrateLegacy(dir: Path, legacyDir: Path, owner: UUID, maidId: UUID): Path =
        prepare(dataFile(dir, owner, maidId)) { legacySource(dir, legacyDir, owner) }

    @JvmStatic
    fun prepareSummon(dir: Path, legacyDir: Path, owner: UUID, maidId: UUID): Path =
        prepare(dataFile(dir, owner, maidId)) { recoverySource(dir, legacyDir, owner) }

    private inline fun prepare(target: Path, source: () -> Path?): Path {
        if (hasData(target)) return target
        val previous = source() ?: return target
        if (!hasData(previous)) throw IOException("Unreadable maid snapshot: $previous")
        replaceFile(target) { Files.copy(previous, it, REPLACE_EXISTING) }
        return target
    }

    @JvmStatic
    fun selectSnapshot(dir: Path, owner: UUID, maidId: UUID) {
        require(hasData(dataFile(dir, owner, maidId))) { "Cannot select an empty maid snapshot" }
        replaceFile(selection(dir, owner)) { Files.writeString(it, maidId.toString()) }
    }

    internal inline fun replaceFile(target: Path, write: (Path) -> Unit) {
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
        try {
            write(temp)
            try {
                Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
