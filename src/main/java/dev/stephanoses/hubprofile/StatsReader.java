package dev.stephanoses.hubprofile;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/** Reads a single UUID's saved vanilla statistics without ever writing to the world. */
public final class StatsReader {
    static final int MAX_BYTES = 8 * 1024 * 1024;
    public enum Status { AVAILABLE, NO_PLAYER_DATA, UNAVAILABLE }
    public record Snapshot(Status status, long playTicks, long deaths, long mobKills,
                           long minedBlocks, Instant savedAt, String detail) {
        static Snapshot failure(Status status, String detail) {
            return new Snapshot(status, 0, 0, 0, 0, Instant.EPOCH, detail);
        }
    }

    public Snapshot read(Path directory, UUID uuid) {
        if (!Files.isDirectory(directory)) {
            return Snapshot.failure(Status.UNAVAILABLE, "统计目录不存在或不可访问: " + directory);
        }
        Path file = directory.resolve(uuid.toString() + ".json");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("统计文件超过 8 MiB");
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("JSON 根节点不是对象");
            JsonObject root = parsed.getAsJsonObject();
            if (!root.has("stats") || !root.get("stats").isJsonObject()) {
                throw new IllegalArgumentException("缺少 stats 对象");
            }
            JsonObject stats = root.getAsJsonObject("stats");
            JsonObject custom = objectOrEmpty(stats, "minecraft:custom");
            long ticks = counter(custom, "minecraft:play_time");
            long deaths = counter(custom, "minecraft:deaths");
            long kills = counter(custom, "minecraft:mob_kills");
            long mined = 0;
            for (JsonElement value : objectOrEmpty(stats, "minecraft:mined").asMap().values()) {
                mined = Math.addExact(mined, nonNegativeLong(value));
            }
            return new Snapshot(Status.AVAILABLE, ticks, deaths, kills, mined,
                    Files.getLastModifiedTime(file).toInstant(), "OK");
        } catch (NoSuchFileException e) {
            return Snapshot.failure(Status.NO_PLAYER_DATA, "未找到此 UUID 的文件: " + file);
        } catch (Exception e) {
            return Snapshot.failure(Status.UNAVAILABLE,
                    e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
        }
    }

    private static JsonObject objectOrEmpty(JsonObject parent, String key) {
        if (!parent.has(key)) return new JsonObject();
        JsonElement value = parent.get(key);
        if (!value.isJsonObject()) throw new IllegalArgumentException("统计类别不是对象: " + key);
        return value.getAsJsonObject();
    }

    private static long counter(JsonObject parent, String key) {
        return parent.has(key) ? nonNegativeLong(parent.get(key)) : 0;
    }

    private static long nonNegativeLong(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("统计值不是数字");
        }
        long result = value.getAsBigDecimal().longValueExact();
        if (result < 0) throw new IllegalArgumentException("统计值为负数");
        return result;
    }

    public static String duration(long ticks) {
        long seconds = Math.max(0, ticks) / 20;
        long hours = seconds / 3600;
        long minutes = seconds / 60 % 60;
        return hours + " 小时 " + minutes + " 分钟 " + seconds % 60 + " 秒";
    }
}
