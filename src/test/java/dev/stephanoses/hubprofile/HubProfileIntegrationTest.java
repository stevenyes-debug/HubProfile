package dev.stephanoses.hubprofile;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class HubProfileIntegrationTest {
    @TempDir Path stats;
    ServerMock server;
    HubProfilePlugin plugin;
    PlayerMock player;

    @BeforeEach void start() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(HubProfilePlugin.class);
        plugin.getConfig().set("fabric-stats-directory", stats.toString());
        plugin.saveConfig();
        server.dispatchCommand(server.getConsoleSender(), "hubprofile reload");
        player = server.addPlayer("Stephanoses");
        player.setOp(false);
        server.getScheduler().performTicks(12);
    }

    @AfterEach void stop() { MockBukkit.unmock(); }

    private long heads(PlayerMock p) {
        return Arrays.stream(p.getInventory().getStorageContents())
                .filter(i -> i != null && i.getType() == Material.PLAYER_HEAD).count();
    }

    private void finishRead() {
        server.getScheduler().performTicks(2);
        server.getScheduler().waitAsyncTasksFinished();
        server.getScheduler().performTicks(2);
    }

    private String lore(ItemStack item) {
        return String.join("\n", item.getItemMeta().lore().stream()
                .map(PlainTextComponentSerializer.plainText()::serialize).toList());
    }

    @Test void nonOpReceivesTheirOwnHead() {
        ItemStack head = player.getInventory().getItem(0);
        assertNotNull(head);
        assertEquals(Material.PLAYER_HEAD, head.getType());
        assertEquals(player.getUniqueId(), ((SkullMeta) head.getItemMeta()).getPlayerProfile().getId());
        assertFalse(player.isOp());
    }

    @Test void repeatedJoinAndReloadNeverDuplicateEntryHead() {
        plugin.onJoin(new PlayerJoinEvent(player, Component.empty()));
        server.getScheduler().performTicks(12);
        server.dispatchCommand(server.getConsoleSender(), "hubprofile reload");
        assertEquals(1, heads(player));
    }

    @Test void occupiedSlotPreservesExistingItem() {
        player.getInventory().clear();
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 64));
        server.dispatchCommand(server.getConsoleSender(), "hubprofile reload");
        assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType());
        assertEquals(64, player.getInventory().getItem(0).getAmount());
        assertEquals(1, heads(player));
    }

    @Test void completelyFullInventoryIsPreserved() {
        player.getInventory().clear();
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Material.DIAMOND, 64));
        server.dispatchCommand(server.getConsoleSender(), "hubprofile reload");
        for (ItemStack i : player.getInventory().getStorageContents()) {
            assertEquals(Material.DIAMOND, i.getType());
            assertEquals(64, i.getAmount());
        }
        assertEquals(0, heads(player));
    }

    @Test void cancelledVanillaAirInteractionStillOpensProfile() {
        ItemStack head = player.getInventory().getItem(0);
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR,
                head, null, BlockFace.SELF, EquipmentSlot.HAND);
        event.setCancelled(true);
        server.getPluginManager().callEvent(event);
        finishRead();
        assertEquals(27, player.getOpenInventory().getTopInventory().getSize());
        assertEquals(Material.BARRIER, player.getOpenInventory().getTopInventory().getItem(22).getType());
    }

    @Test void menuReadsFabricFixtureNotHubStatistics() throws Exception {
        Files.writeString(stats.resolve(player.getUniqueId() + ".json"), """
            {"stats":{"minecraft:custom":{"minecraft:play_time":144000,"minecraft:deaths":7,"minecraft:mob_kills":11},
                      "minecraft:mined":{"minecraft:stone":800}}}
            """);
        server.dispatchCommand(player, "hubprofile");
        finishRead();
        Inventory menu = player.getOpenInventory().getTopInventory();
        assertTrue(lore(menu.getItem(10)).contains("2 小时 0 分钟 0 秒"));
        assertTrue(lore(menu.getItem(12)).contains("7 次"));
        assertTrue(lore(menu.getItem(14)).contains("11 次"));
        assertTrue(lore(menu.getItem(16)).contains("800 个"));
    }

    @Test void secondPlayerCannotReadFirstPlayersRecord() throws Exception {
        Files.writeString(stats.resolve(player.getUniqueId() + ".json"), "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":72000}}}");
        PlayerMock other = server.addPlayer("AnotherPlayer");
        other.setOp(false);
        server.getScheduler().performTicks(12);
        server.dispatchCommand(other, "hubprofile");
        finishRead();
        ItemStack clock = other.getOpenInventory().getTopInventory().getItem(10);
        assertEquals("暂无生存记录", PlainTextComponentSerializer.plainText().serialize(clock.getItemMeta().displayName()));
    }

    @Test void shiftClickAndNumberKeyCannotExtractMenuItems() {
        server.dispatchCommand(player, "hubprofile");
        finishRead();
        InventoryClickEvent shift = new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                10, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        server.getPluginManager().callEvent(shift);
        assertTrue(shift.isCancelled());
        InventoryClickEvent key = new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                10, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, 0);
        server.getPluginManager().callEvent(key);
        assertTrue(key.isCancelled());
    }

    @Test void entryHeadCannotBeShiftClickedIntoAChest() {
        player.openInventory(server.createInventory(null, 27));
        int rawHeadSlot = -1;
        for (int raw = 0; raw < player.getOpenInventory().countSlots(); raw++) {
            ItemStack candidate = player.getOpenInventory().getItem(raw);
            if (candidate != null && candidate.getType() == Material.PLAYER_HEAD) {
                rawHeadSlot = raw;
                break;
            }
        }
        assertTrue(rawHeadSlot >= 0, "Entry head must be visible in the player inventory");
        InventoryClickEvent event = new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.QUICKBAR,
                rawHeadSlot, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        assertEquals(Material.PLAYER_HEAD, event.getCurrentItem().getType());
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled());
    }

    @Test void ordinaryNamedHeadIsNotMistakenForEntryItem() {
        Inventory chest = server.createInventory(null, 27);
        ItemStack ordinary = new ItemStack(Material.PLAYER_HEAD);
        var meta = ordinary.getItemMeta();
        meta.displayName(player.getInventory().getItem(0).getItemMeta().displayName());
        ordinary.setItemMeta(meta);
        chest.setItem(0, ordinary);
        player.openInventory(chest);
        InventoryClickEvent event = new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(event);
        assertFalse(event.isCancelled());
    }

    @Test void nonOpCannotReloadConfiguration() {
        assertFalse(player.hasPermission("hubprofile.admin"));
        assertTrue(server.dispatchCommand(player, "hubprofile reload"));
        assertEquals("只有管理员可以使用此子命令。", player.nextMessage());
    }

    @Test void disablingRemovesOnlinePlayersEntryHead() {
        server.getPluginManager().disablePlugin(plugin);
        assertEquals(0, heads(player));
    }
}
