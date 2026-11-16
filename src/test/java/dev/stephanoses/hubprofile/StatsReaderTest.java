package dev.stephanoses.hubprofile;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsReaderTest {
    @TempDir Path directory;
    private final StatsReader reader = new StatsReader();
    private final UUID owner = UUID.fromString("11111111-2222-4333-8444-555555555555");

    private void write(String json) throws Exception {
        Files.writeString(directory.resolve(owner + ".json"), json);
    }

    @Test void readsRealCounterShapeAndSumsModdedBlocks() throws Exception {
        write("""
            {"DataVersion":4671,"stats":{
              "minecraft:custom":{"minecraft:play_time":74460,"minecraft:deaths":3,"minecraft:mob_kills":24},
              "minecraft:mined":{"minecraft:stone":200,"some_mod:custom_block":5}
            }}
            """);
        var result = reader.read(directory, owner);
        assertEquals(StatsReader.Status.AVAILABLE, result.status());
        assertEquals("1 小时 2 分钟 3 秒", StatsReader.duration(result.playTicks()));
        assertEquals(3, result.deaths());
        assertEquals(24, result.mobKills());
        assertEquals(205, result.minedBlocks());
        assertTrue(result.savedAt().toEpochMilli() > 0);
    }

    @Test void neverUsesAnotherPlayersData() throws Exception {
        Files.writeString(directory.resolve(UUID.randomUUID() + ".json"), "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":72000}}}");
        assertEquals(StatsReader.Status.NO_PLAYER_DATA, reader.read(directory, owner).status());
    }

    @Test void aMissingDirectoryIsAnErrorNotANewPlayer() {
        assertEquals(StatsReader.Status.UNAVAILABLE, reader.read(directory.resolve("wrong-world"), owner).status());
    }

    @Test void firstTimePlayersCanHaveMissingIndividualCounters() throws Exception {
        write("{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":20}}}");
        var result = reader.read(directory, owner);
        assertEquals(StatsReader.Status.AVAILABLE, result.status());
        assertEquals(0, result.deaths());
        assertEquals(0, result.minedBlocks());
    }

    @Test void truncatedSaveDoesNotMasqueradeAsZeroPlaytime() throws Exception {
        write("{\"stats\":{\"minecraft:custom\":{");
        assertEquals(StatsReader.Status.UNAVAILABLE, reader.read(directory, owner).status());
    }

    @Test void wrongJsonSchemaIsNotANewPlayer() throws Exception {
        write("{\"unrelated\":1}");
        assertEquals(StatsReader.Status.UNAVAILABLE, reader.read(directory, owner).status());
    }

    @Test void rejectsNegativeFractionalAndStringCounters() throws Exception {
        for (String bad : new String[]{"-1", "1.5", "\"50\"", "null", "true", "[]", "9223372036854775808"}) {
            write("{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":" + bad + "}}}");
            assertEquals(StatsReader.Status.UNAVAILABLE, reader.read(directory, owner).status(), bad);
        }
    }

    @Test void fileRemainsByteForByteUnmodified() throws Exception {
        String original = "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":100}}}\n";
        write(original);
        reader.read(directory, owner);
        assertEquals(original, Files.readString(directory.resolve(owner + ".json")));
    }

    @Test void oversizedFileIsRejected() throws Exception {
        Files.write(directory.resolve(owner + ".json"), new byte[StatsReader.MAX_BYTES + 1]);
        assertEquals(StatsReader.Status.UNAVAILABLE, reader.read(directory, owner).status());
    }

    @Test void hoursDoNotWrapAtMidnight() {
        assertEquals("49 小时 0 分钟 0 秒", StatsReader.duration(49L * 3600 * 20));
        assertEquals("0 小时 0 分钟 0 秒", StatsReader.duration(19));
    }
}
