package ru.metro.flashlight;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Light;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BlockVector;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Фонарик для сервера Метро.
 *
 * Пока фонарик включён и лежит в руке, перед игроком строится цепочка
 * "поддельных" блоков света. Они отправляются пакетами (sendBlockChange)
 * не только владельцу, но и всем игрокам рядом (share.radius), поэтому
 * освещённое место видят все. В реальном мире блоков нет и ничего не остаётся.
 *
 * Включение/выключение: ПКМ фонариком или /flashlight toggle.
 * Заряд хранится в прочности предмета и тратится только пока фонарик включён;
 * когда он кончается, из инвентаря берётся батарейка.
 */
public final class MetroFlashlight extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    /** Луч одного игрока: какие блоки света он создаёт и кто их сейчас видит. */
    private static final class Beam {
        World world;
        Map<BlockVector, Integer> blocks = new HashMap<>();
        Set<UUID> viewers = new HashSet<>();
    }

    private final MiniMessage mm = MiniMessage.miniMessage();

    // Настройки
    private Material flashMaterial;
    private int flashModel;
    private double range;
    private int[] levels;
    private int updateTicks;
    private int drainSeconds;
    private int drainAmount;
    private Material batteryMaterial;
    private int batteryModel;
    private int restorePercent;
    private int lowThreshold;
    private boolean defaultEnabled;
    private boolean clickToggle;
    private boolean toggleSound;
    private boolean shareEnabled;
    private double shareRadius;
    private String msgLow;
    private String msgDead;
    private String msgReplaced;
    private String msgReloaded;
    private String msgOn;
    private String msgOff;

    // Лучи всех игроков (ключ - владелец фонарика)
    private final Map<UUID, Beam> beams = new HashMap<>();
    // Включён/выключен фонарик у игрока (если записи нет - берётся toggle.default-enabled)
    private final Map<UUID, Boolean> state = new HashMap<>();
    // Защита от двойного срабатывания ПКМ (основная + вторая рука)
    private final Map<UUID, Long> lastToggle = new HashMap<>();

    private BukkitTask lightTask;
    private BukkitTask drainTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("flashlight") != null) {
            getCommand("flashlight").setExecutor(this);
            getCommand("flashlight").setTabCompleter(this);
        }
        start();
    }

    @Override
    public void onDisable() {
        stop();
        clearAll();
        state.clear();
        lastToggle.clear();
    }

    private void start() {
        loadSettings();
        lightTask = getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                updateLight(p);
            }
        }, 5L, updateTicks);
        drainTask = getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                drain(p);
            }
        }, 20L, Math.max(1, drainSeconds) * 20L);
    }

    private void stop() {
        if (lightTask != null) {
            lightTask.cancel();
            lightTask = null;
        }
        if (drainTask != null) {
            drainTask.cancel();
            drainTask = null;
        }
    }

    private void loadSettings() {
        reloadConfig();
        flashMaterial = material(getConfig().getString("flashlight.material"), Material.STICK);
        flashModel = getConfig().getInt("flashlight.custom-model-data", 49);
        range = Math.max(2.0, getConfig().getDouble("flashlight.range", 8.0));
        updateTicks = Math.max(1, getConfig().getInt("flashlight.update-ticks", 3));

        List<Integer> raw = getConfig().getIntegerList("flashlight.light-levels");
        if (raw.isEmpty()) {
            raw = List.of(4, 6, 7);
        }
        levels = new int[raw.size()];
        for (int i = 0; i < levels.length; i++) {
            levels[i] = Math.max(1, Math.min(15, raw.get(i)));
        }

        defaultEnabled = getConfig().getBoolean("toggle.default-enabled", false);
        clickToggle = getConfig().getBoolean("toggle.click", true);
        toggleSound = getConfig().getBoolean("toggle.sound", true);

        shareEnabled = getConfig().getBoolean("share.enabled", true);
        shareRadius = Math.max(4.0, getConfig().getDouble("share.radius", 48.0));

        drainSeconds = Math.max(1, getConfig().getInt("drain.interval-seconds", 1));
        drainAmount = Math.max(0, getConfig().getInt("drain.amount", 1));

        batteryMaterial = material(getConfig().getString("battery.material"), Material.GOLD_NUGGET);
        batteryModel = getConfig().getInt("battery.custom-model-data", 50);
        restorePercent = Math.max(1, Math.min(100, getConfig().getInt("battery.restore-percent", 100)));

        msgLow = getConfig().getString("messages.low", "<yellow>Заряд фонарика: {percent}%");
        msgDead = getConfig().getString("messages.dead", "<red>Фонарик не светит: нужна батарейка");
        msgReplaced = getConfig().getString("messages.replaced", "<green>Батарейка заменена");
        msgReloaded = getConfig().getString("messages.reloaded", "<green>Конфигурация фонарика перезагружена");
        msgOn = getConfig().getString("messages.on", "<green>Фонарик включён");
        msgOff = getConfig().getString("messages.off", "<gray>Фонарик выключен");
        lowThreshold = getConfig().getInt("messages.low-threshold-percent", 20);
    }

    private Material material(String name, Material fallback) {
        if (name == null) {
            return fallback;
        }
        Material m = Material.matchMaterial(name);
        if (m == null) {
            getLogger().warning("Неизвестный материал в config.yml: " + name);
            return fallback;
        }
        return m;
    }

    // ------------------------------------------------------------------
    // Предметы
    // ------------------------------------------------------------------

    private boolean matches(ItemStack stack, Material material, int model) {
        if (stack == null || stack.getType() != material) {
            return false;
        }
        if (model < 0) {
            return true;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || !meta.hasCustomModelDataComponent()) {
            return false;
        }
        CustomModelDataComponent component = meta.getCustomModelDataComponent();
        for (float f : component.getFloats()) {
            if (Math.round(f) == model) {
                return true;
            }
        }
        return false;
    }

    /** Есть ли у предмета запас заряда. Без прочности фонарик считается бесконечным. */
    private boolean isCharged(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof Damageable d && d.hasMaxDamage()) {
            return d.getMaxDamage() - d.getDamage() > 0;
        }
        return true;
    }

    /** Фонарик в любой руке; приоритет у заряженного, потом у основной руки. */
    private ItemStack findFlashlight(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack main = inv.getItemInMainHand();
        ItemStack off = inv.getItemInOffHand();
        boolean mainOk = matches(main, flashMaterial, flashModel);
        boolean offOk = matches(off, flashMaterial, flashModel);

        if (mainOk && isCharged(main)) {
            return main;
        }
        if (offOk && isCharged(off)) {
            return off;
        }
        if (mainOk) {
            return main;
        }
        if (offOk) {
            return off;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Включение / выключение
    // ------------------------------------------------------------------

    private boolean isOn(Player p) {
        return state.getOrDefault(p.getUniqueId(), defaultEnabled);
    }

    private void setOn(Player p, boolean on) {
        state.put(p.getUniqueId(), on);
        if (!on) {
            clear(p.getUniqueId());
        }
        p.sendActionBar(mm.deserialize(on ? msgOn : msgOff));
        if (toggleSound) {
            p.playSound(p.getLocation(), Sound.BLOCK_LEVER_CLICK, 0.6f, on ? 1.5f : 1.0f);
        }
        if (on) {
            updateLight(p);
        }
    }

    // ------------------------------------------------------------------
    // Заряд и батарейка (раз в drain.interval-seconds, только пока включён)
    // ------------------------------------------------------------------

    private void drain(Player p) {
        if (!isOn(p)) {
            return;
        }
        ItemStack flash = findFlashlight(p);
        if (flash == null) {
            return;
        }

        if (!isCharged(flash)) {
            replaceBattery(p, flash);
            return;
        }

        ItemMeta meta = flash.getItemMeta();
        if (!(meta instanceof Damageable d) || !d.hasMaxDamage() || drainAmount <= 0) {
            return;
        }

        int max = d.getMaxDamage();
        d.setDamage(Math.min(max, d.getDamage() + drainAmount));
        flash.setItemMeta(d);

        int remaining = max - d.getDamage();
        if (remaining <= 0) {
            replaceBattery(p, flash);
        } else {
            int percent = remaining * 100 / max;
            if (percent <= lowThreshold) {
                p.sendActionBar(mm.deserialize(msgLow.replace("{percent}", String.valueOf(percent))));
            }
        }
    }

    private void replaceBattery(Player p, ItemStack flash) {
        PlayerInventory inv = p.getInventory();
        int slot = -1;
        for (int i = 0; i <= 40; i++) {
            if (matches(inv.getItem(i), batteryMaterial, batteryModel)) {
                slot = i;
                break;
            }
        }
        if (slot == -1) {
            p.sendActionBar(mm.deserialize(msgDead));
            return;
        }

        ItemStack battery = inv.getItem(slot);
        if (battery.getAmount() > 1) {
            battery.setAmount(battery.getAmount() - 1);
        } else {
            inv.setItem(slot, null);
        }

        ItemMeta meta = flash.getItemMeta();
        if (meta instanceof Damageable d && d.hasMaxDamage()) {
            int restore = Math.max(1, d.getMaxDamage() * restorePercent / 100);
            d.setDamage(Math.max(0, d.getDamage() - restore));
            flash.setItemMeta(d);
        }
        p.sendActionBar(mm.deserialize(msgReplaced));
    }

    // ------------------------------------------------------------------
    // Свет
    // ------------------------------------------------------------------

    private void updateLight(Player p) {
        UUID id = p.getUniqueId();
        ItemStack flash = findFlashlight(p);
        if (flash == null || !isOn(p) || !isCharged(flash)) {
            clear(id);
            return;
        }

        Location eye = p.getEyeLocation();
        World world = eye.getWorld();
        Vector dir = eye.getDirection().normalize();

        RayTraceResult hit = world.rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
        double dist = hit == null ? range : eye.toVector().distance(hit.getHitPosition());

        Map<BlockVector, Integer> desired = new LinkedHashMap<>();
        int n = levels.length;
        for (int i = 0; i < n; i++) {
            double fraction = (i + 1.0) / n;
            double d = dist * fraction;
            if (i == n - 1 && hit != null) {
                d -= 0.4; // не внутри стены
            }
            d = Math.max(1.5, d);

            Location point = eye.clone().add(dir.clone().multiply(d));
            Block block = point.getBlock();
            int tries = 0;
            while (!block.getType().isAir() && tries < 3) {
                point.subtract(dir.clone().multiply(0.5));
                block = point.getBlock();
                tries++;
            }
            if (!block.getType().isAir()) {
                continue;
            }
            BlockVector key = new BlockVector(block.getX(), block.getY(), block.getZ());
            desired.merge(key, levels[i], Math::max);
        }

        Beam beam = beams.get(id);
        if (beam == null) {
            beam = new Beam();
            beams.put(id, beam);
        }
        if (beam.world != null && !beam.world.equals(world)) {
            release(id, beam);
            beam.blocks = new HashMap<>();
            beam.viewers = new HashSet<>();
        }

        // Кто должен видеть свет: сам игрок и все рядом в том же мире
        Set<UUID> newViewers = new HashSet<>();
        newViewers.add(id);
        if (shareEnabled) {
            double r2 = shareRadius * shareRadius;
            for (Player v : world.getPlayers()) {
                if (v != p && v.getLocation().distanceSquared(eye) <= r2) {
                    newViewers.add(v.getUniqueId());
                }
            }
        }

        // Те, кто отошёл далеко или ушёл: возвращаем им настоящие блоки
        for (UUID vid : beam.viewers) {
            if (newViewers.contains(vid)) {
                continue;
            }
            Player v = Bukkit.getPlayer(vid);
            if (v != null && v.getWorld().equals(world)) {
                for (BlockVector key : beam.blocks.keySet()) {
                    restore(v, world, key, id);
                }
            }
        }

        for (UUID vid : newViewers) {
            Player v = vid.equals(id) ? p : Bukkit.getPlayer(vid);
            if (v == null) {
                continue;
            }
            if (!beam.viewers.contains(vid)) {
                // Новый зритель: отправляем весь луч целиком
                for (Map.Entry<BlockVector, Integer> e : desired.entrySet()) {
                    sendLight(v, world, e.getKey(), effective(vid, world, e.getKey(), e.getValue(), id));
                }
            } else {
                // Старый зритель: только изменения
                for (BlockVector key : beam.blocks.keySet()) {
                    if (!desired.containsKey(key)) {
                        restore(v, world, key, id);
                    }
                }
                for (Map.Entry<BlockVector, Integer> e : desired.entrySet()) {
                    Integer previous = beam.blocks.get(e.getKey());
                    if (previous == null || !previous.equals(e.getValue())) {
                        sendLight(v, world, e.getKey(), effective(vid, world, e.getKey(), e.getValue(), id));
                    }
                }
            }
        }

        beam.blocks = desired;
        beam.viewers = newViewers;
        beam.world = world;
    }

    /** Самый яркий свет в точке для зрителя с учётом лучей других игроков. */
    private int effective(UUID viewer, World world, BlockVector pos, int own, UUID excludeOwner) {
        int best = own;
        for (Map.Entry<UUID, Beam> e : beams.entrySet()) {
            if (e.getKey().equals(excludeOwner)) {
                continue;
            }
            Beam b = e.getValue();
            if (!world.equals(b.world) || !b.viewers.contains(viewer)) {
                continue;
            }
            Integer level = b.blocks.get(pos);
            if (level != null && level > best) {
                best = level;
            }
        }
        return best;
    }

    private void sendLight(Player p, World world, BlockVector pos, int level) {
        Light data = (Light) Bukkit.createBlockData(Material.LIGHT);
        data.setLevel(level);
        p.sendBlockChange(new Location(world, pos.getBlockX(), pos.getBlockY(), pos.getBlockZ()), data);
    }

    /** Возвращает зрителю настоящий блок (или свет чужого луча, если он тут тоже есть). */
    private void restore(Player viewer, World world, BlockVector pos, UUID excludeOwner) {
        int other = effective(viewer.getUniqueId(), world, pos, 0, excludeOwner);
        if (other > 0) {
            sendLight(viewer, world, pos, other);
            return;
        }
        Location loc = new Location(world, pos.getBlockX(), pos.getBlockY(), pos.getBlockZ());
        viewer.sendBlockChange(loc, loc.getBlock().getBlockData());
    }

    /** Убирает луч у всех, кто его видел. */
    private void release(UUID owner, Beam beam) {
        if (beam.world == null) {
            return;
        }
        for (UUID vid : beam.viewers) {
            Player v = Bukkit.getPlayer(vid);
            if (v == null || !v.getWorld().equals(beam.world)) {
                continue;
            }
            for (BlockVector key : new ArrayList<>(beam.blocks.keySet())) {
                restore(v, beam.world, key, owner);
            }
        }
    }

    /** Полностью убирает луч игрока. */
    private void clear(UUID owner) {
        Beam beam = beams.remove(owner);
        if (beam != null) {
            release(owner, beam);
        }
    }

    private void clearAll() {
        for (UUID owner : new ArrayList<>(beams.keySet())) {
            clear(owner);
        }
        beams.clear();
    }

    /** Игрок вышел или сменил мир: его клиент больше не хранит чужие лучи. */
    private void dropViewer(UUID viewer) {
        for (Beam b : beams.values()) {
            b.viewers.remove(viewer);
        }
    }

    // ------------------------------------------------------------------
    // События и команда
    // ------------------------------------------------------------------

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!clickToggle || event.getHand() == null) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!matches(event.getItem(), flashMaterial, flashModel)) {
            return;
        }
        Player p = event.getPlayer();
        if (!p.hasPermission("flashlight.use")) {
            return;
        }
        // Не мешаем дверям, кнопкам, сундукам и т.п. (с Shift - переключаем)
        Block clicked = event.getClickedBlock();
        if (clicked != null && clicked.getType().isInteractable() && !p.isSneaking()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastToggle.get(p.getUniqueId());
        if (last != null && now - last < 250) {
            return;
        }
        lastToggle.put(p.getUniqueId(), now);
        setOn(p, !isOn(p));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        clear(id);
        dropViewer(id);
        state.remove(id);
        lastToggle.remove(id);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        clear(id);
        dropViewer(id);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "toggle" : args[0].toLowerCase();

        if (sub.equals("reload")) {
            if (!sender.hasPermission("flashlight.admin")) {
                sender.sendMessage(mm.deserialize("<red>Нет прав"));
                return true;
            }
            stop();
            clearAll();
            start();
            sender.sendMessage(mm.deserialize(msgReloaded));
            return true;
        }

        if (sub.equals("toggle") || sub.equals("on") || sub.equals("off")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Только для игроков");
                return true;
            }
            if (!p.hasPermission("flashlight.use")) {
                p.sendMessage(mm.deserialize("<red>Нет прав"));
                return true;
            }
            boolean on = sub.equals("toggle") ? !isOn(p) : sub.equals("on");
            setOn(p, on);
            return true;
        }

        sender.sendMessage("/" + label + " <toggle|on|off|reload>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            List<String> all = new ArrayList<>(List.of("toggle", "on", "off"));
            if (sender.hasPermission("flashlight.admin")) {
                all.add("reload");
            }
            for (String s : all) {
                if (s.startsWith(prefix)) {
                    out.add(s);
                }
            }
        }
        return out;
    }
}
