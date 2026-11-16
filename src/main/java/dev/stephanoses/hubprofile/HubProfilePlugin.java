package dev.stephanoses.hubprofile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

public class HubProfilePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final LegacyComponentSerializer COLORS = LegacyComponentSerializer.legacyAmpersand();
    private static final long COOLDOWN_NANOS = 750_000_000L;
    private final StatsReader reader = new StatsReader();
    private final Map<UUID, Long> lastOpen = new HashMap<>();
    private NamespacedKey marker;
    private Settings settings;
    private volatile boolean closing;

    private record Settings(Path directory, int slot, Set<String> worlds, boolean giveHead,
                            String headName, List<String> headLore, String menuTitle, ZoneId zone) {}

    @Override public void onEnable() {
        marker = new NamespacedKey(this, "profile_head");
        saveDefaultConfig();
        try { settings = readSettings(); }
        catch (RuntimeException e) {
            getLogger().severe("配置无效: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Objects.requireNonNull(getCommand("hubprofile")).setExecutor(this);
        Objects.requireNonNull(getCommand("hubprofile")).setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getOnlinePlayers().forEach(this::updateHead);
        getLogger().info("Fabric 统计目录（只读）: " + settings.directory);
        if (!Files.isDirectory(settings.directory)) {
            getLogger().warning("统计目录不存在或不可读，请检查 config.yml 中的 fabric-stats-directory。");
        }
    }

    @Override public void onDisable() {
        closing = true;
        if (marker != null) {
            for (Player player : getServer().getOnlinePlayers()) {
                if (currentMenu(player) != null) {
                    player.closeInventory();
                }
                removeHeads(player);
            }
        }
        lastOpen.clear();
    }

    private Settings readSettings() {
        String directory = getConfig().getString("fabric-stats-directory", "");
        if (directory.isBlank()) throw new IllegalArgumentException("fabric-stats-directory 不能为空");
        int slot = getConfig().getInt("hotbar-slot", 0);
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("hotbar-slot 必须为 0–8");
        return new Settings(Path.of(directory).toAbsolutePath().normalize(), slot,
                Set.copyOf(getConfig().getStringList("allowed-worlds")),
                getConfig().getBoolean("give-head-on-join", true),
                getConfig().getString("head-name", "&b个人档案"),
                List.copyOf(getConfig().getStringList("head-lore")),
                getConfig().getString("menu-title", "我的生存档案"),
                ZoneId.of(getConfig().getString("display-timezone", "Asia/Shanghai")));
    }

    private boolean active(Player player) {
        return settings.worlds.isEmpty() || settings.worlds.contains(player.getWorld().getName());
    }

    private boolean isHead(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return Byte.valueOf((byte) 1).equals(item.getItemMeta().getPersistentDataContainer()
                .get(marker, PersistentDataType.BYTE));
    }

    private void removeHeads(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getSize(); i++) {
            if (isHead(inventory.getItem(i))) inventory.setItem(i, null);
        }
        if (isHead(player.getItemOnCursor())) player.setItemOnCursor(null);
    }

    private void updateHead(Player player) {
        if (!player.isOnline()) return;
        if (!active(player) || !settings.giveHead || !player.hasPermission("hubprofile.use")) {
            removeHeads(player);
            return;
        }
        ItemStack[] contents = player.getInventory().getStorageContents();
        boolean[] empty = new boolean[contents.length];
        boolean[] owned = new boolean[contents.length];
        for (int i = 0; i < contents.length; i++) {
            empty[i] = contents[i] == null || contents[i].getType().isAir();
            owned[i] = isHead(contents[i]);
        }
        int target = HeadSlot.choose(empty, owned, settings.slot);
        if (target < 0) {
            player.sendMessage(text("&e背包已满，腾出一格后重新进入大厅即可获得档案头像。也可以输入 /hubprofile。"));
            return;
        }
        // Build first: if a head profile cannot be constructed, leave inventory untouched.
        ItemStack head = head(player, settings.headName, settings.headLore);
        ItemMeta meta = head.getItemMeta();
        meta.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
        head.setItemMeta(meta);
        removeHeads(player);
        player.getInventory().setItem(target, head);
    }

    private static Component text(String input) {
        return COLORS.deserialize(input).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack item(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text(name));
        meta.lore(Arrays.stream(lore).map(HubProfilePlugin::text).toList());
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack head(Player player, String name, List<String> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setPlayerProfile(player.getPlayerProfile());
        meta.displayName(text(name));
        meta.lore(lore.stream().map(HubProfilePlugin::text).toList());
        item.setItemMeta(meta);
        return item;
    }

    private static final class ProfileMenu implements InventoryHolder {
        private final UUID owner;
        private final Inventory inventory;
        private boolean loading;
        private long lastRefresh;
        ProfileMenu(UUID owner, String title) {
            this.owner = owner;
            this.inventory = Bukkit.createInventory(this, 27, text(title));
        }
        @Override public Inventory getInventory() { return inventory; }
    }

    private static ProfileMenu currentMenu(Player player) {
        Inventory top = player.getOpenInventory().getTopInventory();
        return top != null && top.getHolder() instanceof ProfileMenu menu ? menu : null;
    }

    private void openProfile(Player player) {
        if (!player.isOnline() || !active(player) || !player.hasPermission("hubprofile.use")) return;
        long now = System.nanoTime();
        if (now - lastOpen.getOrDefault(player.getUniqueId(), now - COOLDOWN_NANOS) < COOLDOWN_NANOS) return;
        lastOpen.put(player.getUniqueId(), now);
        ProfileMenu menu = new ProfileMenu(player.getUniqueId(), settings.menuTitle);
        for (int i = 0; i < 27; i++) {
            menu.inventory.setItem(i, item(Material.GRAY_STAINED_GLASS_PANE, " "));
        }
        menu.inventory.setItem(4, head(player, "&b" + player.getName(), List.of("&7你的 Fabric 生存档案")));
        menu.inventory.setItem(19, item(Material.LIME_DYE, "&a刷新档案", "&7点击查看最新记录"));
        menu.inventory.setItem(22, item(Material.BARRIER, "&c关闭"));
        menu.inventory.setItem(25, item(Material.PAPER, "&e关于这些记录",
                "&7仅统计 Fabric 生存服", "&7游玩时长包含挂机时间", "&7返回大厅后，数据可能稍有延迟"));
        for (int slot : new int[]{10, 12, 14, 16}) {
            menu.inventory.setItem(slot, item(Material.CLOCK, "&7正在读取…"));
        }
        player.openInventory(menu.inventory);
        refresh(player, menu);
    }

    private void refresh(Player player, ProfileMenu menu) {
        if (menu.loading) return;
        long now = System.nanoTime();
        if (menu.lastRefresh != 0 && now - menu.lastRefresh < COOLDOWN_NANOS) return;
        menu.lastRefresh = now;
        menu.loading = true;
        Settings captured = settings;
        // Only plain file IO is off-thread. All Bukkit inventory operations remain on the main thread.
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            StatsReader.Snapshot snapshot = reader.read(captured.directory, menu.owner);
            if (closing) return;
            try {
                getServer().getScheduler().runTask(this, () -> {
                    menu.loading = false;
                    if (player.isOnline() && player.getOpenInventory().getTopInventory() == menu.inventory) {
                        render(menu, snapshot, captured.zone);
                    }
                });
            } catch (IllegalPluginAccessException ignored) {
                // The server disabled the plugin between reading and scheduling the UI update.
            }
        });
    }

    private void render(ProfileMenu menu, StatsReader.Snapshot snapshot, ZoneId zone) {
        if (snapshot.status() != StatsReader.Status.AVAILABLE) {
            String label = snapshot.status() == StatsReader.Status.NO_PLAYER_DATA
                    ? "&e暂无生存记录" : "&c暂时无法读取档案";
            String hint = snapshot.status() == StatsReader.Status.NO_PLAYER_DATA
                    ? "&7先到 Fabric 游玩，再回来查看" : "&7稍后刷新；若持续出现，请联系服主";
            for (int slot : new int[]{10, 12, 14, 16}) {
                menu.inventory.setItem(slot, item(Material.PAPER, label, hint));
            }
            return;
        }
        String updated = "&8记录更新于 " + DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(zone).format(snapshot.savedAt());
        menu.inventory.setItem(10, item(Material.CLOCK, "&b游玩时长",
                "&f" + StatsReader.duration(snapshot.playTicks()), "&7包含挂机时间", updated));
        menu.inventory.setItem(12, item(Material.BONE, "&b死亡次数", "&f" + snapshot.deaths() + " 次", updated));
        menu.inventory.setItem(14, item(Material.IRON_SWORD, "&b击杀生物", "&f" + snapshot.mobKills() + " 次", updated));
        menu.inventory.setItem(16, item(Material.DIAMOND_PICKAXE, "&b挖掘方块", "&f" + snapshot.minedBlocks() + " 个", updated));
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        getServer().getScheduler().runTaskLater(this, () -> updateHead(event.getPlayer()), 10L);
    }
    @EventHandler public void onRespawn(PlayerRespawnEvent event) {
        getServer().getScheduler().runTaskLater(this, () -> updateHead(event.getPlayer()), 1L);
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { lastOpen.remove(event.getPlayer().getUniqueId()); }
    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (!active(player) && currentMenu(player) != null) {
            player.closeInventory();
        }
        updateHead(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent event) {
        if (!isHead(event.getItem())) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        if (event.getHand() == EquipmentSlot.HAND) {
            getServer().getScheduler().runTask(this, () -> openProfile(event.getPlayer()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUseEntity(PlayerInteractEntityEvent event) {
        ItemStack held = event.getHand() == EquipmentSlot.HAND
                ? event.getPlayer().getInventory().getItemInMainHand()
                : event.getPlayer().getInventory().getItemInOffHand();
        if (!isHead(held)) return;
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND) {
            getServer().getScheduler().runTask(this, () -> openProfile(event.getPlayer()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUseAtEntity(PlayerInteractAtEntityEvent event) {
        onUseEntity(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getView().getTopInventory().getHolder() instanceof ProfileMenu menu) {
            event.setCancelled(true);
            if (!menu.owner.equals(player.getUniqueId())) return;
            int slot = event.getRawSlot();
            if (slot == 22) getServer().getScheduler().runTask(this, () -> player.closeInventory());
            if (slot == 19) getServer().getScheduler().runTask(this, () -> {
                if (player.getOpenInventory().getTopInventory() == menu.inventory) refresh(player, menu);
            });
            return;
        }
        boolean hotbarSwap = event.getHotbarButton() >= 0
                && isHead(player.getInventory().getItem(event.getHotbarButton()));
        boolean offhandSwap = event.getClick() == ClickType.SWAP_OFFHAND
                && isHead(player.getInventory().getItemInOffHand());
        if (isHead(event.getCurrentItem()) || isHead(event.getCursor()) || hotbarSwap || offhandSwap) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ProfileMenu || isHead(event.getOldCursor())) {
            event.setCancelled(true);
            return;
        }
        for (int slot : event.getRawSlots()) {
            if (isHead(event.getView().getItem(slot))) { event.setCancelled(true); return; }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (isHead(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (isHead(event.getMainHandItem()) || isHead(event.getOffHandItem())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        if (isHead(event.getItemInHand())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMoveItem(InventoryMoveItemEvent event) {
        if (isHead(event.getItem())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) { event.getDrops().removeIf(this::isHead); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) { sender.sendMessage("此命令需要在大厅游戏内执行。"); return true; }
            if (!active(player)) { player.sendMessage(text("&e请在大厅打开档案。")); return true; }
            openProfile(player);
            return true;
        }
        if (!sender.hasPermission("hubprofile.admin")) { sender.sendMessage("只有管理员可以使用此子命令。"); return true; }
        if (args[0].equalsIgnoreCase("reload")) {
            try {
                reloadConfig();
                Settings replacement = readSettings();
                settings = replacement;
                for (Player player : getServer().getOnlinePlayers()) {
                    if (currentMenu(player) != null) player.closeInventory();
                    updateHead(player);
                }
                sender.sendMessage("HubProfile 配置已重载。");
            } catch (RuntimeException e) { sender.sendMessage("配置无效，保留此前设置: " + e.getMessage()); }
            return true;
        }
        if (args[0].equalsIgnoreCase("check")) {
            Settings captured = settings;
            UUID uuid = sender instanceof Player p ? p.getUniqueId() : null;
            sender.sendMessage("目录: " + captured.directory);
            if (uuid != null) sender.sendMessage("当前大厅玩家 UUID: " + uuid);
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                boolean directoryExists = Files.isDirectory(captured.directory);
                StatsReader.Snapshot result = uuid == null ? null : reader.read(captured.directory, uuid);
                if (closing) return;
                try {
                    getServer().getScheduler().runTask(this, () -> {
                        sender.sendMessage("目录可用: " + directoryExists);
                        if (result != null) sender.sendMessage("读取结果: " + result.status() + " | " + result.detail());
                        else sender.sendMessage("在游戏内执行 /hubprofile check 可检查自己的 UUID 数据文件。");
                    });
                } catch (IllegalPluginAccessException ignored) { }
            });
            return true;
        }
        sender.sendMessage("用法: /hubprofile [reload|check]");
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("hubprofile.admin") || args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("reload", "check").stream().filter(s -> s.startsWith(prefix)).toList();
    }
}
