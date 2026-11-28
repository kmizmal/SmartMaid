package com.oyxdsg.smartmaid.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class MaidSnapshotRetentionTest {
    @TempDir
    lateinit var dir: Path

    private val owner = UUID.fromString("a0000000-0000-0000-0000-000000000001")
    private val otherOwner = UUID.fromString("b0000000-0000-0000-0000-000000000001")
    private val pointer get() = dir.resolve("$owner.latest")
    private val legacyDir get() = dir.resolve("global-legacy")

    @Test
    fun repeatedResummonsKeepSelectedAndPrevious() {
        val ids = (1..6).map(::maidId)
        val legacy = Files.writeString(dir.resolve("$owner.dat"), "legacy inventory")
        assertNull(MaidDataFiles.selectedSnapshotId(dir, owner))
        var previous: UUID? = null
        for ((index, id) in ids.withIndex()) {
            assertEquals(previous, MaidDataFiles.selectedSnapshotId(dir, owner))
            val prepared = MaidDataFiles.prepareSummon(dir, legacyDir, owner, id)
            assertEquals(if (index == 0) "legacy inventory" else "generation $index", Files.readString(prepared))
            Files.writeString(prepared, "generation ${index + 1}")
            MaidDataFiles.selectSnapshot(dir, owner, id)
            assertEquals(emptyList<Path>(), MaidDataFiles.pruneSupersededSnapshots(dir, owner, id, previous, emptySet()))
            previous = id
        }

        assertEquals(setOf(snapshot(ids[4]).fileName, snapshot(ids[5]).fileName), snapshotNames())
        assertEquals(ids[5].toString(), Files.readString(pointer))
        assertEquals("generation 6", Files.readString(snapshot(ids[5])))
        assertEquals("generation 5", Files.readString(snapshot(ids[4])))
        assertEquals("legacy inventory", Files.readString(legacy))
    }

    @Test
    fun prepareDoesNotPrune() {
        val old = putSnapshot(1, "older")
        val selected = putSnapshot(2, "current")
        MaidDataFiles.selectSnapshot(dir, owner, selected)

        val prepared = MaidDataFiles.prepareSummon(dir, legacyDir, owner, maidId(3))

        assertEquals("current", Files.readString(prepared))
        assertEquals("older", Files.readString(snapshot(old)))
        assertEquals("current", Files.readString(snapshot(selected)))
        assertEquals(selected.toString(), Files.readString(pointer))
    }

    @Test
    fun pointerCommitFailureLeavesOldSelectionAndFiles() {
        val old = putSnapshot(1, "older")
        val selected = putSnapshot(2, "current")
        MaidDataFiles.selectSnapshot(dir, owner, selected)
        val new = maidId(3)
        val prepared = MaidDataFiles.prepareSummon(dir, legacyDir, owner, new)

        assertThrows(IOException::class.java) {
            MaidDataFiles.replaceFile(pointer) { staged ->
                Files.writeString(staged, new.toString())
                throw IOException("injected pointer staging failure")
            }
        }

        assertEquals(selected.toString(), Files.readString(pointer))
        assertEquals("older", Files.readString(snapshot(old)))
        assertEquals("current", Files.readString(snapshot(selected)))
        assertEquals("current", Files.readString(prepared))
        assertEquals(setOf(snapshot(old).fileName, snapshot(selected).fileName, prepared.fileName, pointer.fileName), fileNames())
    }

    @Test
    fun pruneFailureDoesNotUndoNewSelection() {
        val blocked = putSnapshot(1, "blocked deletion")
        val deletable = putSnapshot(2, "deletable")
        val previous = putSnapshot(3, "previous")
        val selected = putSnapshot(4, "selected")
        MaidDataFiles.selectSnapshot(dir, owner, previous)
        MaidDataFiles.selectSnapshot(dir, owner, selected)

        val failed = MaidDataFiles.pruneSupersededSnapshots(dir, owner, selected, previous, emptySet()) { candidate ->
            if (candidate == snapshot(blocked)) throw IOException("injected snapshot deletion failure")
            Files.deleteIfExists(candidate)
        }

        assertEquals(listOf(snapshot(blocked)), failed)
        assertEquals("blocked deletion", Files.readString(snapshot(blocked)))
        assertFalse(Files.exists(snapshot(deletable)))
        assertEquals("previous", Files.readString(snapshot(previous)))
        assertEquals("selected", Files.readString(snapshot(selected)))
        assertEquals(selected.toString(), Files.readString(pointer))
    }

    @Test
    fun preservesLegacyOtherOwnerUnknownAndProtectedFiles() {
        val stale = putSnapshot(1, "stale")
        val selected = putSnapshot(2, "selected")
        val protected = putSnapshot(3, "active maid")
        val legacy = Files.writeString(dir.resolve("$owner.dat"), "world legacy")
        Files.createDirectory(legacyDir)
        val global = Files.writeString(legacyDir.resolve("$owner.dat"), "global legacy")
        val other = Files.writeString(dir.resolve("$otherOwner-${maidId(4)}.dat"), "other player")
        val short = Files.writeString(dir.resolve("$owner-1-1-1-1-1.dat"), "unknown short UUID")
        val upper = Files.writeString(dir.resolve("$owner-${maidId(5).toString().uppercase()}.dat"), "unknown uppercase UUID")
        val directory = Files.createDirectory(snapshot(maidId(6)))
        MaidDataFiles.selectSnapshot(dir, owner, selected)

        MaidDataFiles.pruneSupersededSnapshots(dir, owner, selected, null, setOf(protected))

        assertFalse(Files.exists(snapshot(stale)))
        assertEquals("selected", Files.readString(snapshot(selected)))
        assertEquals("active maid", Files.readString(snapshot(protected)))
        assertEquals("world legacy", Files.readString(legacy))
        assertEquals("global legacy", Files.readString(global))
        assertEquals("other player", Files.readString(other))
        assertEquals("unknown short UUID", Files.readString(short))
        assertEquals("unknown uppercase UUID", Files.readString(upper))
        assertTrue(Files.isDirectory(directory))
    }

    @Test
    fun preservesSnapshotSymlinksWhenSupported() {
        val selected = putSnapshot(1, "selected")
        val target = Files.writeString(dir.resolve("unrelated.dat"), "not a snapshot")
        val link = snapshot(maidId(2))
        try {
            Files.createSymbolicLink(link, target)
        } catch (e: IOException) {
            assumeTrue(false, "Symbolic link creation unavailable: ${e.message}")
        } catch (e: UnsupportedOperationException) {
            assumeTrue(false, "Symbolic links unsupported: ${e.message}")
        }
        val stale = putSnapshot(3, "stale")
        MaidDataFiles.selectSnapshot(dir, owner, selected)

        MaidDataFiles.pruneSupersededSnapshots(dir, owner, selected, null, emptySet())

        assertTrue(Files.isSymbolicLink(link))
        assertEquals("not a snapshot", Files.readString(target))
        assertFalse(Files.exists(snapshot(stale)))
    }

    @Test
    fun malformedOrChangedPointerStopsPrune() {
        val older = putSnapshot(1, "older")
        val selected = putSnapshot(2, "expected selected")
        val replacement = putSnapshot(3, "newly selected")
        val before = fileNames()

        assertEquals(emptyList<Path>(), MaidDataFiles.pruneSupersededSnapshots(dir, owner, selected, null, emptySet()))
        assertEquals(before, fileNames())

        for (contents in listOf("broken UUID", maidId(4).toString())) {
            Files.writeString(pointer, contents)
            assertThrows(IOException::class.java) { MaidDataFiles.selectedSnapshotId(dir, owner) }
            assertThrows(IOException::class.java) {
                MaidDataFiles.pruneSupersededSnapshots(dir, owner, selected, null, emptySet())
            }
            assertEquals(contents, Files.readString(pointer))
            assertEquals(before + pointer.fileName, fileNames())
        }

        MaidDataFiles.selectSnapshot(dir, owner, replacement)
        assertEquals(emptyList<Path>(), MaidDataFiles.pruneSupersededSnapshots(dir, owner, selected, null, emptySet()))
        assertEquals(replacement.toString(), Files.readString(pointer))
        assertEquals("older", Files.readString(snapshot(older)))
        assertEquals(before + pointer.fileName, fileNames())
    }

    @Test
    fun firstSummonWithoutSelectionPrefersWorldThenGlobalLegacy() {
        Files.createDirectory(legacyDir)
        val world = Files.writeString(dir.resolve("$owner.dat"), "world")
        val global = Files.writeString(legacyDir.resolve("$owner.dat"), "global")
        assertNull(MaidDataFiles.selectedSnapshotId(dir, owner))
        assertEquals("world", Files.readString(MaidDataFiles.prepareSummon(dir, legacyDir, owner, maidId(1))))
        Files.delete(world)
        assertNull(MaidDataFiles.selectedSnapshotId(dir, owner))
        assertEquals("global", Files.readString(MaidDataFiles.prepareSummon(dir, legacyDir, owner, maidId(2))))
        assertEquals("global", Files.readString(global))
        assertFalse(Files.exists(pointer))
    }

    private fun maidId(index: Int): UUID = UUID.fromString("c0000000-0000-0000-0000-${index.toString().padStart(12, '0')}")

    private fun snapshot(id: UUID): Path = dir.resolve("$owner-$id.dat")

    private fun putSnapshot(index: Int, contents: String): UUID = maidId(index).also { Files.writeString(snapshot(it), contents) }

    private fun fileNames(): Set<Path> = Files.list(dir).use { files -> files.map { it.fileName }.toList().toSet() }

    private fun snapshotNames(): Set<Path> = fileNames().filter { it.toString().startsWith("$owner-") }.toSet()
}
