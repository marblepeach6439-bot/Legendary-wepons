package dev.dwarven;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.function.Consumer;

public class DwarvenWeapons extends JavaPlugin implements Listener {

    static final List<String> IDS = List.of("bloody_eclipse", "stormcaller", "dwarven_pickaxe",
            "ten_ton_axe", "void_blade", "inferno", "sculk_battle_axe", "royal_spear", "kings_crown", "divine_judgement", "hammer_of_the_void",
            "temporal_reaver", "primal_bow", "mad_scientists_crossbow");

    NamespacedKey KEY, HUNT_KEY, PRIMAL_KEY, CROSS_KEY;
    boolean internal = false;   // guard for our own true-damage calls
    boolean mining = false;     // guard for 3x3 mining recursion

    final Map<String, Long> cooldowns = new HashMap<>();
    final Map<UUID, Long> bloodbath = new HashMap<>();
    final Map<UUID, Long> eclipsed = new HashMap<>();   // victims of Blinding Eclipse (no wind charges/cobwebs)
    final Map<UUID, Long> stunned = new HashMap<>();
    final Map<String, Integer> hitCounters = new HashMap<>();
    final Map<Block, Material> domeFloor = new HashMap<>();   // breakable floor under the dome (block -> type we placed)
    final Map<Block, Material> dome = new HashMap<>();  // dome block -> original material (always air)
    final Map<UUID, Incin> incin = new HashMap<>();
    final Map<Material, Material> smelt = new HashMap<>();
    final Map<UUID, Long> lungeCd = new HashMap<>();
    final Map<UUID, Integer> airHits = new HashMap<>();   // mace hits since last touching the ground
    final Map<UUID, Thrown> thrown = new HashMap<>();
    final Map<UUID, Long> holdUntil = new HashMap<>();   // action-bar message hold (HUD pauses)
    final Set<UUID> huntReady = new HashSet<>();
    final Map<UUID, Integer> momentum = new HashMap<>();
    final Map<UUID, Mark> marks = new HashMap<>();
    record Mark(UUID holder, long end, double bonus) {}
    static class Thrown { ItemDisplay disp; Location land; boolean landed; }

    record Incin(Location center, int radius, long end) {}

    // ------------------------------------------------------------------ lifecycle
    @Override
    public void onEnable() {
        saveDefaultConfig();
        KEY = new NamespacedKey(this, "weapon");
        HUNT_KEY = new NamespacedKey(this, "hunt_arrow");
        PRIMAL_KEY = new NamespacedKey(this, "primal_arrow");
        CROSS_KEY = new NamespacedKey(this, "cocktail_arrow");
        getServer().getPluginManager().registerEvents(this, this);
        buildSmeltMap();
        new BukkitRunnable() {
            @Override public void run() { for (Player p : Bukkit.getOnlinePlayers()) passives(p); }
        }.runTaskTimer(this, 20, 20);
        new BukkitRunnable() {          // cooldown HUD + weapon auras + warden peace
            int t = 0;
            @Override public void run() {
                t++;
                Set<UUID> axeCarriers = new HashSet<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    String id = id(p.getInventory().getItemInMainHand());
                    if (id != null) { if (t % 2 == 0) auraFx(p, id); hud(p, id); }
                    if ("kings_crown".equals(id(p.getInventory().getHelmet())) && t % 2 == 0) {
                        Location h = p.getEyeLocation().add(0, 0.55, 0);
                        sp(p.getWorld(), Particle.END_ROD, h, 1, 0.25, 0.05, 0.25, 0.01);
                        dustAt(h, 255, 210, 70, 0.8f, 1, 0.3);
                    }
                    if (hasWeapon(p, "sculk_battle_axe")) axeCarriers.add(p.getUniqueId());
                }
                if (!axeCarriers.isEmpty()) {
                    for (World w : Bukkit.getWorlds()) for (Warden wd : w.getEntitiesByClass(Warden.class)) {
                        if (wd.getTarget() instanceof Player tp && axeCarriers.contains(tp.getUniqueId())) wd.setTarget(null);
                        for (Player pl : w.getPlayers())
                            if (axeCarriers.contains(pl.getUniqueId()) && pl.getLocation().distanceSquared(wd.getLocation()) < 6400) wd.clearAnger(pl);
                    }
                }
            }
        }.runTaskTimer(this, 10, 5);
    }

    @Override
    public void onDisable() {
        for (Map.Entry<Block, Material> en : dome.entrySet()) {
            Material t = en.getKey().getType();
            if (t == Material.DIRT || t == Material.STONE) en.getKey().setType(en.getValue(), false);
        }
        dome.clear();
        for (Map.Entry<Block, Material> en : domeFloor.entrySet())
            if (en.getKey().getType() == en.getValue()) en.getKey().setType(Material.AIR, false);
        domeFloor.clear();
    }

    void buildSmeltMap() {
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe r = it.next();
            if (r instanceof FurnaceRecipe fr && fr.getInputChoice() instanceof RecipeChoice.MaterialChoice mc) {
                for (Material m : mc.getChoices()) smelt.putIfAbsent(m, fr.getResult().getType());
            }
        }
    }

    // ------------------------------------------------------------------ items
    String id(ItemStack i) {
        if (i == null || !i.hasItemMeta()) return null;
        String v = i.getItemMeta().getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
        return "skulk_battle_axe".equals(v) ? "sculk_battle_axe" : v;     // items made before the spelling fix
    }

    ItemStack make(String id) {
        Material mat; String name; TextColor col; List<String> lore = new ArrayList<>();
        Map<Enchantment, Integer> en = new LinkedHashMap<>();
        switch (id) {
            case "bloody_eclipse" -> {
                mat = Material.NETHERITE_SWORD; name = "Bloody Eclipse"; col = TextColor.color(0xE02828);
                en.put(Enchantment.SHARPNESS, 5); en.put(Enchantment.FIRE_ASPECT, 3);
                en.put(Enchantment.MENDING, 1); en.put(Enchantment.UNBREAKING, 3);
                lore = List.of("Ability 1 [F]: Bloodbath - every hit crits for 10s",
                        "Ability 2 [Shift+F]: Blinding Eclipse - blind enemies, no wind charges/cobwebs 10s",
                        "Passive: Bleed every 25 hits, Speed II, Strength I");
            }
            case "stormcaller" -> {
                mat = Material.TRIDENT; name = "Stormcaller"; col = TextColor.color(0x5AC8FF);
                en.put(Enchantment.RIPTIDE, 3); en.put(Enchantment.SHARPNESS, 5); en.put(Enchantment.UNBREAKING, 3); en.put(Enchantment.MENDING, 1);
                lore = List.of("Ability 1 [F]: Lightning Storm - 3 strikes, 2 hearts true damage each",
                        "Ability 2 [Shift+F]: Tides Call - pull & drown everything within 20 blocks",
                        "Passive: lightning every 5 hits, Water Breathing, Dolphin's Grace, Conduit Power");
            }
            case "dwarven_pickaxe" -> {
                mat = Material.NETHERITE_PICKAXE; name = "Dwarven Pickaxe"; col = TextColor.color(0xCDCDCD);
                en.put(Enchantment.SHARPNESS, 10); en.put(Enchantment.EFFICIENCY, 5); en.put(Enchantment.FORTUNE, 3);
                en.put(Enchantment.MENDING, 1); en.put(Enchantment.UNBREAKING, 3);
                lore = List.of("Ability [F]: Call of the Deep - unbreakable dome for 1 minute",
                        "Passive: Autosmelt, 3x3 mining, doubles all ores");
            }
            case "ten_ton_axe" -> {
                mat = Material.NETHERITE_AXE; name = "10 Ton Axe"; col = TextColor.color(0xC8C8CD);
                en.put(Enchantment.SHARPNESS, 5); en.put(Enchantment.UNBREAKING, 3);
                en.put(Enchantment.MENDING, 1); en.put(Enchantment.EFFICIENCY, 5);
                lore = List.of("Ability 1 [F]: Stunning Strike - stun 5s + 2 hearts true damage",
                        "Ability 2 [Shift+F]: Durability Drain - 2 hearts true damage + 30 armor durability");
            }
            case "void_blade" -> {
                mat = Material.NETHERITE_SWORD; name = "Void Blade"; col = TextColor.color(0xAA50FF);
                en.put(Enchantment.SHARPNESS, 5); en.put(Enchantment.FIRE_ASPECT, 2);
                en.put(Enchantment.UNBREAKING, 3); en.put(Enchantment.MENDING, 1);
                lore = List.of("Ability 1 [F]: Void Walk - teleport 20 blocks forward",
                        "Ability 2 [Shift+F]: Pull of the Void - pull target (40 blocks) to you",
                        "Passive: Speed III, Strength I, Fire Resistance");
            }
            case "inferno" -> {
                mat = Material.NETHERITE_SWORD; name = "Inferno"; col = TextColor.color(0xFF9628);
                en.put(Enchantment.SHARPNESS, 5); en.put(Enchantment.FIRE_ASPECT, 2);
                en.put(Enchantment.UNBREAKING, 3); en.put(Enchantment.MENDING, 1);
                lore = List.of("Ability 1 [F]: Acidic Blaze - unquenchable fire that ignores fire resistance, 10s",
                        "Ability 2 [Shift+F]: Incineration - evaporates water, enemies take 2x damage, 20s");
            }
            case "sculk_battle_axe" -> {
                mat = Material.NETHERITE_AXE; name = "Sculk Battle Axe"; col = TextColor.color(0x28C8DC);
                en.put(Enchantment.SHARPNESS, 5); en.put(Enchantment.UNBREAKING, 3); en.put(Enchantment.MENDING, 1);
                lore = List.of("Ability 1 [F]: Sculk Beams - 3 warden beams, 2 hearts true damage + heavy armor durability each",
                        "Ability 2 [Shift+F]: Summon a Warden",
                        "Passive: Wardens are not hostile to you, Strength I, Speed II, Resistance I");
            }
            case "royal_spear" -> {
                Material sp = Material.matchMaterial("NETHERITE_SPEAR");
                mat = sp != null ? sp : Material.NETHERITE_SWORD; name = "Royal Spear"; col = TextColor.color(0xFFC83C);
                Enchantment lunge = lungeEnchant();
                if (lunge != null) en.put(lunge, 3);
                en.put(Enchantment.SHARPNESS, 5); en.put(Enchantment.UNBREAKING, 3); en.put(Enchantment.MENDING, 1);
                lore = List.of("Ability 1 [F]: Royal Judgement - throw the spear: 3 hearts true damage, Wither II + Poison II 20s",
                        "Ability 2 [Shift+F]: Piercing Strike - 30 block beam through blocks, 4 hearts true damage",
                        "Passive: Speed II.  Nerf: you can only lunge once every 5 seconds");
            }
            case "kings_crown" -> {
                mat = Material.NETHERITE_HELMET; name = "King's Crown"; col = TextColor.color(0xFFD700);
                en.put(Enchantment.PROTECTION, 5);
                lore = List.of("Unbreakable",
                        "Worn: Speed III, Strength I, +10 extra hearts");
            }
            case "divine_judgement" -> {
                mat = Material.MACE; name = "Divine Judgement"; col = TextColor.color(0xF5CD5A);
                en.put(Enchantment.DENSITY, 2); en.put(Enchantment.WIND_BURST, 1);
                en.put(Enchantment.UNBREAKING, 3); en.put(Enchantment.MENDING, 1);
                lore = List.of("Ability 1 [F]: Smite - throw the mace, pull the enemy to you, 3 hearts true damage",
                        "Ability 2: Final Verdict - 5th mace hit without touching the ground explodes: 2x damage, 60 durability to every armor piece",
                        "Passive: Shift+Right-click cycles Wind Burst I / II / III");
            }
            case "hammer_of_the_void" -> {
                mat = Material.MACE; name = "Hammer of the Void"; col = TextColor.color(0xAA50FF);
                en.put(Enchantment.DENSITY, 2); en.put(Enchantment.WIND_BURST, 1);
                en.put(Enchantment.UNBREAKING, 3); en.put(Enchantment.MENDING, 1);
                lore = List.of("Ability 1 [F]: throw the mace, then Right-click to teleport to it",
                        "Ability 2: Void Strike - the hit after 5 mace hits without touching the ground: 2x damage, Wither II + Poison II 20s",
                        "Passive: Shift+Right-click cycles Wind Burst I / II / III");
            }
            case "temporal_reaver" -> {
                mat = Material.NETHERITE_SWORD; name = "Temporal Reaver"; col = TextColor.color(0x32E164);
                en.put(Enchantment.SHARPNESS, 5); en.put(Enchantment.FIRE_ASPECT, 2); en.put(Enchantment.LOOTING, 3);
                en.put(Enchantment.UNBREAKING, 3); en.put(Enchantment.MENDING, 1);
                lore = List.of("Ability 1 [F]: Temporal Cleave - freezes time around you (players, mobs, projectiles) for 10s",
                        "Ability 2 [Shift+F]: Temporal Dismemberment - 3-phase execution of your crosshair target");
            }
            case "primal_bow" -> {
                mat = Material.BOW; name = "Primal Bow"; col = TextColor.color(0x50DCDC);
                en.put(Enchantment.POWER, 5); en.put(Enchantment.PUNCH, 2); en.put(Enchantment.UNBREAKING, 3);
                en.put(Enchantment.MENDING, 1); en.put(Enchantment.INFINITY, 1);
                lore = List.of("Ability 1 [F]: Live for the Hunt - next arrow: Glowing 5 min, Slowness 1 min, Mining Fatigue 1 min",
                        "Passive: Hunter's Momentum - +1 damage per shot, resets when you are hit");
            }
            case "mad_scientists_crossbow" -> {
                mat = Material.CROSSBOW; name = "Mad Scientist's Crossbow"; col = TextColor.color(0x78FF1E);
                en.put(Enchantment.QUICK_CHARGE, 5); en.put(Enchantment.MULTISHOT, 1);
                en.put(Enchantment.UNBREAKING, 3); en.put(Enchantment.MENDING, 1);
                lore = List.of("Passive: Furious Cocktail - every arrow carries a random potion effect",
                        "Passive: Grapple - every hit pulls the enemy 5 blocks toward you");
            }
            default -> { return null; }
        }
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, col).decoration(TextDecoration.ITALIC, false).decorate(TextDecoration.BOLD));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(l);
        for (Map.Entry<Enchantment, Integer> e : en.entrySet()) meta.addEnchant(e.getKey(), e.getValue(), true);
        meta.setUnbreakable(true);
        if (id.equals("kings_crown")) {
            EquippableComponent eq = meta.getEquippable();      // worn model: assets/dwarven/equipment/kings_crown.json
            eq.setSlot(EquipmentSlot.HEAD);
            eq.setModel(new NamespacedKey("dwarven", "kings_crown"));
            meta.setEquippable(eq);
        }
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, id);
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();   // pack swaps the model; no pack = vanilla look
        cmd.setStrings(List.of(id));
        meta.setCustomModelDataComponent(cmd);
        item.setItemMeta(meta);
        return item;
    }

    // ------------------------------------------------------------------ command
    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) { s.sendMessage("/dweapons <give|list|reload> [player] [weapon]"); return true; }
        switch (a[0].toLowerCase()) {
            case "list" -> s.sendMessage("Weapons: " + String.join(", ", IDS));
            case "reload" -> { reloadConfig(); s.sendMessage("Reloaded."); }
            case "give" -> {
                if (a.length < 3) { s.sendMessage("/dweapons give <player> <weapon|all>"); return true; }
                Player t = Bukkit.getPlayer(a[1]);
                if (t == null) { s.sendMessage("Player not found."); return true; }
                if (a[2].equalsIgnoreCase("all")) { for (String i : IDS) give(t, i); }
                else if (!give(t, a[2].toLowerCase())) s.sendMessage("Unknown weapon. Use /dweapons list");
                else s.sendMessage("Given.");
            }
            default -> s.sendMessage("Unknown subcommand.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("give", "list", "reload"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("give")) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        else if (a.length == 3 && a[0].equalsIgnoreCase("give")) { out.addAll(IDS); out.add("all"); }
        out.removeIf(x -> !x.toLowerCase().startsWith(a[a.length - 1].toLowerCase()));
        return out;
    }

    boolean give(Player p, String id) {
        ItemStack it = make(id);
        if (it == null) return false;
        p.getInventory().addItem(it).values().forEach(x -> p.getWorld().dropItemNaturally(p.getLocation(), x));
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        String url = getConfig().getString("resource-pack-url", "");
        if (url != null && !url.isBlank()) e.getPlayer().setResourcePack(url);
    }

    // ------------------------------------------------------------------ helpers
    boolean cd(Player p, String key, int def) { return cd(p, key, def, false); }

    boolean cd(Player p, String key, int def, boolean quiet) {
        long now = System.currentTimeMillis();
        String k = p.getUniqueId() + key;
        Long until = cooldowns.get(k);
        if (until != null && until > now) {
            if (!quiet) msg(p, Component.text(key.replace('_', ' ') + " on cooldown: " + ((until - now + 999) / 1000) + "s", NamedTextColor.RED));
            return false;
        }
        cooldowns.put(k, now + getConfig().getInt("cooldowns." + key, def) * 1000L);
        return true;
    }

    void trueDamage(LivingEntity t, Player src, double amt) {
        if (t == null || t.isDead() || !t.isValid()) return;
        if (t instanceof Player pl && (pl.getGameMode() == GameMode.CREATIVE || pl.getGameMode() == GameMode.SPECTATOR)) return;
        internal = true;
        try { if (src != null) t.damage(0.01, src); else t.damage(0.01); } finally { internal = false; }
        double nh = t.getHealth() - amt;
        if (nh <= 0) t.setHealth(0); else t.setHealth(nh);
    }

    void effect(Player p, PotionEffectType t, int amp) {
        p.addPotionEffect(new PotionEffect(t, 60, amp, true, false, true));
    }

    boolean isStunned(Player p) {
        Long e = stunned.get(p.getUniqueId());
        if (e == null) return false;
        if (e < System.currentTimeMillis()) { stunned.remove(p.getUniqueId()); return false; }
        return true;
    }

    LivingEntity lookTarget(Player p, int range) {
        Entity t = p.getTargetEntity(range);
        return (t instanceof LivingEntity le && !(t instanceof ArmorStand)) ? le : null;
    }


    Enchantment lungeEnchant() { return Registry.ENCHANTMENT.get(NamespacedKey.minecraft("lunge")); }

    boolean hasWeapon(Player p, String id) {
        for (ItemStack i : p.getInventory().getContents()) if (id.equals(id(i))) return true;
        return false;
    }

    void setLunge(Player p, boolean on) {
        Enchantment l = lungeEnchant();
        if (l == null) return;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (!"royal_spear".equals(id(it))) continue;
            if (on) it.addUnsafeEnchantment(l, 3); else it.removeEnchantment(l);
            inv.setItem(i, it);
        }
    }

    void drainArmor(LivingEntity t, int amount) {
        EntityEquipment eq = t.getEquipment();
        if (eq == null) return;
        ItemStack[] arm = eq.getArmorContents();
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < arm.length; i++) if (arm[i] != null && arm[i].getType().getMaxDurability() > 0) idx.add(i);
        if (idx.isEmpty()) return;
        int i = idx.get(new Random().nextInt(idx.size()));
        ItemStack it = arm[i];
        if (it.getItemMeta() instanceof Damageable dm) {
            int nd = dm.getDamage() + amount;
            if (nd >= it.getType().getMaxDurability()) { arm[i] = null; t.getWorld().playSound(t.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f); }
            else { dm.setDamage(nd); it.setItemMeta(dm); }
            eq.setArmorContents(arm);
        }
    }

    void beamParticles(Location a, Location b, Particle particle) {
        Vector d = b.toVector().subtract(a.toVector());
        double len = d.length(); if (len < 0.01) return;
        d.normalize();
        for (double x = 0; x < len; x += 0.6) sp(a.getWorld(), particle, a.clone().add(d.clone().multiply(x)), 1, 0, 0, 0, 0);
    }

    // ---- Sculk Battle Axe
    void sculkBeams(Player p) {
        LivingEntity t = lookTarget(p, 30);
        if (t == null) { msg(p, Component.text("No target in sight", NamedTextColor.GRAY)); return; }
        if (!cd(p, "sculk_beams", 60)) return;
        int drain = getConfig().getInt("sculk-beam-armor-durability", 50);
        World w = p.getWorld();
        for (int i = 0; i < 3; i++) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!t.isValid() || t.isDead() || !p.isOnline()) return;
                Location from = p.getEyeLocation().add(0, -0.2, 0), to = t.getLocation().add(0, t.getHeight() / 2, 0);
                beamParticles(from, to, Particle.SONIC_BOOM);
                helix(from, to, 0.6, 0.3, l -> { sp(w, Particle.SCULK_SOUL, l, 1, 0, 0, 0, 0.01); dustAt(l, 30, 200, 230, 1.5f, 1, 0.05); });
                w.playSound(p.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 1f, 1f);
                sp(w, Particle.SCULK_SOUL, to, 25, .4, .6, .4, 0.08);
                expandRing(t.getLocation(), 3, 6, l -> dustAt(l, 30, 200, 230, 1.5f, 1, 0.05));
                trueDamage(t, p, 4.0);
                drainArmor(t, drain);
            }, i * 6L);
        }
    }

    void summonWarden(Player p) {
        if (!cd(p, "summon_warden", 300)) return;
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) dir = new Vector(0, 0, 1);
        Location l = p.getLocation().add(dir.normalize().multiply(3));
        World world = l.getWorld();
        Warden w = world.spawn(l, Warden.class);
        LivingEntity t = lookTarget(p, 30);
        if (t != null) w.setAnger(t, 150);
        world.playSound(l, Sound.ENTITY_WARDEN_EMERGE, 1f, 1f);
        world.playSound(l, Sound.ENTITY_WARDEN_ROAR, 1f, 0.8f);
        expandRing(l, 8, 12, x -> { sp(world, Particle.SCULK_SOUL, x, 2, .1, .5, .1, 0.03); dustAt(x, 20, 160, 190, 1.5f, 1, 0.05); });
        column(l, 5, x -> sp(world, Particle.SCULK_SOUL, x, 3, .5, .1, .5, 0.02));
        Bukkit.getScheduler().runTaskLater(this, () -> { if (w.isValid()) w.remove(); },
                getConfig().getInt("warden-lifetime-seconds", 60) * 20L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onWardenTarget(EntityTargetLivingEntityEvent e) {
        if (e.getEntity() instanceof Warden && e.getTarget() instanceof Player p && hasWeapon(p, "sculk_battle_axe")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWardenHurt(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Warden && e.getEntity() instanceof Player p && hasWeapon(p, "sculk_battle_axe")) e.setCancelled(true);
    }


    // ---- Maces (Divine Judgement / Hammer of the Void)
    void drainAllArmor(LivingEntity t, int amount) {
        EntityEquipment eq = t.getEquipment();
        if (eq == null) return;
        ItemStack[] arm = eq.getArmorContents();
        for (int i = 0; i < arm.length; i++) {
            ItemStack it = arm[i];
            if (it == null || it.getType().getMaxDurability() <= 0 || !(it.getItemMeta() instanceof Damageable dm)) continue;
            int nd = dm.getDamage() + amount;
            if (nd >= it.getType().getMaxDurability()) { arm[i] = null; t.getWorld().playSound(t.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f); }
            else { dm.setDamage(nd); it.setItemMeta(dm); }
        }
        eq.setArmorContents(arm);
    }

    void flyItem(Player p, LivingEntity t, ItemStack item, Runnable onHit) {
        Location start = p.getEyeLocation().add(p.getEyeLocation().getDirection());
        ItemDisplay d = p.getWorld().spawn(start, ItemDisplay.class, x -> x.setItemStack(item));
        new BukkitRunnable() {
            int n = 0;
            @Override public void run() {
                if (!t.isValid() || t.isDead() || !d.isValid() || n++ > 60) { d.remove(); cancel(); return; }
                Location target = t.getLocation().add(0, t.getHeight() / 2, 0);
                Vector v = target.toVector().subtract(d.getLocation().toVector());
                if (v.length() < 1.6) { d.remove(); cancel(); onHit.run(); return; }
                Location nl = d.getLocation().add(v.clone().normalize().multiply(1.8));
                nl.setDirection(v);
                d.teleport(nl);
                sp(d.getWorld(), Particle.END_ROD, nl, 3, 0.1, 0.1, 0.1, 0.02);
                dustAt(nl, 255, 215, 90, 1.5f, 3, 0.15);
            }
        }.runTaskTimer(this, 0, 1);
    }

    void smite(Player p) {
        LivingEntity t = lookTarget(p, 40);
        if (t == null) { msg(p, Component.text("No target in sight", NamedTextColor.GRAY)); return; }
        if (!cd(p, "smite", 30)) return;
        ItemStack it = p.getInventory().getItemInMainHand().clone();
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_TRIDENT_THROW, 1f, 0.6f);
        flyItem(p, t, it, () -> {
            if (!t.isValid() || t.isDead()) return;
            World w = t.getWorld(); Location g = t.getLocation();
            trueDamage(t, p, 6.0);
            w.strikeLightningEffect(g);
            sp(w, Particle.END_ROD, g.clone().add(0, 1, 0), 60, .5, .9, .5, 0.4);
            sp(w, Particle.TOTEM_OF_UNDYING, g.clone().add(0, 1, 0), 40, .5, .9, .5, 0.4);
            expandRing(g, 5, 8, l -> { dustAt(l, 255, 215, 90, 1.8f, 1, 0.05); sp(w, Particle.END_ROD, l, 1); });
            column(g, 10, l -> dustAt(l, 255, 230, 120, 1.6f, 2, 0.3));
            w.playSound(g, Sound.ITEM_TRIDENT_THUNDER, 1.2f, 1.2f);
            new BukkitRunnable() {
                int n = 0;
                @Override public void run() {
                    if (!t.isValid() || t.isDead() || !p.isOnline() || n++ > 15) { cancel(); return; }
                    Vector v = p.getLocation().toVector().subtract(t.getLocation().toVector());
                    if (v.length() < 2.5) { cancel(); return; }
                    t.setVelocity(v.normalize().multiply(1.1).setY(0.25));   // pull toward the wielder
                    beam(t.getLocation().add(0, 1, 0), p.getLocation().add(0, 1, 0), 0.7, l -> { sp(w, Particle.END_ROD, l, 1); dustAt(l, 255, 215, 90, 1.2f, 1, 0.05); });
                }
            }.runTaskTimer(this, 0, 1);
        });
    }

    void maceHit(EntityDamageByEntityEvent e, Player p, LivingEntity v, String id) {
        UUID u = p.getUniqueId();
        if (p.isOnGround()) { airHits.remove(u); return; }
        int c = airHits.getOrDefault(u, 0) + 1;
        if (id.equals("divine_judgement")) {
            if (c >= 5) {
                if (!cd(p, "final_verdict", 30, true)) { airHits.put(u, 5); return; }   // wait for cooldown, keep the charge
                Location l = v.getLocation();
                e.setDamage(e.getDamage() * 2);
                World fw = l.getWorld();
                sp(fw, Particle.EXPLOSION_EMITTER, l, 1);
                sp(fw, Particle.END_ROD, l.clone().add(0, 1, 0), 80, 1.5, 1, 1.5, 0.35);
                sp(fw, Particle.TOTEM_OF_UNDYING, l.clone().add(0, 1, 0), 60, 1.2, 1, 1.2, 0.5);
                expandRing(l, 6, 10, x -> { dustAt(x, 255, 215, 90, 2f, 1, 0.05); sp(fw, Particle.FLAME, x, 1); });
                column(l, 12, x -> dustAt(x, 255, 230, 120, 1.8f, 3, 0.4));
                fw.strikeLightningEffect(l);
                l.getWorld().playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 1f);
                for (Entity en : v.getNearbyEntities(3, 3, 3))
                    if (en instanceof LivingEntity le && en != p && !(en instanceof ArmorStand)) drainAllArmor(le, 60);
                drainAllArmor(v, 60);
                msg(p, Component.text("FINAL VERDICT", NamedTextColor.GOLD));
                airHits.remove(u);
                return;
            }
        } else if (c >= 6) {   // 5 hits, then the NEXT hit is the void strike
            e.setDamage(e.getDamage() * 2);
            v.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 400, 1));
            v.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 400, 1));
            World vw = v.getWorld(); Location vl = v.getLocation();
            sp(vw, Particle.REVERSE_PORTAL, vl.clone().add(0, 1, 0), 90, .5, .9, .5, 0.6);
            sp(vw, Particle.DRAGON_BREATH, vl.clone().add(0, 1, 0), 40, .5, .9, .5, 0.05);
            expandRing(vl, 5, 10, x -> { dustAt(x, 150, 60, 255, 1.9f, 1, 0.05); sp(vw, Particle.PORTAL, x, 2, .1, .3, .1, 0.4); });
            column(vl, 10, x -> dustAt(x, 190, 120, 255, 1.7f, 3, 0.35));
            vw.playSound(vl, Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f, 0.5f);
            vw.playSound(vl, Sound.ENTITY_WITHER_HURT, 0.8f, 0.6f);
            msg(p, Component.text("VOID STRIKE", NamedTextColor.DARK_PURPLE));
            airHits.remove(u);
            return;
        }
        airHits.put(u, c);
    }

    @EventHandler
    public void onGroundReset(PlayerMoveEvent e) {
        if (e.getPlayer().isOnGround()) airHits.remove(e.getPlayer().getUniqueId());
    }

    void voidThrow(Player p) {
        if (!cd(p, "void_throw", 20)) return;
        Thrown old = thrown.remove(p.getUniqueId());
        if (old != null && old.disp != null && old.disp.isValid()) old.disp.remove();
        ItemStack it = p.getInventory().getItemInMainHand().clone();
        Location eye = p.getEyeLocation(); Vector dir = eye.getDirection().normalize();
        ItemDisplay d = p.getWorld().spawn(eye.clone().add(dir), ItemDisplay.class, x -> x.setItemStack(it));
        Thrown th = new Thrown(); th.disp = d; thrown.put(p.getUniqueId(), th);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ENDER_PEARL_THROW, 1f, 0.6f);
        new BukkitRunnable() {
            final Location pos = eye.clone().add(dir);
            int n = 0;
            @Override public void run() {
                if (!d.isValid()) { cancel(); return; }
                if (n++ > 24) { th.land = pos.clone(); th.landed = true; msg(p, Component.text("Right-click to teleport to the mace", NamedTextColor.LIGHT_PURPLE)); cancel(); return; }
                RayTraceResult r = pos.getWorld().rayTrace(pos, dir, 1.7, FluidCollisionMode.NEVER, true, 0.4,
                        en -> en != p && en instanceof LivingEntity && !(en instanceof ArmorStand));
                if (r != null) {
                    double dist = Math.max(0, pos.toVector().distance(r.getHitPosition()) - 0.8);
                    th.land = pos.clone().add(dir.clone().multiply(dist)); th.landed = true;
                    d.teleport(th.land);
                    expandRing(th.land.clone().add(0, -1, 0), 3, 8, x -> dustAt(x, 150, 60, 255, 1.6f, 1, 0.05));
                    sp(d.getWorld(), Particle.PORTAL, th.land, 50, .4, .4, .4, 0.8);
                    msg(p, Component.text("Right-click to teleport to the mace", NamedTextColor.LIGHT_PURPLE));
                    cancel(); return;
                }
                pos.add(dir.clone().multiply(1.7));
                d.teleport(pos);
                sp(d.getWorld(), Particle.PORTAL, pos, 8, .15, .15, .15, .3);
                dustAt(pos, 150, 60, 255, 1.6f, 3, 0.15);
                sp(d.getWorld(), Particle.REVERSE_PORTAL, pos, 2, .1, .1, .1, 0.05);
            }
        }.runTaskTimer(this, 0, 1);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (thrown.get(p.getUniqueId()) == th) { thrown.remove(p.getUniqueId()); if (d.isValid()) d.remove(); }
        }, 300L);
    }

    @EventHandler
    public void onMaceClick(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        ItemStack it = p.getInventory().getItemInMainHand();
        String id = id(it);
        if (!"divine_judgement".equals(id) && !"hammer_of_the_void".equals(id)) return;
        if (isStunned(p)) return;
        if (p.isSneaking()) {                       // cycle Wind Burst I -> II -> III
            e.setCancelled(true);
            int cur = it.getEnchantmentLevel(Enchantment.WIND_BURST);
            int next = cur >= 3 ? 1 : cur + 1;
            it.addUnsafeEnchantment(Enchantment.WIND_BURST, next);
            p.getInventory().setItemInMainHand(it);
            msg(p, Component.text("Wind Burst " + next, NamedTextColor.AQUA));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 0.8f + next * 0.2f);
            return;
        }
        if (id.equals("hammer_of_the_void")) {
            Thrown th = thrown.get(p.getUniqueId());
            if (th == null) return;
            e.setCancelled(true);
            if (!th.landed) { msg(p, Component.text("The mace is still flying", NamedTextColor.GRAY)); return; }
            Location dest = null;
            for (double dy = -1.6; dy <= 0.01; dy += 0.2) {
                Location c = th.land.clone().add(0, dy, 0);
                if (!c.getBlock().getType().isSolid() && !c.clone().add(0, 1, 0).getBlock().getType().isSolid()) { dest = c; break; }
            }
            thrown.remove(p.getUniqueId());
            if (th.disp != null && th.disp.isValid()) th.disp.remove();
            if (dest == null) { msg(p, Component.text("No safe spot at the mace", NamedTextColor.RED)); return; }
            dest.setYaw(p.getLocation().getYaw()); dest.setPitch(p.getLocation().getPitch());
            sp(p.getWorld(), Particle.PORTAL, p.getLocation().add(0, 1, 0), 60, .4, .8, .4);
            Location from = p.getLocation().add(0, 1, 0), to = dest.clone().add(0, 1, 0);
            beam(from, to, 0.3, l -> { dustAt(l, 150, 60, 255, 1.4f, 1, 0.12); sp(p.getWorld(), Particle.REVERSE_PORTAL, l, 2, .15, .15, .15, 0.05); });
            expandRing(p.getLocation(), 3, 6, l -> dustAt(l, 150, 60, 255, 1.5f, 1, 0.05));
            p.teleport(dest);
            expandRing(dest, 3, 6, l -> dustAt(l, 190, 120, 255, 1.5f, 1, 0.05));
            sp(p.getWorld(), Particle.REVERSE_PORTAL, dest.clone().add(0, 1, 0), 80, .4, .8, .4, 0.4);
            p.getWorld().playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.8f);
        }
    }


    // ---- Temporal Reaver
    void unstun(LivingEntity t) {
        if (t instanceof Player pl) {
            stunned.remove(pl.getUniqueId());
            pl.removePotionEffect(PotionEffectType.SLOWNESS); pl.removePotionEffect(PotionEffectType.JUMP_BOOST);
        } else {
            if (t instanceof Mob m) m.setAware(true);
            t.removePotionEffect(PotionEffectType.SLOWNESS);
        }
    }

    void ring(Location c, double maxR, Particle particle) {
        new BukkitRunnable() {
            int n = 0;
            @Override public void run() {
                double r = maxR * (n + 1) / 20.0;
                for (int i = 0; i < 72; i++) {
                    double a = i * Math.PI * 2 / 72;
                    sp(c.getWorld(), particle, c.clone().add(Math.cos(a) * r, 0.3, Math.sin(a) * r), 1, 0, 0, 0, 0);
                }
                if (++n >= 20) cancel();
            }
        }.runTaskTimer(this, 0, 1);
    }

    void temporalCleave(Player p) {
        if (!cd(p, "temporal_cleave", 90)) return;
        int r = getConfig().getInt("temporal-cleave.radius", 25);
        int ticks = getConfig().getInt("temporal-cleave.duration-seconds", 10) * 20;
        Location c = p.getLocation();
        List<LivingEntity> frozen = new ArrayList<>();
        Map<Projectile, Vector> proj = new HashMap<>();
        for (Entity en : p.getNearbyEntities(r, r, r)) {
            if (en.getLocation().distanceSquared(c) > (double) r * r) continue;
            if (en instanceof Projectile pr) { proj.put(pr, pr.getVelocity()); pr.setGravity(false); pr.setVelocity(new Vector()); }
            else if (en instanceof LivingEntity le && !(en instanceof ArmorStand) && (en instanceof Player || en instanceof Mob)) { stun(le, ticks); frozen.add(le); }
        }
        p.getWorld().playSound(c, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.2f, 0.5f);
        ring(c, r, Particle.END_ROD);
        World cw = c.getWorld();
        expandRing(c, r, 20, l -> { dustAt(l, 50, 230, 100, 1.8f, 1, 0.05); dustAt(l, 255, 205, 60, 1.3f, 1, 0.05); });
        for (int i = 0; i < 12; i++) {      // clock marks around the holder
            double a = i * Math.PI / 6;
            Location mark = c.clone().add(Math.cos(a) * 6, 0, Math.sin(a) * 6);
            column(mark, i % 3 == 0 ? 4 : 2, l -> dustAt(l, 255, 205, 60, 1.4f, 1, 0.05));
        }
        sp(cw, Particle.END_ROD, c.clone().add(0, 1, 0), 120, 3, 2, 3, 0.2);
        msg(p, Component.text("TEMPORAL CLEAVE - time is shattered", NamedTextColor.GREEN));
        new BukkitRunnable() {
            int n = 0;
            @Override public void run() {
                for (Projectile pr : proj.keySet()) if (pr.isValid()) pr.setVelocity(new Vector());
                for (LivingEntity fe : frozen) if (fe.isValid()) {
                    dustAt(fe.getEyeLocation().add(0, 0.7, 0), 50, 230, 100, 1.1f, 1, 0.25);
                    if (n % 5 == 0) sp(fe.getWorld(), Particle.END_ROD, fe.getLocation().add(0, 1, 0), 3, .3, .6, .3, 0.01);
                }
                if (++n * 2 >= ticks) {
                    cancel();
                    for (LivingEntity le : frozen) if (le.isValid()) unstun(le);
                    for (Map.Entry<Projectile, Vector> en : proj.entrySet())
                        if (en.getKey().isValid()) { en.getKey().setGravity(true); en.getKey().setVelocity(en.getValue()); }
                    Location now = p.isOnline() ? p.getLocation() : c;
                    now.getWorld().playSound(now, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.6f);
                    ring(now, r, Particle.DRAGON_BREATH);
                    ring(now, r, Particle.END_ROD);
                }
            }
        }.runTaskTimer(this, 2, 2);
    }

    void temporalDismember(Player p) {
        int range = getConfig().getInt("temporal-dismemberment.range", 25);
        LivingEntity t = lookTarget(p, range);
        if (t == null) { msg(p, Component.text("No target in sight", NamedTextColor.GRAY)); return; }
        if (!cd(p, "temporal_dismemberment", 120)) return;
        double cloneDmg = getConfig().getDouble("temporal-dismemberment.clone-damage-hearts", 1.5) * 2;
        double eraseDmg = getConfig().getDouble("temporal-dismemberment.erasure-damage-hearts", 3.0) * 2;
        int markSec = getConfig().getInt("temporal-dismemberment.mark-seconds", 10);
        double bonus = getConfig().getDouble("temporal-dismemberment.mark-bonus", 0.25);

        // Phase 1 + 2: frozen for 10 seconds (blocks movement, teleports, pearls, wind charges)
        stun(t, 200);
        msg(p, Component.text("TEMPORAL DISMEMBERMENT - lock on", NamedTextColor.DARK_GREEN));
        new BukkitRunnable() {   // rotating sigil, phase 1 (5s)
            int n = 0;
            @Override public void run() {
                if (!t.isValid() || t.isDead() || n >= 50) { cancel(); return; }
                Location base = t.getLocation().add(0, 0.1 + n * 0.02, 0);
                double a = n * 0.3;
                for (int k = 0; k < 8; k++) {
                    double ang = a + k * Math.PI / 4;
                    for (double rr : new double[]{1.2 + n * 0.03, 2.2 + n * 0.03}) {
                        Location l = base.clone().add(Math.cos(ang) * rr, 0, Math.sin(ang) * rr);
                        sp(l.getWorld(), Particle.DRAGON_BREATH, l, 1, 0, 0, 0, 0);
                        dustAt(l, 50, 230, 100, 1.3f, 1, 0.03);
                        sp(l.getWorld(), Particle.PORTAL, l, 2, 0.05, 0.05, 0.05, 0.1);
                    }
                }
                if (n % 5 == 0) column(base, 3, l -> dustAt(l, 255, 205, 60, 1.2f, 1, 0.5));
                if (n % 10 == 0) t.getWorld().playSound(t.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 0.5f + n / 60f);
                n++;
            }
        }.runTaskTimer(this, 0, 2);

        // Phase 2: five fracture clones, 5 hits total
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            Bukkit.getScheduler().runTaskLater(this, () -> fractureClone(p, t, idx, cloneDmg), 100L + i * 20L);
        }
        // Phase 3: unfreeze, then the final strike after 2 seconds
        Bukkit.getScheduler().runTaskLater(this, () -> { if (t.isValid()) unstun(t); }, 200L);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!t.isValid() || t.isDead()) return;
            Location l = t.getLocation();
            l.getWorld().strikeLightningEffect(l);
            beam(l.clone().add(0, 25, 0), l, 0.4, x -> { dustAt(x, 255, 215, 80, 2f, 2, 0.2); dustAt(x, 50, 230, 100, 1.6f, 2, 0.3); sp(l.getWorld(), Particle.END_ROD, x, 1); });
            expandRing(l, 6, 10, x -> { dustAt(x, 255, 205, 60, 1.8f, 1, 0.05); sp(l.getWorld(), Particle.END_ROD, x, 1); });
            sp(l.getWorld(), Particle.END_ROD, l.clone().add(0, 1, 0), 80, .5, 1, .5, 0.2);
            l.getWorld().playSound(l, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.5f, 0.6f);
            trueDamage(t, p, eraseDmg);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Location at = t.getLocation();
                if (t.isDead() || !t.isValid()) {
                    Bukkit.broadcast(Component.text(t.getName() + " was erased from the timeline by " + p.getName(), NamedTextColor.GREEN));
                    World w = at.getWorld();
                    sp(w, Particle.EXPLOSION_EMITTER, at, 4, 1, 1, 1);
                    sp(w, Particle.DRAGON_BREATH, at.clone().add(0, 1, 0), 300, 2, 2, 2, 0.1);
                    sp(w, Particle.END_ROD, at.clone().add(0, 1, 0), 200, 2, 2, 2, 0.3);
                    w.playSound(at, Sound.ENTITY_ENDER_DRAGON_DEATH, 1f, 1.2f);
                } else {
                    marks.put(t.getUniqueId(), new Mark(p.getUniqueId(), System.currentTimeMillis() + markSec * 1000L, bonus));
                    int mt = markSec * 20;
                    for (PotionEffectType pt : new PotionEffectType[]{PotionEffectType.GLOWING, PotionEffectType.WEAKNESS,
                            PotionEffectType.SLOWNESS, PotionEffectType.DARKNESS, PotionEffectType.MINING_FATIGUE,
                            PotionEffectType.WITHER, PotionEffectType.HUNGER})
                        t.addPotionEffect(new PotionEffect(pt, mt, 0));
                    msg(p, Component.text("Target MARKED (+" + (int) (bonus * 100) + "% damage)", NamedTextColor.GREEN));
                }
            }, 2L);
        }, 240L);
    }

    void fractureClone(Player p, LivingEntity t, int idx, double dmg) {
        if (!t.isValid() || t.isDead() || !p.isOnline()) return;
        double ang = idx * (Math.PI * 2 / 5) + Math.random();
        Location loc = t.getLocation().add(Math.cos(ang) * 2.5, 0, Math.sin(ang) * 2.5);
        loc.setDirection(t.getLocation().toVector().subtract(loc.toVector()));
        ArmorStand as = t.getWorld().spawn(loc, ArmorStand.class, a -> {
            a.setArms(true); a.setBasePlate(false); a.setInvulnerable(true); a.setGravity(false); a.setSilent(true);
            a.getEquipment().setArmorContents(p.getInventory().getArmorContents());
            a.getEquipment().setItemInMainHand(p.getInventory().getItemInMainHand());
        });
        sp(loc.getWorld(), Particle.PORTAL, loc.clone().add(0, 1, 0), 40, .3, .8, .3, .3);
        Bukkit.getScheduler().runTaskLater(this, () -> {      // slash 1 deals the damage
            if (!t.isValid() || t.isDead()) return;
            sp(t.getWorld(), Particle.SWEEP_ATTACK, t.getLocation().add(0, 1, 0), 3, .4, .4, .4, 0);
            t.getWorld().playSound(t.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.8f);
            trueDamage(t, p, dmg);
        }, 3L);
        Bukkit.getScheduler().runTaskLater(this, () -> {      // slash 2 is the afterimage
            if (!t.isValid()) return;
            sp(t.getWorld(), Particle.SWEEP_ATTACK, t.getLocation().add(0, 1.2, 0), 3, .4, .4, .4, 0);
            sp(t.getWorld(), Particle.END_ROD, loc.clone().add(0, 1, 0), 20, .3, .8, .3, 0.05);
        }, 9L);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            sp(loc.getWorld(), Particle.PORTAL, loc.clone().add(0, 1, 0), 30, .3, .8, .3, .3);
            as.remove();
        }, 16L);
    }

    @EventHandler
    public void onFrozenTeleport(PlayerTeleportEvent e) {
        if (isStunned(e.getPlayer()) && (e.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                || e.getCause() == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT)) e.setCancelled(true);
    }

    // ---- Primal Bow
    void liveForTheHunt(Player p) {
        if (huntReady.contains(p.getUniqueId())) { msg(p, Component.text("Your next arrow is already marked", NamedTextColor.GRAY)); return; }
        if (!cd(p, "live_for_the_hunt", 600)) return;
        huntReady.add(p.getUniqueId());
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WOLF_HOWL, 1f, 0.8f);
        expandRing(p.getLocation(), 6, 10, l -> { dustAt(l, 80, 225, 225, 1.7f, 1, 0.05); sp(p.getWorld(), Particle.HAPPY_VILLAGER, l, 1); });
        auraTask(p, 80, l -> { dustAt(l, 80, 225, 225, 1.3f, 1, 0.05); sp(p.getWorld(), Particle.END_ROD, l, 1, 0, 0, 0, 0.01); });
        msg(p, Component.text("LIVE FOR THE HUNT - next arrow is marked", NamedTextColor.AQUA));
    }

    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p) || !"primal_bow".equals(id(e.getBow()))) return;
        if (e.getProjectile() instanceof AbstractArrow a) {
            a.getPersistentDataContainer().set(PRIMAL_KEY, PersistentDataType.BYTE, (byte) 1);
            boolean hunt = huntReady.remove(p.getUniqueId());
            if (hunt) a.getPersistentDataContainer().set(HUNT_KEY, PersistentDataType.BYTE, (byte) 1);
            World aw = a.getWorld();
            trail(a, l -> {
                dustAt(l, 80, 225, 225, hunt ? 1.5f : 0.9f, hunt ? 3 : 1, hunt ? 0.1 : 0.02);
                if (hunt) sp(aw, Particle.END_ROD, l, 1, .05, .05, .05, 0.01);
            });
        }
        int max = getConfig().getInt("hunters-momentum-max", 30);
        int m = Math.min(max, momentum.getOrDefault(p.getUniqueId(), 0) + 1);
        momentum.put(p.getUniqueId(), m);
        msg(p, Component.text("Hunter's Momentum +" + m, NamedTextColor.AQUA));
        dustAt(p.getEyeLocation(), 80, 225, 225, 1.2f, 6 + m / 2, 0.5);
        sp(p.getWorld(), Particle.HAPPY_VILLAGER, p.getLocation().add(0, 1, 0), 3 + m / 4, .5, .6, .5);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunterHurt(EntityDamageByEntityEvent e) {
        if (internal || !(e.getEntity() instanceof Player v)) return;
        if (momentum.remove(v.getUniqueId()) != null) msg(v, Component.text("Hunter's Momentum lost", NamedTextColor.RED));
    }

    // ---- Mad Scientist's Crossbow
    @EventHandler(ignoreCancelled = true)
    public void onCrossbowLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof Arrow a) || !(a.getShooter() instanceof Player p)) return;
        if (!"mad_scientists_crossbow".equals(id(p.getInventory().getItemInMainHand()))
                && !"mad_scientists_crossbow".equals(id(p.getInventory().getItemInOffHand()))) return;
        PotionEffectType[] pool = {PotionEffectType.POISON, PotionEffectType.SLOWNESS, PotionEffectType.WEAKNESS, PotionEffectType.WITHER,
                PotionEffectType.BLINDNESS, PotionEffectType.INSTANT_DAMAGE, PotionEffectType.HUNGER, PotionEffectType.NAUSEA,
                PotionEffectType.LEVITATION, PotionEffectType.GLOWING, PotionEffectType.SPEED, PotionEffectType.STRENGTH,
                PotionEffectType.REGENERATION, PotionEffectType.INSTANT_HEALTH, PotionEffectType.RESISTANCE, PotionEffectType.JUMP_BOOST};
        PotionEffectType t = pool[new Random().nextInt(pool.length)];
        a.addCustomEffect(new PotionEffect(t, 200, 1), true);
        a.setColor(t.getColor());
        a.getPersistentDataContainer().set(CROSS_KEY, PersistentDataType.BYTE, (byte) 1);
        Color arrowColor = t.getColor(); World aw = a.getWorld();
        trail(a, l -> {
            sp(aw, Particle.DUST, l, 3, .06, .06, .06, 0, new Particle.DustOptions(arrowColor, 1.3f));
            sp(aw, Particle.BUBBLE_POP, l, 1, .1, .1, .1, 0);
        });
    }

    void grapplePull(LivingEntity v, Player p, double blocks) {
        if (!v.isValid() || !p.isOnline() || !p.getWorld().equals(v.getWorld())) return;
        Vector dir = p.getLocation().toVector().subtract(v.getLocation().toVector());
        double dist = dir.length();
        if (dist < 2.5) return;
        dir.normalize();
        Location dest = null;
        for (double d = Math.min(blocks, dist - 1.5); d >= 0.5; d -= 0.5) {
            Location c = v.getLocation().add(dir.clone().multiply(d));
            if (!c.getBlock().getType().isSolid() && !c.clone().add(0, 1, 0).getBlock().getType().isSolid()) { dest = c; break; }
        }
        if (dest == null) return;
        World gw = v.getWorld();
        beam(p.getEyeLocation().add(0, -0.2, 0), v.getLocation().add(0, 1, 0), 0.3, l -> { dustAt(l, 120, 255, 30, 1.5f, 1, 0.05); sp(gw, Particle.END_ROD, l, 1); });
        sp(gw, Particle.CLOUD, v.getLocation().add(0, 1, 0), 20, .3, .5, .3, 0.05);
        v.teleport(dest);
        sp(gw, Particle.CLOUD, dest.clone().add(0, 1, 0), 15, .3, .5, .3, 0.05);
        gw.playSound(dest, Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1f, 0.7f);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArrowHit(EntityDamageByEntityEvent e) {
        if (internal || !(e.getDamager() instanceof AbstractArrow a) || !(a.getShooter() instanceof Player p)
                || !(e.getEntity() instanceof LivingEntity v)) return;
        PersistentDataContainer pdc = a.getPersistentDataContainer();
        Location hl = v.getLocation().add(0, v.getHeight() / 2, 0); World hw = v.getWorld();
        if (pdc.has(PRIMAL_KEY, PersistentDataType.BYTE)) { dustAt(hl, 80, 225, 225, 1.4f, 14, 0.4); sp(hw, Particle.HAPPY_VILLAGER, hl, 8, .4, .5, .4); }
        if (pdc.has(HUNT_KEY, PersistentDataType.BYTE)) expandRing(v.getLocation(), 3, 8, l -> dustAt(l, 80, 225, 225, 1.5f, 1, 0.05));
        if (pdc.has(CROSS_KEY, PersistentDataType.BYTE)) { dustAt(hl, 120, 255, 30, 1.4f, 16, 0.4); sp(hw, Particle.BUBBLE_POP, hl, 10, .4, .5, .4); }
        if (pdc.has(HUNT_KEY, PersistentDataType.BYTE)) {
            v.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 6000, 0));
            v.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 1200, 1));
            v.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 1200, 1));
        }
        if (pdc.has(PRIMAL_KEY, PersistentDataType.BYTE)) {
            int m = momentum.getOrDefault(p.getUniqueId(), 0);
            if (m > 0) e.setDamage(e.getDamage() + m);          // +1 damage per stack
        }
        if (pdc.has(CROSS_KEY, PersistentDataType.BYTE))
            Bukkit.getScheduler().runTask(this, () -> grapplePull(v, p, 5.0));
    }

    // ---- Royal Spear
    void royalJudgement(Player p) {
        LivingEntity t = lookTarget(p, 40);
        if (t == null) { msg(p, Component.text("No target in sight", NamedTextColor.GRAY)); return; }
        if (!cd(p, "royal_judgement", 60)) return;
        ItemStack spear = p.getInventory().getItemInMainHand().clone();
        Location start = p.getEyeLocation().add(p.getEyeLocation().getDirection());
        ItemDisplay d = p.getWorld().spawn(start, ItemDisplay.class, x -> x.setItemStack(spear));
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_TRIDENT_THROW, 1f, 0.8f);
        new BukkitRunnable() {
            int n = 0;
            @Override public void run() {
                if (!t.isValid() || t.isDead() || !d.isValid() || n++ > 60) { d.remove(); cancel(); return; }
                Location target = t.getLocation().add(0, t.getHeight() / 2, 0);
                Vector v = target.toVector().subtract(d.getLocation().toVector());
                if (v.length() < 1.6) {
                    d.remove(); cancel();
                    trueDamage(t, p, 6.0);
                    t.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 400, 1));
                    t.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 400, 1));
                    t.getWorld().playSound(t.getLocation(), Sound.ITEM_TRIDENT_HIT, 1f, 0.8f);
                    World iw = t.getWorld();
                    sp(iw, Particle.CRIT, target, 40, .4, .5, .4, 0.4);
                    sp(iw, Particle.TOTEM_OF_UNDYING, target, 30, .4, .5, .4, 0.4);
                    sp(iw, Particle.SMOKE, target, 20, .4, .5, .4, 0.05);
                    expandRing(t.getLocation(), 4, 8, x -> { dustAt(x, 255, 205, 60, 1.7f, 1, 0.05); dustAt(x, 130, 40, 150, 1.4f, 1, 0.05); });
                    column(t.getLocation(), 6, x -> dustAt(x, 255, 215, 80, 1.5f, 2, 0.3));
                    return;
                }
                Location nl = d.getLocation().add(v.clone().normalize().multiply(1.8));
                nl.setDirection(v);
                d.teleport(nl);
                sp(d.getWorld(), Particle.END_ROD, nl, 3, 0.08, 0.08, 0.08, 0.02);
                dustAt(nl, 255, 205, 60, 1.5f, 3, 0.12);
            }
        }.runTaskTimer(this, 0, 1);
    }

    void piercingStrike(Player p) {
        if (!cd(p, "piercing_strike", 20)) return;
        Location eye = p.getEyeLocation(); Vector dir = eye.getDirection().normalize();
        Location end = eye.clone().add(dir.clone().multiply(30));
        World bw = p.getWorld(); Location start = eye.clone().add(0, -0.2, 0);
        beam(start, end, 0.25, l -> { sp(bw, Particle.END_ROD, l, 1, 0.02, 0.02, 0.02, 0); dustAt(l, 255, 205, 60, 1.6f, 1, 0.04); });
        helix(start, end, 0.7, 0.2, l -> dustAt(l, 140, 50, 190, 1.3f, 1, 0.02));
        sp(bw, Particle.END_ROD, start, 20, .3, .3, .3, 0.2);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 0.8f, 1.6f);
        Location mid = eye.clone().add(dir.clone().multiply(15));
        for (Entity en : p.getWorld().getNearbyEntities(mid, 16, 16, 16)) {
            if (en == p || !(en instanceof LivingEntity le) || en instanceof ArmorStand) continue;
            BoundingBox box = en.getBoundingBox().expand(0.3);
            RayTraceResult r = box.rayTrace(eye.toVector(), dir, 30);
            if (r != null) {                                  // goes through blocks: no block check
                trueDamage(le, p, 8.0);
                Location hl = le.getLocation().add(0, le.getHeight() / 2, 0);
                sp(bw, Particle.END_ROD, hl, 40, .4, .5, .4, 0.4);
                sp(bw, Particle.CRIT, hl, 30, .4, .5, .4, 0.5);
                expandRing(le.getLocation(), 3, 6, x -> dustAt(x, 255, 205, 60, 1.6f, 1, 0.05));
            }
        }
    }

    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player p = e.getPlayer();
        if (!"royal_spear".equals(id(p.getInventory().getItemInMainHand())) || lungeEnchant() == null) return;
        long now = System.currentTimeMillis();
        Long until = lungeCd.get(p.getUniqueId());
        if (until != null && until > now) return;
        lungeCd.put(p.getUniqueId(), now + 5000);
        Bukkit.getScheduler().runTask(this, () -> setLunge(p, false));   // strip Lunge right after this one is used
        Bukkit.getScheduler().runTaskLater(this, () -> setLunge(p, true), 100L);
    }

    @EventHandler
    public void onJoinLunge(PlayerJoinEvent e) { setLunge(e.getPlayer(), true); }


    // ------------------------------------------------------------------ visual effects + HUD
    void sp(World w, Particle p, Location l, int n) { sp(w, p, l, n, 0, 0, 0, 0); }
    void sp(World w, Particle p, Location l, int n, double dx, double dy, double dz) { sp(w, p, l, n, dx, dy, dz, 0); }
    void sp(World w, Particle p, Location l, int n, double dx, double dy, double dz, double speed) {
        try { w.spawnParticle(p, l, n, dx, dy, dz, speed); } catch (Exception ignored) { }
    }
    <T> void sp(World w, Particle p, Location l, int n, double dx, double dy, double dz, double speed, T data) {
        try { w.spawnParticle(p, l, n, dx, dy, dz, speed, data); } catch (Exception ignored) { }
    }

    void dustAt(Location l, int r, int g, int b, float size, int n, double spread) {
        sp(l.getWorld(), Particle.DUST, l, n, spread, spread, spread, 0, new Particle.DustOptions(Color.fromRGB(r, g, b), size));
    }

    void expandRing(Location c0, double maxR, int steps, Consumer<Location> plot) {
        Location c = c0.clone();
        new BukkitRunnable() {
            int n = 0;
            @Override public void run() {
                double r = maxR * (n + 1) / steps;
                int pts = (int) Math.max(16, r * 8);
                for (int i = 0; i < pts; i++) {
                    double a = i * Math.PI * 2 / pts;
                    plot.accept(c.clone().add(Math.cos(a) * r, 0.2, Math.sin(a) * r));
                }
                if (++n >= steps) cancel();
            }
        }.runTaskTimer(this, 0, 1);
    }

    void auraTask(Player p, int ticks, Consumer<Location> plot) {
        new BukkitRunnable() {
            int n = 0;
            @Override public void run() {
                if (!p.isOnline() || n >= ticks) { cancel(); return; }
                Location b = p.getLocation();
                for (int k = 0; k < 2; k++) {
                    double a = n * 0.45 + k * Math.PI, h = (n * 0.12 + k) % 2.0;
                    plot.accept(b.clone().add(Math.cos(a) * 0.9, h, Math.sin(a) * 0.9));
                }
                n += 2;
            }
        }.runTaskTimer(this, 0, 2);
    }

    void beam(Location a, Location b, double step, Consumer<Location> plot) {
        Vector d = b.toVector().subtract(a.toVector());
        double len = d.length(); if (len < 0.01) return;
        d.normalize();
        for (double x = 0; x < len; x += step) plot.accept(a.clone().add(d.clone().multiply(x)));
    }

    void helix(Location a, Location b, double radius, double step, Consumer<Location> plot) {
        Vector d = b.toVector().subtract(a.toVector());
        double len = d.length(); if (len < 0.01) return;
        d.normalize();
        Vector u = d.clone().crossProduct(new Vector(0, 1, 0));
        if (u.lengthSquared() < 1e-4) u = new Vector(1, 0, 0);
        u.normalize();
        Vector v = d.clone().crossProduct(u).normalize();
        for (double x = 0; x < len; x += step) {
            double ang = x * 2.2;
            plot.accept(a.clone().add(d.clone().multiply(x)).add(u.clone().multiply(Math.cos(ang) * radius)).add(v.clone().multiply(Math.sin(ang) * radius)));
        }
    }

    void trail(Entity e, Consumer<Location> plot) {
        new BukkitRunnable() {
            int n = 0;
            @Override public void run() {
                if (!e.isValid() || n++ > 400 || (e instanceof AbstractArrow a && a.isInBlock())) { cancel(); return; }
                plot.accept(e.getLocation());
            }
        }.runTaskTimer(this, 0, 1);
    }

    void column(Location base, double height, Consumer<Location> plot) {
        for (double y = 0; y < height; y += 0.4) plot.accept(base.clone().add(0, y, 0));
    }

    void hitFx(LivingEntity v, String id) {
        World w = v.getWorld(); Location l = v.getLocation().add(0, v.getHeight() / 2, 0);
        switch (id) {
            case "bloody_eclipse" -> { dustAt(l, 190, 10, 20, 1.4f, 16, 0.4); sp(w, Particle.DAMAGE_INDICATOR, l, 5, .3, .4, .3); }
            case "stormcaller" -> sp(w, Particle.ELECTRIC_SPARK, l, 28, .4, .5, .4, 0.4);
            case "dwarven_pickaxe" -> { sp(w, Particle.BLOCK, l, 18, .3, .4, .3, 0, Material.STONE.createBlockData()); sp(w, Particle.CRIT, l, 8, .3, .4, .3, 0.2); }
            case "ten_ton_axe" -> { sp(w, Particle.EXPLOSION, l, 1); sp(w, Particle.CRIT, l, 22, .4, .5, .4, 0.4); sp(w, Particle.CLOUD, l, 6, .3, .3, .3, 0.05); }
            case "void_blade" -> { sp(w, Particle.PORTAL, l, 30, .4, .5, .4, 0.9); dustAt(l, 150, 60, 255, 1.4f, 10, 0.4); }
            case "inferno" -> { sp(w, Particle.FLAME, l, 22, .3, .5, .3, 0.1); sp(w, Particle.LAVA, l, 4, .3, .4, .3); }
            case "sculk_battle_axe" -> { sp(w, Particle.SCULK_SOUL, l, 7, .3, .5, .3, 0.04); dustAt(l, 30, 200, 230, 1.3f, 12, 0.4); }
            case "royal_spear" -> { sp(w, Particle.END_ROD, l, 14, .3, .5, .3, 0.12); dustAt(l, 255, 205, 60, 1.3f, 14, 0.4); }
            case "divine_judgement" -> { sp(w, Particle.END_ROD, l, 16, .3, .5, .3, 0.15); dustAt(l, 255, 220, 100, 1.5f, 12, 0.4); sp(w, Particle.TOTEM_OF_UNDYING, l, 6, .3, .5, .3, 0.2); }
            case "hammer_of_the_void" -> { sp(w, Particle.REVERSE_PORTAL, l, 30, .4, .5, .4, 0.2); dustAt(l, 140, 50, 255, 1.5f, 10, 0.4); }
            case "temporal_reaver" -> { dustAt(l, 50, 230, 100, 1.4f, 16, 0.4); sp(w, Particle.END_ROD, l, 8, .3, .5, .3, 0.1); dustAt(l, 255, 205, 60, 1.0f, 8, 0.4); }
            default -> { }
        }
    }

    void auraFx(Player p, String id) {
        World w = p.getWorld(); Location b = p.getLocation(), h = p.getLocation().add(0, 1, 0);
        switch (id) {
            case "bloody_eclipse" -> dustAt(h, 170, 5, 15, 1.0f, 2, 0.6);
            case "stormcaller" -> sp(w, Particle.ELECTRIC_SPARK, h, 2, .5, .7, .5, 0.1);
            case "dwarven_pickaxe" -> sp(w, Particle.ENCHANT, h, 3, .5, .6, .5, 0.2);
            case "ten_ton_axe" -> sp(w, Particle.SMOKE, b.clone().add(0, 0.2, 0), 2, .5, .1, .5, 0.01);
            case "void_blade" -> { sp(w, Particle.PORTAL, h, 3, .5, .7, .5, 0.5); dustAt(h, 150, 60, 255, 0.9f, 1, 0.5); }
            case "inferno" -> { sp(w, Particle.FLAME, b.clone().add(0, 0.1, 0), 3, .4, .05, .4, 0.03); sp(w, Particle.LAVA, h, 1, .4, .4, .4); }
            case "sculk_battle_axe" -> { sp(w, Particle.SCULK_SOUL, h, 1, .5, .6, .5, 0.02); dustAt(h, 30, 200, 230, 0.9f, 1, 0.5); }
            case "royal_spear", "divine_judgement" -> { sp(w, Particle.END_ROD, h, 1, .5, .7, .5, 0.01); dustAt(h, 255, 205, 60, 0.9f, 1, 0.5); }
            case "hammer_of_the_void" -> { sp(w, Particle.PORTAL, h, 2, .5, .7, .5, 0.4); dustAt(h, 140, 50, 255, 0.9f, 1, 0.5); }
            case "temporal_reaver" -> { dustAt(h, 50, 230, 100, 0.9f, 2, 0.6); dustAt(h, 255, 205, 60, 0.7f, 1, 0.6); }
            case "primal_bow" -> dustAt(h, 80, 225, 225, 0.9f, 2, 0.6);
            case "mad_scientists_crossbow" -> { dustAt(h, 120, 255, 30, 0.9f, 2, 0.6); sp(w, Particle.BUBBLE_POP, h, 1, .4, .6, .4, 0); }
            default -> { }
        }
    }

    void msg(Player p, Component c) {
        holdUntil.put(p.getUniqueId(), System.currentTimeMillis() + 2500);
        p.sendActionBar(c);
    }

    long remaining(Player p, String key) {
        Long u = cooldowns.get(p.getUniqueId() + key);
        long now = System.currentTimeMillis();
        return (u == null || u <= now) ? 0 : (u - now + 999) / 1000;
    }

    String[][] abilitiesOf(String id) {
        return switch (id) {
            case "bloody_eclipse" -> new String[][]{{"F Bloodbath", "bloodbath"}, {"Shift+F Eclipse", "blinding_eclipse"}};
            case "stormcaller" -> new String[][]{{"F Storm", "lightning_storm"}, {"Shift+F Tides", "tides_call"}};
            case "dwarven_pickaxe" -> new String[][]{{"F Call of the Deep", "call_of_the_deep"}};
            case "ten_ton_axe" -> new String[][]{{"F Stun", "stunning_strike"}, {"Shift+F Drain", "durability_drain"}};
            case "void_blade" -> new String[][]{{"F Void Walk", "void_walk"}, {"Shift+F Pull", "pull_of_the_void"}};
            case "inferno" -> new String[][]{{"F Acidic Blaze", "acidic_blaze"}, {"Shift+F Incinerate", "incineration"}};
            case "sculk_battle_axe" -> new String[][]{{"F Beams", "sculk_beams"}, {"Shift+F Warden", "summon_warden"}};
            case "royal_spear" -> new String[][]{{"F Judgement", "royal_judgement"}, {"Shift+F Pierce", "piercing_strike"}};
            case "divine_judgement" -> new String[][]{{"F Smite", "smite"}, {"Verdict", "final_verdict"}};
            case "hammer_of_the_void" -> new String[][]{{"F Throw", "void_throw"}};
            case "temporal_reaver" -> new String[][]{{"F Cleave", "temporal_cleave"}, {"Shift+F Dismember", "temporal_dismemberment"}};
            case "primal_bow" -> new String[][]{{"F Hunt", "live_for_the_hunt"}};
            default -> new String[0][];
        };
    }

    void hud(Player p, String id) {
        if (!getConfig().getBoolean("cooldown-hud", true)) return;
        Long hold = holdUntil.get(p.getUniqueId());
        if (hold != null && hold > System.currentTimeMillis()) return;
        String[][] abs = abilitiesOf(id);
        if (abs.length == 0) return;
        Component c = Component.empty();
        for (int i = 0; i < abs.length; i++) {
            if (i > 0) c = c.append(Component.text("  |  ", NamedTextColor.DARK_GRAY));
            long rem = remaining(p, abs[i][1]);
            c = c.append(rem > 0 ? Component.text(abs[i][0] + " " + rem + "s", NamedTextColor.RED)
                                 : Component.text(abs[i][0] + " READY", NamedTextColor.GREEN));
        }
        long now = System.currentTimeMillis();
        Long bb = bloodbath.get(p.getUniqueId());
        if (id.equals("bloody_eclipse") && bb != null && bb > now) c = c.append(Component.text("   BLOODBATH " + ((bb - now) / 1000 + 1) + "s", NamedTextColor.GOLD));
        if (id.equals("primal_bow")) {
            if (huntReady.contains(p.getUniqueId())) c = c.append(Component.text("   ARMED", NamedTextColor.AQUA));
            c = c.append(Component.text("   Momentum x" + momentum.getOrDefault(p.getUniqueId(), 0), NamedTextColor.DARK_AQUA));
        }
        if (id.equals("hammer_of_the_void")) {
            Thrown th = thrown.get(p.getUniqueId());
            if (th != null && th.landed) c = c.append(Component.text("   RIGHT-CLICK to teleport", NamedTextColor.LIGHT_PURPLE));
        }
        if (id.equals("divine_judgement") || id.equals("hammer_of_the_void"))
            c = c.append(Component.text("   Air hits " + airHits.getOrDefault(p.getUniqueId(), 0), NamedTextColor.GRAY));
        p.sendActionBar(c);
    }

    // ------------------------------------------------------------------ passives
    void passives(Player p) {
        ItemStack helm = p.getInventory().getHelmet();
        if ("kings_crown".equals(id(helm))) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 30, 2, true, false, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 30, 0, true, false, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.HEALTH_BOOST, 30, 4, true, false, true));   // +20 HP = 10 hearts
        }
        String id = id(p.getInventory().getItemInMainHand());
        if (id == null) return;
        switch (id) {
            case "bloody_eclipse" -> { effect(p, PotionEffectType.SPEED, 1); effect(p, PotionEffectType.STRENGTH, 0); }
            case "stormcaller" -> { effect(p, PotionEffectType.WATER_BREATHING, 0); effect(p, PotionEffectType.DOLPHINS_GRACE, 0);
                effect(p, PotionEffectType.CONDUIT_POWER, 0); }
            case "void_blade" -> { effect(p, PotionEffectType.SPEED, 2); effect(p, PotionEffectType.STRENGTH, 0);
                effect(p, PotionEffectType.FIRE_RESISTANCE, 0); }
            case "inferno" -> effect(p, PotionEffectType.FIRE_RESISTANCE, 0);
            case "sculk_battle_axe" -> { effect(p, PotionEffectType.STRENGTH, 0); effect(p, PotionEffectType.SPEED, 1);
                effect(p, PotionEffectType.RESISTANCE, 0); }
            case "royal_spear" -> { effect(p, PotionEffectType.SPEED, 1);
                Long u = lungeCd.get(p.getUniqueId());
                if (u == null || u < System.currentTimeMillis()) setLunge(p, true); }
            default -> {}
        }
    }

    // ------------------------------------------------------------------ ability key (F / Shift+F)
    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        String id = id(p.getInventory().getItemInMainHand());
        if (id == null || id.equals("kings_crown") || id.equals("mad_scientists_crossbow")) return;
        e.setCancelled(true);
        if (isStunned(p)) return;
        boolean sh = p.isSneaking();
        switch (id) {
            case "bloody_eclipse" -> { if (sh) blindingEclipse(p); else bloodbath(p); }
            case "stormcaller" -> { if (sh) tidesCall(p); else lightningStorm(p); }
            case "dwarven_pickaxe" -> { if (!sh) callOfTheDeep(p); }
            case "ten_ton_axe" -> { if (sh) durabilityDrain(p); else stunningStrike(p); }
            case "void_blade" -> { if (sh) pullOfTheVoid(p); else voidWalk(p); }
            case "inferno" -> { if (sh) incinerate(p); else acidicBlaze(p); }
            case "sculk_battle_axe" -> { if (sh) summonWarden(p); else sculkBeams(p); }
            case "royal_spear" -> { if (sh) piercingStrike(p); else royalJudgement(p); }
            case "temporal_reaver" -> { if (sh) temporalDismember(p); else temporalCleave(p); }
            case "primal_bow" -> { if (!sh) liveForTheHunt(p); }
            case "divine_judgement" -> { if (!sh) smite(p); }
            case "hammer_of_the_void" -> { if (!sh) voidThrow(p); }
            default -> {}
        }
    }

    // ---- Bloody Eclipse
    void bloodbath(Player p) {
        if (!cd(p, "bloodbath", 45)) return;
        bloodbath.put(p.getUniqueId(), System.currentTimeMillis() + 10_000);
        World w = p.getWorld(); Location c = p.getLocation();
        w.playSound(c, Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.6f);
        w.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 0.6f);
        expandRing(c, 7, 12, l -> { dustAt(l, 200, 10, 20, 1.7f, 1, 0.05); sp(w, Particle.DAMAGE_INDICATOR, l, 1); });
        column(c, 4, l -> dustAt(l, 150, 0, 10, 1.5f, 3, 0.4));
        auraTask(p, 200, l -> { dustAt(l, 210, 15, 25, 1.3f, 1, 0.05); sp(w, Particle.DAMAGE_INDICATOR, l, 1); });
        msg(p, Component.text("BLOODBATH! Every hit crits for 10s", NamedTextColor.DARK_RED));
    }

    void blindingEclipse(Player p) {
        if (!cd(p, "blinding_eclipse", 60)) return;
        long end = System.currentTimeMillis() + 10_000;
        World w = p.getWorld(); Location c = p.getLocation();
        for (Entity en : p.getNearbyEntities(20, 20, 20)) {
            if (!(en instanceof LivingEntity le) || en instanceof ArmorStand) continue;
            if (en.getLocation().distanceSquared(c) > 400) continue;
            le.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 200, 0));
            sp(w, Particle.SQUID_INK, le.getEyeLocation(), 16, .3, .3, .3, 0.05);
            if (en instanceof Player v) eclipsed.put(v.getUniqueId(), end);
        }
        w.playSound(c, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f, 0.6f);
        expandRing(c, 20, 20, l -> { dustAt(l, 20, 0, 40, 2.2f, 1, 0.1); sp(w, Particle.SMOKE, l, 1, .1, .6, .1, 0.02); });
        expandRing(c.clone().add(0, 3, 0), 20, 20, l -> sp(w, Particle.SQUID_INK, l, 1));
        new BukkitRunnable() {          // black sun with a fiery corona above the holder
            int n = 0;
            @Override public void run() {
                if (!p.isOnline() || n++ > 30) { cancel(); return; }
                Location top = p.getLocation().add(0, 7, 0);
                for (int i = 0; i < 28; i++) {
                    double a = i * Math.PI * 2 / 28 + n * 0.1;
                    dustAt(top.clone().add(Math.cos(a) * 2.6, Math.sin(a) * 2.6 * 0.3, Math.sin(a) * 2.6), 255, 90, 20, 1.2f, 1, 0.02);
                }
                for (int i = 0; i < 10; i++) dustAt(top.clone().add((Math.random() - .5) * 4, (Math.random() - .5) * 1.2, (Math.random() - .5) * 4), 5, 0, 10, 2f, 1, 0.1);
            }
        }.runTaskTimer(this, 0, 2);
        msg(p, Component.text("BLINDING ECLIPSE", NamedTextColor.DARK_PURPLE));
    }

    boolean isEclipsed(Player p) {
        Long e = eclipsed.get(p.getUniqueId());
        if (e == null) return false;
        if (e < System.currentTimeMillis()) { eclipsed.remove(p.getUniqueId()); return false; }
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onWindCharge(ProjectileLaunchEvent e) {
        if (e.getEntity() instanceof WindCharge w && w.getShooter() instanceof Player p && isEclipsed(p)) {
            e.setCancelled(true);
            msg(p, Component.text("Wind charges are disabled by the eclipse!", NamedTextColor.RED));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCobweb(BlockPlaceEvent e) {
        if (e.getBlock().getType() == Material.COBWEB && isEclipsed(e.getPlayer())) {
            e.setCancelled(true);
            msg(e.getPlayer(), Component.text("Cobwebs are disabled by the eclipse!", NamedTextColor.RED));
        }
    }

    void bleed(LivingEntity v, Player src) {
        new BukkitRunnable() {
            int n = 0;
            @Override public void run() {
                if (!v.isValid() || v.isDead() || n++ >= 5) { cancel(); return; }
                trueDamage(v, null, 1.0);
                sp(v.getWorld(), Particle.DAMAGE_INDICATOR, v.getLocation().add(0, 1, 0), 6, .3, .4, .3);
            }
        }.runTaskTimer(this, 20, 20);
        if (src != null) msg(src, Component.text("Bleed applied", NamedTextColor.RED));
    }

    // ---- Stormcaller
    void lightningStorm(Player p) {
        LivingEntity t = lookTarget(p, 30);
        if (t == null) { msg(p, Component.text("No target in sight", NamedTextColor.GRAY)); return; }
        if (!cd(p, "lightning_storm", 30)) return;
        World w = t.getWorld();
        new BukkitRunnable() {          // dark storm cloud over the target
            int n = 0;
            @Override public void run() {
                if (!t.isValid() || n++ > 9) { cancel(); return; }
                Location top = t.getLocation().add(0, 12, 0);
                for (int i = 0; i < 30; i++)
                    dustAt(top.clone().add((Math.random() - .5) * 8, (Math.random() - .5) * 1.2, (Math.random() - .5) * 8), 60, 70, 90, 2.4f, 1, 0.1);
                sp(w, Particle.ELECTRIC_SPARK, top, 6, 3, 0.5, 3, 0.2);
            }
        }.runTaskTimer(this, 0, 2);
        for (int i = 0; i < 3; i++) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!t.isValid() || t.isDead()) return;
                Location g = t.getLocation();
                beam(g.clone().add(0, 14, 0), g, 0.4, l -> { sp(w, Particle.ELECTRIC_SPARK, l, 3, .25, .1, .25, 0.1); dustAt(l, 150, 220, 255, 1.5f, 1, 0.15); });
                w.strikeLightningEffect(g);
                sp(w, Particle.ELECTRIC_SPARK, g.clone().add(0, 1, 0), 70, .6, .9, .6, 0.7);
                sp(w, Particle.SPLASH, g.clone().add(0, 0.3, 0), 30, .6, .1, .6, 0.2);
                expandRing(g, 4, 6, l -> sp(w, Particle.ELECTRIC_SPARK, l, 1));
                trueDamage(t, p, 4.0);
            }, 20L + i * 6L);
        }
    }

    void tidesCall(Player p) {
        if (!cd(p, "tides_call", 40)) return;
        World w = p.getWorld();
        w.playSound(p.getLocation(), Sound.ITEM_TRIDENT_THUNDER, 1f, 0.7f);
        w.playSound(p.getLocation(), Sound.ENTITY_DOLPHIN_SPLASH, 1.5f, 0.6f);
        expandRing(p.getLocation(), 20, 14, l -> { sp(w, Particle.SPLASH, l, 6, .2, .3, .2); sp(w, Particle.BUBBLE_POP, l, 2, .2, .3, .2); });
        auraTask(p, 200, l -> { sp(w, Particle.SPLASH, l, 6, .15, .15, .15); sp(w, Particle.BUBBLE_POP, l, 2, .1, .1, .1); });
        new BukkitRunnable() {
            int n = 0;
            @Override public void run() {
                if (!p.isOnline() || n++ >= 100) { cancel(); return; }
                for (Entity en : p.getNearbyEntities(20, 20, 20)) {
                    if (!(en instanceof LivingEntity le) || en instanceof ArmorStand) continue;
                    if (en.getLocation().distanceSquared(p.getLocation()) > 400) continue;
                    Vector v = p.getLocation().toVector().subtract(en.getLocation().toVector());
                    if (v.lengthSquared() > 9) en.setVelocity(v.normalize().multiply(0.7).setY(0.12));   // fast pull
                    if (!(en instanceof WaterMob) && !(en instanceof Drowned) && !le.hasPotionEffect(PotionEffectType.WATER_BREATHING))
                        le.setRemainingAir(Math.max(-20, le.getRemainingAir() - 12));
                    sp(w, Particle.BUBBLE_POP, en.getLocation().add(0, 1, 0), 5, .3, .5, .3);
                    if (n % 3 == 0) beam(en.getLocation().add(0, 1, 0), p.getLocation().add(0, 1, 0), 1.3, l -> sp(w, Particle.BUBBLE_POP, l, 1));
                }
            }
        }.runTaskTimer(this, 0, 2);
    }

    // ---- Dwarven Pickaxe
    void callOfTheDeep(Player p) {
        if (!cd(p, "call_of_the_deep", 120)) return;
        int R = getConfig().getInt("dome-radius", 20);
        Location c = p.getLocation(); World w = c.getWorld(); Random rnd = new Random();
        List<Block> placed = new ArrayList<>();
        for (int x = -R - 1; x <= R + 1; x++) for (int y = -R - 1; y <= R + 1; y++) for (int z = -R - 1; z <= R + 1; z++) {
            double d = Math.sqrt(x * x + y * y + z * z);
            if (d < R - 0.5 || d >= R + 0.5) continue;
            int by = c.getBlockY() + y;
            if (by < w.getMinHeight() || by >= w.getMaxHeight()) continue;
            int bx = c.getBlockX() + x, bz = c.getBlockZ() + z;
            if (!w.isChunkLoaded(bx >> 4, bz >> 4)) continue;
            Block b = w.getBlockAt(bx, by, bz);
            if (!b.getType().isAir()) continue;       // never overwrite existing blocks
            b.setType(rnd.nextBoolean() ? Material.DIRT : Material.STONE, false);
            dome.put(b, Material.AIR); placed.add(b);
        }
        // floor (3 layers, only over air) under the dome - unbreakable, same as the walls
        for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
            if (dx * dx + dz * dz > R * R) continue;
            for (int layer = 1; layer <= 3; layer++) {
                int by = c.getBlockY() - layer;
                if (by < w.getMinHeight()) break;
                int bx = c.getBlockX() + dx, bz = c.getBlockZ() + dz;
                if (!w.isChunkLoaded(bx >> 4, bz >> 4)) break;
                Block fb = w.getBlockAt(bx, by, bz);
                if (!fb.getType().isAir()) continue;
                Material fm = rnd.nextBoolean() ? Material.DIRT : Material.STONE;
                fb.setType(fm, false);
                dome.put(fb, Material.AIR); placed.add(fb);        // unbreakable like the dome walls; removed with the dome
            }
        }
        w.playSound(c, Sound.BLOCK_ANVIL_LAND, 1f, 0.5f);
        w.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.5f);
        for (int i = 0; i < placed.size(); i += 3)
            sp(w, Particle.BLOCK, placed.get(i).getLocation().add(0.5, 0.5, 0.5), 6, .4, .4, .4, 0, (i % 2 == 0 ? Material.DIRT : Material.STONE).createBlockData());
        expandRing(c, R, 16, l -> { sp(w, Particle.CLOUD, l, 2, .3, .2, .3, 0.02); sp(w, Particle.BLOCK, l, 3, .3, .2, .3, 0, Material.STONE.createBlockData()); });
        column(c, 6, l -> sp(w, Particle.CLOUD, l, 2, .5, .1, .5, 0.02));
        msg(p, Component.text("CALL OF THE DEEP", NamedTextColor.GRAY));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (Block b : placed) {
                Material t = b.getType();
                if (dome.remove(b) != null && (t == Material.DIRT || t == Material.STONE)) b.setType(Material.AIR, false);
            }
            for (Block b : new ArrayList<>(domeFloor.keySet())) {
                Material placedType = domeFloor.remove(b);
                if (b.getType() == placedType) b.setType(Material.AIR, false);     // only if nobody replaced it
            }
        }, 1200L);
    }

    boolean isOre(Material m) { return m == Material.ANCIENT_DEBRIS || m.name().endsWith("_ORE"); }

    void processDrops(Player p, ItemStack tool, Block b) {
        boolean ore = isOre(b.getType());
        Location loc = b.getLocation().add(0.5, 0.5, 0.5);
        for (ItemStack d : b.getDrops(tool, p)) {
            ItemStack out = d.clone();
            Material sm = smelt.get(out.getType());
            if (sm != null) out = new ItemStack(sm, out.getAmount());
            if (ore) out.setAmount(Math.min(out.getMaxStackSize(), out.getAmount() * 2));
            b.getWorld().dropItemNaturally(loc, out);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (dome.containsKey(b)) { e.setCancelled(true); return; }
        if (mining) return;
        Player p = e.getPlayer();
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (!"dwarven_pickaxe".equals(id(tool)) || p.getGameMode() == GameMode.CREATIVE) return;
        mining = true;
        try {
            BlockFace f = p.getTargetBlockFace(8);
            if (f == null) f = BlockFace.UP;
            e.setDropItems(false);
            processDrops(p, tool, b);
            for (int a = -1; a <= 1; a++) for (int c = -1; c <= 1; c++) {
                if (a == 0 && c == 0) continue;
                Block o = switch (f) {
                    case UP, DOWN -> b.getRelative(a, 0, c);
                    case NORTH, SOUTH -> b.getRelative(a, c, 0);
                    default -> b.getRelative(0, c, a);
                };
                Material t = o.getType();
                if (t.isAir() || o.isLiquid() || t.getHardness() < 0 || t.getHardness() > 50 || dome.containsKey(o)) continue;
                if (o.getState() instanceof Container) continue;
                BlockBreakEvent be = new BlockBreakEvent(o, p);
                Bukkit.getPluginManager().callEvent(be);
                if (be.isCancelled()) continue;
                processDrops(p, tool, o);
                o.getWorld().playEffect(o.getLocation(), Effect.STEP_SOUND, o.getBlockData());
                o.setType(Material.AIR);
            }
        } finally { mining = false; }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(dome::containsKey); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(dome::containsKey); }

    @EventHandler(ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent e) { for (Block b : e.getBlocks()) if (dome.containsKey(b)) { e.setCancelled(true); return; } }

    @EventHandler(ignoreCancelled = true)
    public void onPistonR(BlockPistonRetractEvent e) { for (Block b : e.getBlocks()) if (dome.containsKey(b)) { e.setCancelled(true); return; } }

    // ---- 10 Ton Axe
    void stun(LivingEntity t, int ticks) {
        t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 6, false, false));
        if (t instanceof Player pl) {
            stunned.put(pl.getUniqueId(), System.currentTimeMillis() + ticks * 50L);
            pl.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, ticks, 128, false, false));
        } else if (t instanceof Mob m) {
            m.setAware(false);
            Bukkit.getScheduler().runTaskLater(this, () -> { if (m.isValid()) m.setAware(true); }, ticks);
        }
    }

    void stunningStrike(Player p) {
        LivingEntity t = lookTarget(p, 6);
        if (t == null) { msg(p, Component.text("No target in reach", NamedTextColor.GRAY)); return; }
        if (!cd(p, "stunning_strike", 30)) return;
        stun(t, 100);
        trueDamage(t, p, 4.0);
        World w = t.getWorld(); Location g = t.getLocation();
        w.playSound(g, Sound.ENTITY_IRON_GOLEM_ATTACK, 1f, 0.6f);
        w.playSound(g, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.4f);
        sp(w, Particle.EXPLOSION, g.clone().add(0, 1, 0), 2, .3, .3, .3);
        sp(w, Particle.CRIT, g.clone().add(0, 1, 0), 50, .5, .6, .5, 0.5);
        expandRing(g, 5, 8, l -> { sp(w, Particle.CLOUD, l, 2, .2, .1, .2, 0.02); sp(w, Particle.CRIT, l, 1); });
        new BukkitRunnable() {          // stars circling the stunned target's head
            int n = 0;
            @Override public void run() {
                if (!t.isValid() || n++ >= 25) { cancel(); return; }
                Location head = t.getEyeLocation().add(0, 0.5, 0);
                for (int i = 0; i < 4; i++) {
                    double a = n * 0.5 + i * Math.PI / 2;
                    sp(w, Particle.CRIT, head.clone().add(Math.cos(a) * 0.7, 0, Math.sin(a) * 0.7), 1);
                    dustAt(head.clone().add(Math.cos(a) * 0.7, 0.1, Math.sin(a) * 0.7), 255, 230, 80, 1.0f, 1, 0.02);
                }
            }
        }.runTaskTimer(this, 0, 4);
    }

    void durabilityDrain(Player p) {
        LivingEntity t = lookTarget(p, 6);
        if (t == null) { msg(p, Component.text("No target in reach", NamedTextColor.GRAY)); return; }
        if (!cd(p, "durability_drain", 60)) return;
        trueDamage(t, p, 4.0);
        EntityEquipment eq = t.getEquipment();
        if (eq != null) {
            ItemStack[] arm = eq.getArmorContents();
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < arm.length; i++) if (arm[i] != null && arm[i].getType().getMaxDurability() > 0) idx.add(i);
            if (!idx.isEmpty()) {
                int i = idx.get(new Random().nextInt(idx.size()));
                ItemStack it = arm[i];
                if (it.getItemMeta() instanceof Damageable dm) {
                    int nd = dm.getDamage() + 30;
                    if (nd >= it.getType().getMaxDurability()) {
                        arm[i] = null;
                        t.getWorld().playSound(t.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
                    } else { dm.setDamage(nd); it.setItemMeta(dm); }
                    eq.setArmorContents(arm);
                }
            }
        }
        t.getWorld().playSound(t.getLocation(), Sound.BLOCK_ANVIL_PLACE, 1f, 0.8f);
        Location mid = t.getLocation().add(0, 1.2, 0);
        sp(t.getWorld(), Particle.ITEM, mid, 40, .4, .6, .4, 0.15, new ItemStack(Material.NETHERITE_CHESTPLATE));
        sp(t.getWorld(), Particle.CRIT, mid, 30, .4, .6, .4, 0.4);
        expandRing(t.getLocation(), 3, 6, l -> sp(t.getWorld(), Particle.ENCHANTED_HIT, l, 2, .1, .3, .1, 0.1));
    }

    @EventHandler
    public void onStunMove(PlayerMoveEvent e) {
        if (!isStunned(e.getPlayer())) return;
        Location f = e.getFrom(), t = e.getTo();
        if (t == null) return;
        if (f.getX() != t.getX() || f.getY() != t.getY() || f.getZ() != t.getZ()) {
            Location n = f.clone(); n.setYaw(t.getYaw()); n.setPitch(t.getPitch()); e.setTo(n);
        }
    }

    @EventHandler
    public void onStunInteract(PlayerInteractEvent e) { if (isStunned(e.getPlayer())) e.setCancelled(true); }

    // ---- Void Blade
    void voidWalk(Player p) {
        if (!cd(p, "void_walk", 15)) return;
        Location eye = p.getEyeLocation(); Vector dir = eye.getDirection().normalize();
        RayTraceResult r = p.getWorld().rayTraceBlocks(eye, dir, 20, FluidCollisionMode.NEVER, true);
        double dist = r == null ? 20 : Math.max(0, eye.toVector().distance(r.getHitPosition()) - 0.6);
        Location base = p.getLocation(), dest = null;
        for (double d = dist; d >= 0; d -= 0.5) {
            Location c = base.clone().add(dir.clone().multiply(d));
            if (!c.getBlock().getType().isSolid() && !c.clone().add(0, 1, 0).getBlock().getType().isSolid()) { dest = c; break; }
        }
        if (dest == null) return;
        World w = p.getWorld();
        Location from = base.clone().add(0, 1, 0), to = dest.clone().add(0, 1, 0);
        beam(from, to, 0.3, l -> { dustAt(l, 150, 60, 255, 1.4f, 1, 0.12); sp(w, Particle.REVERSE_PORTAL, l, 2, .15, .15, .15, 0.05); });
        helix(from, to, 0.7, 0.25, l -> sp(w, Particle.DRAGON_BREATH, l, 1));
        sp(w, Particle.PORTAL, from, 70, .4, .8, .4, 0.8);
        expandRing(base, 3, 6, l -> dustAt(l, 150, 60, 255, 1.5f, 1, 0.05));
        p.teleport(dest);
        sp(w, Particle.REVERSE_PORTAL, to, 70, .4, .8, .4, 0.4);
        expandRing(dest, 3, 6, l -> dustAt(l, 190, 120, 255, 1.5f, 1, 0.05));
        p.getWorld().playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.8f);
    }

    void pullOfTheVoid(Player p) {
        Location eye = p.getEyeLocation();
        RayTraceResult r = p.getWorld().rayTraceEntities(eye, eye.getDirection(), 40, 0.6,
                en -> en != p && en instanceof LivingEntity && !(en instanceof ArmorStand) && !en.isDead());
        if (r == null || r.getHitEntity() == null) { msg(p, Component.text("No target in sight", NamedTextColor.GRAY)); return; }
        if (!cd(p, "pull_of_the_void", 30)) return;
        Entity t = r.getHitEntity();
        Vector flat = eye.getDirection().setY(0);
        if (flat.lengthSquared() < 1e-4) flat = new Vector(0, 0, 1);
        Location dest = p.getLocation().add(flat.normalize().multiply(2));
        if (dest.getBlock().getType().isSolid() || dest.clone().add(0, 1, 0).getBlock().getType().isSolid()) dest = p.getLocation();
        World w = t.getWorld();
        Location from = t.getLocation().add(0, 1, 0), to = p.getLocation().add(0, 1, 0);
        beam(from, to, 0.3, l -> { dustAt(l, 150, 60, 255, 1.4f, 1, 0.12); sp(w, Particle.PORTAL, l, 2, .15, .15, .15, 0.3); });
        helix(from, to, 0.8, 0.25, l -> sp(w, Particle.REVERSE_PORTAL, l, 1));
        sp(w, Particle.PORTAL, from, 70, .4, .8, .4, 0.8);
        expandRing(t.getLocation(), 3, 6, l -> dustAt(l, 150, 60, 255, 1.5f, 1, 0.05));
        dest.setYaw(t.getLocation().getYaw()); dest.setPitch(t.getLocation().getPitch());
        t.teleport(dest);
        sp(w, Particle.REVERSE_PORTAL, dest.clone().add(0, 1, 0), 60, .4, .8, .4, 0.4);
        t.getWorld().playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.6f);
    }

    // ---- Inferno
    void acidicBlaze(Player p) {
        LivingEntity t = lookTarget(p, 20);
        if (t == null) { msg(p, Component.text("No target in sight", NamedTextColor.GRAY)); return; }
        if (!cd(p, "acidic_blaze", 45)) return;
        World w = t.getWorld();
        beam(p.getEyeLocation().add(0, -0.3, 0), t.getLocation().add(0, 1, 0), 0.35, l -> { sp(w, Particle.FLAME, l, 2, .1, .1, .1, 0.02); dustAt(l, 140, 255, 40, 1.4f, 1, 0.1); });
        sp(w, Particle.EXPLOSION, t.getLocation().add(0, 1, 0), 1);
        expandRing(t.getLocation(), 3, 8, l -> { sp(w, Particle.FLAME, l, 2, .1, .3, .1, 0.04); dustAt(l, 140, 255, 40, 1.3f, 1, 0.05); });
        new BukkitRunnable() {
            int n = 0;
            @Override public void run() {
                if (!t.isValid() || t.isDead() || n >= 100) { cancel(); return; }
                t.setFireTicks(30);                                  // re-ignites every 2 ticks: water cannot put it out
                Location b = t.getLocation().add(0, 1, 0);
                sp(w, Particle.FLAME, b, 4, .3, .6, .3, 0.03);
                dustAt(b, 140, 255, 40, 1.2f, 3, 0.4);
                if (n % 4 == 0) sp(w, Particle.LAVA, b, 1, .3, .5, .3);
                if (n % 10 == 0 && n > 0 && t.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE))
                    trueDamage(t, p, 1.0);                           // bypasses fire resistance
                n++;
            }
        }.runTaskTimer(this, 0, 2);
        w.playSound(t.getLocation(), Sound.ITEM_FIRECHARGE_USE, 1f, 0.7f);
        w.playSound(t.getLocation(), Sound.BLOCK_FIRE_AMBIENT, 1f, 0.6f);
    }

    boolean inIncin(Location l) {
        long now = System.currentTimeMillis();
        for (Incin i : incin.values()) {
            if (i.end() < now || !i.center().getWorld().equals(l.getWorld())) continue;
            if (i.center().distanceSquared(l) <= (double) i.radius() * i.radius()) return true;
        }
        return false;
    }

    void evaporate(Location c, int R, World w) {
        int cx = c.getBlockX(), cy = c.getBlockY(), cz = c.getBlockZ();
        new BukkitRunnable() {
            int x = -R, steam = 0;
            @Override public void run() {
                for (int n = 0; n < 5 && x <= R; n++, x++) {
                    for (int dz = -R; dz <= R; dz++) for (int dy = -R; dy <= R; dy++) {
                        if (x * x + dy * dy + dz * dz > R * R) continue;
                        int bx = cx + x, by = cy + dy, bz = cz + dz;
                        if (by < w.getMinHeight() || by >= w.getMaxHeight() || !w.isChunkLoaded(bx >> 4, bz >> 4)) continue;
                        Block b = w.getBlockAt(bx, by, bz);
                        Material t = b.getType();
                        boolean gone = false;
                        if (t == Material.WATER || t == Material.BUBBLE_COLUMN || t == Material.KELP || t == Material.KELP_PLANT
                                || t == Material.SEAGRASS || t == Material.TALL_SEAGRASS) { b.setType(Material.AIR, false); gone = true; }
                        else if (b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged()) { wl.setWaterlogged(false); b.setBlockData(wl, false); gone = true; }
                        if (gone && (steam++ % 6 == 0)) sp(w, Particle.CLOUD, b.getLocation().add(0.5, 0.8, 0.5), 2, .3, .3, .3, 0.05);
                    }
                }
                if (x > R) cancel();
            }
        }.runTaskTimer(this, 0, 1);
    }

    void incinerate(Player p) {
        if (!cd(p, "incineration", 120)) return;
        int R = getConfig().getInt("incineration-radius", 20);
        Location c = p.getLocation().clone(); World w = c.getWorld();
        incin.put(p.getUniqueId(), new Incin(c, R, System.currentTimeMillis() + 20_000));
        w.playSound(c, Sound.ENTITY_BLAZE_SHOOT, 1.5f, 0.5f);
        w.playSound(c, Sound.ITEM_FIRECHARGE_USE, 1.5f, 0.5f);
        w.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.5f);
        expandRing(c, R, 20, l -> { sp(w, Particle.FLAME, l, 2, .1, .5, .1, 0.05); sp(w, Particle.LAVA, l, 1); });
        expandRing(c, R * 0.6, 14, l -> sp(w, Particle.SMOKE, l.clone().add(0, 0.5, 0), 1, .1, .5, .1, 0.02));
        column(c, 10, l -> sp(w, Particle.FLAME, l, 4, .6, .1, .6, 0.04));
        evaporate(c, R, w);
        for (int k = 1; k <= 5; k++) Bukkit.getScheduler().runTaskLater(this, () -> evaporate(c, R, w), k * 80L);   // re-scan in case water flows back
        new BukkitRunnable() {          // inferno aura while it lasts
            int n = 0;
            @Override public void run() {
                if (!p.isOnline() || n++ > 40) { cancel(); return; }
                Location b = p.getLocation();
                for (int i = 0; i < 6; i++) {
                    double a = Math.random() * Math.PI * 2, r = 1 + Math.random() * 3;
                    sp(w, Particle.FLAME, b.clone().add(Math.cos(a) * r, 0.1, Math.sin(a) * r), 0, 0, 1, 0, 0.25);
                }
                sp(w, Particle.FLAME, b.clone().add(0, 1, 0), 30, 3, 1.5, 3, 0.02);
                sp(w, Particle.LAVA, b.clone().add(0, 1, 0), 3, 2, 1, 2);
            }
        }.runTaskTimer(this, 0, 10);
    }

    @EventHandler(ignoreCancelled = true)
    public void onWaterFlow(BlockFromToEvent e) {
        if (e.getBlock().getType() == Material.WATER && inIncin(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (e.getBucket() == Material.WATER_BUCKET && inIncin(e.getBlock().getLocation())) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ combat hooks
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (internal || !(e.getEntity() instanceof LivingEntity victim)) return;
        Player p; String id = null;
        if (e.getDamager() instanceof Player pl) { p = pl; id = id(pl.getInventory().getItemInMainHand()); }
        else if (e.getDamager() instanceof Trident tr && tr.getShooter() instanceof Player pl) { p = pl; id = id(tr.getItemStack()); }
        else return;

        if (isStunned(p)) { e.setCancelled(true); return; }

        // Inferno - Incineration: players in the area take 2x damage from the wielder
        Incin in = incin.get(p.getUniqueId());
        if (in != null && in.end() > System.currentTimeMillis() && victim instanceof Player
                && p.getWorld().equals(victim.getWorld())
                && victim.getLocation().distanceSquared(in.center()) <= (double) in.radius() * in.radius())
            e.setDamage(e.getDamage() * 2);

        Mark mk = marks.get(victim.getUniqueId());
        if (mk != null && mk.holder().equals(p.getUniqueId()) && mk.end() > System.currentTimeMillis())
            e.setDamage(e.getDamage() * (1 + mk.bonus()));

        if (id == null) return;
        final Player pf = p;
        hitFx(victim, id);
        int n = hitCounters.merge(p.getUniqueId() + id, 1, Integer::sum);
        switch (id) {
            case "bloody_eclipse" -> {
                Long end = bloodbath.get(p.getUniqueId());
                if (end != null && end > System.currentTimeMillis()) {
                    e.setDamage(e.getDamage() * 1.5);
                    sp(victim.getWorld(), Particle.CRIT, victim.getLocation().add(0, 1, 0), 20, .3, .5, .3);
                }
                if (n % 25 == 0) bleed(victim, p);
            }
            case "divine_judgement", "hammer_of_the_void" -> maceHit(e, p, victim, id);
            case "stormcaller" -> {
                if (n % 5 == 0) Bukkit.getScheduler().runTask(this, () -> {
                    if (!victim.isValid() || victim.isDead()) return;
                    victim.getWorld().strikeLightningEffect(victim.getLocation());
                    trueDamage(victim, pf, 4.0);
                });
            }
            default -> {}
        }
    }
}
