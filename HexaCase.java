package ru.hexatime.hexacase;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.type.Chest;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * HexaCase — кейсы «выбери один из сундуков».
 * Блок кейса стоит на спавне (привязывается командой /case setblock),
 * правый клик открывает меню с кейсами игрока, клик по кейсу запускает открытие:
 * вокруг игрока появляются сундуки, он выбирает один, остальные показывают награды.
 */
public final class HexaCase extends JavaPlugin implements Listener, TabExecutor {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final Map<String, Crate> crates = new LinkedHashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<String, Session> activeBlocks = new HashMap<>();
    private final Set<String> menuBlocks = new HashSet<>();
    private final Random random = new Random();

    private File dataFile;
    private YamlConfiguration data;

    // ---------- модели ----------

    static final class Reward {
        String name;
        double weight;
        Material material;
        int amount;
        List<String> commands = new ArrayList<>();
        String skipPerm = "";
        List<String> fallback = new ArrayList<>();
    }

    static final class Crate {
        String id;
        String name;
        Material icon;
        int chests;
        List<Reward> rewards = new ArrayList<>();
    }

    static final class Session {
        Player player;
        Crate crate;
        boolean chosen;
        boolean done;
        int left;
        BossBar bar;
        BukkitTask timer;
        List<Block> blocks = new ArrayList<>();
        List<BlockState> originals = new ArrayList<>();
        List<Reward> rolls = new ArrayList<>();
        List<ArmorStand> stands = new ArrayList<>();
    }

    static final class MenuHolder implements InventoryHolder {
        Inventory inv;
        final Map<Integer, String> slots = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    // ---------- жизненный цикл ----------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        menuBlocks.addAll(data.getStringList("blocks"));
        loadCrates();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("case");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        getLogger().info("HexaCase загружен, кейсов: " + crates.size());
    }

    @Override
    public void onDisable() {
        for (Session s : new ArrayList<>(sessions.values())) {
            finish(s, random.nextInt(s.blocks.size()), false);
        }
    }

    private void loadCrates() {
        crates.clear();
        ConfigurationSection cs = getConfig().getConfigurationSection("crates");
        if (cs == null) {
            return;
        }
        for (String id : cs.getKeys(false)) {
            ConfigurationSection s = cs.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            Crate c = new Crate();
            c.id = id.toLowerCase();
            c.name = s.getString("name", id);
            Material icon = Material.matchMaterial(s.getString("icon", "CHEST"));
            c.icon = icon == null ? Material.CHEST : icon;
            c.chests = Math.max(3, Math.min(10, s.getInt("chests", 6)));
            ConfigurationSection rs = s.getConfigurationSection("rewards");
            if (rs != null) {
                for (String rid : rs.getKeys(false)) {
                    ConfigurationSection r = rs.getConfigurationSection(rid);
                    if (r == null) {
                        continue;
                    }
                    Reward rw = new Reward();
                    rw.name = r.getString("name", rid);
                    rw.weight = r.getDouble("weight", 1);
                    String m = r.getString("material");
                    if (m != null) {
                        rw.material = Material.matchMaterial(m);
                        if (rw.material == null) {
                            getLogger().warning("Неизвестный материал '" + m + "' в награде " + id + "/" + rid);
                        }
                    }
                    rw.amount = r.getInt("amount", 1);
                    rw.commands = r.getStringList("commands");
                    rw.skipPerm = r.getString("skip-if-permission", "");
                    rw.fallback = r.getStringList("fallback-commands");
                    c.rewards.add(rw);
                }
            }
            crates.put(c.id, c);
        }
    }

    // ---------- данные ----------

    private int getCount(UUID id, String crate) {
        return data.getInt("players." + id + "." + crate, 0);
    }

    private void addCount(UUID id, String crate, int delta) {
        int n = Math.max(0, getCount(id, crate) + delta);
        data.set("players." + id + "." + crate, n == 0 ? null : n);
        saveData();
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Не удалось сохранить data.yml: " + e.getMessage());
        }
    }

    private void saveBlocks() {
        data.set("blocks", new ArrayList<>(menuBlocks));
        saveData();
    }

    // ---------- утилиты ----------

    private static Component txt(String s) {
        return LEGACY.deserialize(s);
    }

    private static Component item(String s) {
        return LEGACY.deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    private static String key(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    private boolean isProtected(Block b) {
        String k = key(b);
        return activeBlocks.containsKey(k) || menuBlocks.contains(k);
    }

    private int indexOfBlock(Session s, Block b) {
        String k = key(b);
        for (int i = 0; i < s.blocks.size(); i++) {
            if (key(s.blocks.get(i)).equals(k)) {
                return i;
            }
        }
        return -1;
    }

    // ---------- меню ----------

    private void openMenu(Player p) {
        MenuHolder h = new MenuHolder();
        Inventory inv = Bukkit.createInventory(h, 27, txt("&8Ваши кейсы"));
        h.inv = inv;
        int slot = 10;
        boolean any = false;
        for (Crate cr : crates.values()) {
            int n = getCount(p.getUniqueId(), cr.id);
            if (n <= 0) {
                continue;
            }
            if (slot > 16) {
                break;
            }
            any = true;
            ItemStack it = new ItemStack(cr.icon);
            ItemMeta m = it.getItemMeta();
            m.displayName(item(cr.name));
            m.lore(List.of(item("&7У вас: &f" + n), item("&eНажмите, чтобы открыть")));
            it.setItemMeta(m);
            inv.setItem(slot, it);
            h.slots.put(slot, cr.id);
            slot++;
        }
        if (!any) {
            ItemStack b = new ItemStack(Material.BARRIER);
            ItemMeta m = b.getItemMeta();
            m.displayName(item("&cУ вас нет кейсов"));
            m.lore(List.of(item("&7Кейсы можно получить в донате")));
            b.setItemMeta(m);
            inv.setItem(13, b);
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder h)) {
            return;
        }
        e.setCancelled(true);
        if (e.getClickedInventory() != top) {
            return;
        }
        String id = h.slots.get(e.getSlot());
        if (id == null || !(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        Crate cr = crates.get(id);
        if (cr == null) {
            return;
        }
        p.closeInventory();
        startOpening(p, cr);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof MenuHolder) {
            e.setCancelled(true);
        }
    }

    // ---------- открытие кейса ----------

    private boolean isFree(Block b) {
        return b.getType().isAir()
                && b.getRelative(BlockFace.UP).getType().isAir()
                && b.getRelative(BlockFace.DOWN).getType().isSolid();
    }

    private List<Block> findSpots(Player p, int n, int radius) {
        Location base = p.getLocation();
        World w = base.getWorld();
        int cx = base.getBlockX();
        int cy = base.getBlockY();
        int cz = base.getBlockZ();
        List<Block> res = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double ang = 2 * Math.PI * i / n;
            int x = cx + (int) Math.round(Math.cos(ang) * radius);
            int z = cz + (int) Math.round(Math.sin(ang) * radius);
            for (int dy : new int[]{0, 1, -1, 2, -2}) {
                Block b = w.getBlockAt(x, cy + dy, z);
                if (isFree(b) && !res.contains(b) && !isProtected(b)) {
                    res.add(b);
                    break;
                }
            }
        }
        return res;
    }

    private Reward roll(Crate cr) {
        double total = 0;
        for (Reward r : cr.rewards) {
            total += Math.max(0, r.weight);
        }
        if (total <= 0) {
            return cr.rewards.get(random.nextInt(cr.rewards.size()));
        }
        double x = random.nextDouble() * total;
        for (Reward r : cr.rewards) {
            x -= Math.max(0, r.weight);
            if (x < 0) {
                return r;
            }
        }
        return cr.rewards.get(cr.rewards.size() - 1);
    }

    private void startOpening(Player p, Crate cr) {
        if (sessions.containsKey(p.getUniqueId())) {
            return;
        }
        if (cr.rewards.isEmpty()) {
            p.sendMessage(txt("&cВ этом кейсе нет наград."));
            return;
        }
        if (getCount(p.getUniqueId(), cr.id) <= 0) {
            p.sendMessage(txt("&cУ вас нет такого кейса."));
            return;
        }
        int radius = getConfig().getInt("ring-radius", 3);
        List<Block> spots = findSpots(p, cr.chests, radius);
        if (spots.size() < 3) {
            p.sendMessage(txt("&cРядом мало места. Отойдите на открытую площадку."));
            return;
        }
        addCount(p.getUniqueId(), cr.id, -1);

        final Session s = new Session();
        s.player = p;
        s.crate = cr;
        Location center = p.getLocation();
        for (Block b : spots) {
            s.originals.add(b.getState());
            Chest d = (Chest) Material.CHEST.createBlockData();
            double dx = center.getX() - (b.getX() + 0.5);
            double dz = center.getZ() - (b.getZ() + 0.5);
            BlockFace f;
            if (Math.abs(dx) > Math.abs(dz)) {
                f = dx > 0 ? BlockFace.EAST : BlockFace.WEST;
            } else {
                f = dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
            }
            d.setFacing(f);
            d.setType(Chest.Type.SINGLE);
            b.setBlockData(d, false);
            s.blocks.add(b);
            s.rolls.add(roll(cr));
            activeBlocks.put(key(b), s);
        }

        final int total = getConfig().getInt("timeout-seconds", 30);
        s.left = total;
        s.bar = Bukkit.createBossBar("Выберите сундук в течение " + total + " сек.", BarColor.GREEN, BarStyle.SOLID);
        s.bar.addPlayer(p);
        sessions.put(p.getUniqueId(), s);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 1f, 1f);
        p.sendMessage(txt("&aВыберите один из сундуков — нажмите на него."));

        s.timer = new BukkitRunnable() {
            @Override
            public void run() {
                if (s.done || s.chosen) {
                    cancel();
                    return;
                }
                s.left--;
                if (s.left <= 0) {
                    pick(s, random.nextInt(s.blocks.size()));
                    cancel();
                    return;
                }
                s.bar.setTitle("Выберите сундук в течение " + s.left + " сек.");
                s.bar.setProgress(Math.max(0.0, Math.min(1.0, (double) s.left / total)));
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    private void pick(Session s, int idx) {
        if (s.chosen || s.done || idx < 0 || idx >= s.blocks.size()) {
            return;
        }
        s.chosen = true;
        Player p = s.player;
        Reward r = s.rolls.get(idx);
        give(p, r);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        p.sendMessage(txt("&aВам выпало: &f" + r.name));
        if (s.bar != null) {
            s.bar.removeAll();
        }
        for (int i = 0; i < s.blocks.size(); i++) {
            String label = (i == idx ? "&a&l✔ &r" : "&7") + s.rolls.get(i).name;
            s.stands.add(spawnLabel(s.blocks.get(i), label));
        }
        long ticks = getConfig().getInt("reveal-seconds", 6) * 20L;
        Bukkit.getScheduler().runTaskLater(this, () -> cleanup(s), ticks);
    }

    private ArmorStand spawnLabel(Block b, String text) {
        Location l = b.getLocation().add(0.5, 0.9, 0.5);
        return b.getWorld().spawn(l, ArmorStand.class, a -> {
            a.setVisible(false);
            a.setGravity(false);
            a.setMarker(true);
            a.setSmall(true);
            a.setInvulnerable(true);
            a.setPersistent(false);
            a.customName(txt(text));
            a.setCustomNameVisible(true);
        });
    }

    /** Завершение без показа остальных сундуков (выход игрока, остановка сервера). */
    private void finish(Session s, int idx, boolean reveal) {
        if (reveal) {
            pick(s, idx);
            return;
        }
        if (!s.chosen && !s.done) {
            s.chosen = true;
            give(s.player, s.rolls.get(idx));
        }
        cleanup(s);
    }

    private void cleanup(Session s) {
        if (s.done) {
            return;
        }
        s.done = true;
        if (s.timer != null) {
            s.timer.cancel();
        }
        if (s.bar != null) {
            s.bar.removeAll();
        }
        for (ArmorStand a : s.stands) {
            a.remove();
        }
        for (Block b : s.blocks) {
            activeBlocks.remove(key(b));
        }
        for (BlockState st : s.originals) {
            st.update(true, false);
        }
        sessions.remove(s.player.getUniqueId());
    }

    // ---------- выдача наград ----------

    private void give(Player p, Reward r) {
        if (!r.skipPerm.isEmpty() && p.hasPermission(r.skipPerm)) {
            p.sendMessage(txt("&eЭта награда у вас уже есть (или выше), выдана компенсация."));
            runCommands(p, r.fallback);
            return;
        }
        if (r.material != null) {
            ItemStack it = new ItemStack(r.material, Math.max(1, r.amount));
            Map<Integer, ItemStack> left = p.getInventory().addItem(it);
            for (ItemStack x : left.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), x);
            }
        }
        runCommands(p, r.commands);
    }

    private void runCommands(Player p, List<String> cmds) {
        for (String c : cmds) {
            String line = c.replace("{player}", p.getName()).replace("{uuid}", p.getUniqueId().toString());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
        }
    }

    // ---------- события мира ----------

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null) {
            return;
        }
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_BLOCK && a != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        String k = key(b);
        Session s = activeBlocks.get(k);
        if (s != null) {
            e.setCancelled(true);
            if (e.getHand() == EquipmentSlot.OFF_HAND) {
                return;
            }
            if (s.player.equals(e.getPlayer()) && !s.chosen) {
                pick(s, indexOfBlock(s, b));
            }
            return;
        }
        if (menuBlocks.contains(k)) {
            e.setCancelled(true);
            if (a == Action.RIGHT_CLICK_BLOCK && e.getHand() == EquipmentSlot.HAND) {
                openMenu(e.getPlayer());
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (isProtected(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(this::isProtected);
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::isProtected);
    }

    @EventHandler
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (isProtected(b)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (isProtected(b)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) {
            finish(s, random.nextInt(s.blocks.size()), false);
        }
    }

    // ---------- команды ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("hexacase.admin")) {
            sender.sendMessage(txt("&cНет доступа."));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(txt("&e/case give <ник> <кейс> [число] &7| &e/case count <ник> &7| &e/case setblock &7| &e/case removeblock &7| &e/case list &7| &e/case reload"));
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "give": {
                if (args.length < 3) {
                    sender.sendMessage(txt("&cИспользование: /case give <ник> <кейс> [число]"));
                    return true;
                }
                OfflinePlayer target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    target = Bukkit.getOfflinePlayer(args[1]);
                }
                if (!target.isOnline() && !target.hasPlayedBefore()) {
                    sender.sendMessage(txt("&cИгрок не найден."));
                    return true;
                }
                Crate cr = crates.get(args[2].toLowerCase());
                if (cr == null) {
                    sender.sendMessage(txt("&cТакого кейса нет. Список: /case list"));
                    return true;
                }
                int n = 1;
                if (args.length > 3) {
                    try {
                        n = Integer.parseInt(args[3]);
                    } catch (NumberFormatException ex) {
                        sender.sendMessage(txt("&cЧисло указано неверно."));
                        return true;
                    }
                }
                if (n <= 0) {
                    sender.sendMessage(txt("&cЧисло должно быть больше нуля."));
                    return true;
                }
                addCount(target.getUniqueId(), cr.id, n);
                sender.sendMessage(txt("&aВыдано: " + n + " x " + cr.name + " &aигроку " + args[1]));
                Player online = target.getPlayer();
                if (online != null) {
                    online.sendMessage(txt("&aВы получили кейс: &f" + cr.name + " &ax" + n));
                }
                return true;
            }
            case "count": {
                if (args.length < 2) {
                    sender.sendMessage(txt("&cИспользование: /case count <ник>"));
                    return true;
                }
                OfflinePlayer target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    target = Bukkit.getOfflinePlayer(args[1]);
                }
                sender.sendMessage(txt("&eКейсы игрока " + args[1] + ":"));
                for (Crate cr : crates.values()) {
                    sender.sendMessage(txt("&7- " + cr.id + ": &f" + getCount(target.getUniqueId(), cr.id)));
                }
                return true;
            }
            case "setblock": {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(txt("&cТолько для игрока."));
                    return true;
                }
                Block t = p.getTargetBlockExact(6);
                if (t == null || t.getType().isAir()) {
                    sender.sendMessage(txt("&cПосмотрите на блок (до 6 блоков)."));
                    return true;
                }
                menuBlocks.add(key(t));
                saveBlocks();
                sender.sendMessage(txt("&aБлок привязан: правый клик откроет меню кейсов."));
                return true;
            }
            case "removeblock": {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(txt("&cТолько для игрока."));
                    return true;
                }
                Block t = p.getTargetBlockExact(6);
                if (t == null || !menuBlocks.remove(key(t))) {
                    sender.sendMessage(txt("&cЭто не блок кейсов."));
                    return true;
                }
                saveBlocks();
                sender.sendMessage(txt("&aБлок отвязан."));
                return true;
            }
            case "list": {
                sender.sendMessage(txt("&eКейсы: &f" + String.join(", ", crates.keySet())));
                return true;
            }
            case "reload": {
                reloadConfig();
                loadCrates();
                sender.sendMessage(txt("&aКонфиг перезагружен. Кейсов: " + crates.size()));
                return true;
            }
            default:
                sender.sendMessage(txt("&cНеизвестная подкоманда."));
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("hexacase.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(List.of("give", "count", "setblock", "removeblock", "list", "reload"), args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("count"))) {
            return null;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return filter(new ArrayList<>(crates.keySet()), args[2]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        List<String> res = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase().startsWith(prefix.toLowerCase())) {
                res.add(o);
            }
        }
        return res;
    }
}
