package com.oyxdsg.smartmaid.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MaidDataFilesTest {
    @TempDir Path temp;

    @Test
    void distinctMaidsHaveDistinctFiles() {
        UUID owner = UUID.randomUUID(), maid = UUID.randomUUID();
        assertEquals(temp.resolve(owner + "-" + maid + ".dat"), MaidDataFiles.dataFile(temp, owner, maid));
        assertNotEquals(MaidDataFiles.dataFile(temp, owner, maid), MaidDataFiles.dataFile(temp, owner, UUID.randomUUID()));
    }

    @Test
    void prefersWorldLegacySnapshotAndDoesNotOverwriteAValidTarget() throws IOException {
        UUID owner = UUID.randomUUID(), maid = UUID.randomUUID();
        Path legacy = Files.createDirectory(temp.resolve("legacy"));
        Path worldSource = Files.writeString(temp.resolve(owner + ".dat"), "world");
        Path globalSource = Files.writeString(legacy.resolve(owner + ".dat"), "global");
        Path target = MaidDataFiles.migrateLegacy(temp, legacy, owner, maid);
        assertEquals("world", Files.readString(target));
        Files.writeString(target, "current");
        assertEquals(target, MaidDataFiles.migrateLegacy(temp, legacy, owner, maid));
        assertEquals("current", Files.readString(target));
        assertEquals("world", Files.readString(worldSource));
        assertEquals("global", Files.readString(globalSource));
    }

    @Test
    void copiesGlobalSnapshotWhenWorldBackupIsMissingAndReplacesEmptyTarget() throws IOException {
        UUID owner = UUID.randomUUID(), maid = UUID.randomUUID();
        Path legacy = Files.createDirectory(temp.resolve("legacy"));
        Files.writeString(legacy.resolve(owner + ".dat"), "global");
        Path target = Files.createFile(MaidDataFiles.dataFile(temp, owner, maid));
        assertEquals(target, MaidDataFiles.migrateLegacy(temp, legacy, owner, maid));
        assertEquals("global", Files.readString(target));
    }

    @Test
    void resummonUsesOnlyTheSelectedSnapshotAndKeepsOldFiles() throws IOException {
        UUID owner = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        Path old = Files.writeString(MaidDataFiles.dataFile(temp, owner, first), "latest");
        Files.writeString(temp.resolve(owner + ".dat"), "legacy");
        Files.writeString(MaidDataFiles.dataFile(temp, owner, UUID.randomUUID()), "other");
        MaidDataFiles.selectSnapshot(temp, owner, first);
        Path restored = MaidDataFiles.prepareSummon(temp, temp.resolve("legacy"), owner, second);
        assertEquals(MaidDataFiles.dataFile(temp, owner, second), restored);
        assertEquals("latest", Files.readString(restored));
        assertEquals("latest", Files.readString(old));
        assertEquals("legacy", Files.readString(temp.resolve(owner + ".dat")));
    }

    @Test
    void missingSelectedSnapshotDoesNotSilentlyRestoreStaleLegacy() throws IOException {
        UUID owner = UUID.randomUUID(), next = UUID.randomUUID();
        Files.writeString(temp.resolve(owner + ".dat"), "stale");
        UUID selected = UUID.randomUUID();
        Path previous = Files.writeString(MaidDataFiles.dataFile(temp, owner, selected), "latest");
        MaidDataFiles.selectSnapshot(temp, owner, selected);
        Files.delete(previous);
        assertThrows(IOException.class, () -> MaidDataFiles.prepareSummon(temp, temp, owner, next));
        assertFalse(Files.exists(MaidDataFiles.dataFile(temp, owner, next)));
    }
}
