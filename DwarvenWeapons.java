package dev.dwarven;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Container;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
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

import java.io.File;
import java.util.*;
import java.util.function.Consumer;

public class DwarvenWeapons extends JavaPlugin implements Listener {

    static final List<String> IDS = List.of("bloody_eclipse", "stormcaller", "dwarven_pickaxe",
            "ten_ton_axe", "void_blade", "inferno", "sculk_battle_axe", "royal_spear", "kings_crown", "divine_judgement", "hammer_of_the_void",
            "temporal_reaver", "primal_bow", "mad_scientists_crossbow",
            "kings_shield", "kings_plate", "kings_legguards", "kings_step", "chrono_blade", "soul_reaper",
            "dragons_rend", "axe_of_abyss", "divine_executioner", "priests_staff", "gamblers_sword");

    NamespacedKey KEY, HUNT_KEY, PRIMAL_KEY, CROSS_KEY;
    boolean internal = false;   // guard for our own true-damage calls
    boolean mining = false;     // guard for 3x3 mining recursion

    final Map<String, Long> cooldowns = new HashMap<>();
    final Map<UUID, Long> bloodbath = new HashMap<>();
    final Map<UUID, Long> eclipsed = new HashMap<>();   // victims of Blinding Eclipse (no wind charges/cobwebs)
    final Map<UUID, Long> stunned = new HashMap<>();
    final Map<String, Integer> hitCounters = new HashMap<>();
    final Map<Block, BlockData> domeOrig = new HashMap<>();   // what each dome block replaced (restored afterwards)
    final Map<UUID, String> packStatus = new HashMap<>();
    final Map<Block, Material> dome = new HashMap<>();  // dome block -> original material (always air)
    final Map<UUID, Incin> incin = new HashMap<>();
    final Map<Material, Material> smelt = new HashMap<>();
    final Map<UUID, Long> lungeCd = new HashMap<>();
    final Map<UUID, String> lastMaceId = new HashMap<>();     // for attribute swapping: the mace held a moment ago
    final Map<UUID, Long> lastMaceTime = new HashMap<>();
    final Map<Block, BlockData> incinOrig = new HashMap<>();
    final Map<Block, Material> incinSet = new HashMap<>();
    final Map<UUID, Set<UUID>> trusted = new HashMap<>();     // owner -> players they trust (allies)
    File trustFile;
    final Map<UUID, Long> kickImmune = new HashMap<>();       // players who may hover because of an ability (no fly kick)
    final Map<UUID, Integer> bleedHits = new HashMap<>();
    final Random rng = new Random();
    final Map<UUID, Long> reprisal = new HashMap<>();
    final Map<UUID, Long> decree = new HashMap<>();
    final Map<UUID, AttributeModifier> decreeMods = new HashMap<>();
    final Map<UUID, Long> voidWeak = new HashMap<>();
    final Map<UUID, WarpState> warps = new HashMap<>();
    static class WarpState { Location loc; double health; int food; }
    final Map<UUID, Domain> domains = new HashMap<>();
    record Domain(Location center, int radius, long end) {}
    final List<Rift> rifts = new ArrayList<>();
    static class Rift { Location loc; UUID owner; long end; }
    final Map<UUID, SacGui> sacGuis = new HashMap<>();
    static class SacGui { Inventory inv; }
    final Map<UUID, Integer> doubleHits = new HashMap<>();
    final Map<UUID, Integer> fortuneHits = new HashMap<>();
    final Map<UUID, Integer> houseBonus = new HashMap<>();
    final Map<UUID, Integer> doubleStreak = new HashMap<>();
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
        KEY = new NamespacedKey("dwarvenweapons", "weapon");
        HUNT_KEY = new NamespacedKey("dwarvenweapons", "hunt_arrow");
        PRIMAL_KEY = new NamespacedKey("dwarvenweapons", "primal_arrow");
        CROSS_KEY = new NamespacedKey("dwarvenweapons", "cocktail_arrow");
        getServer().getPluginManager().registerEvents(this, this);
        buildSmeltMap();
        loadTrust();
        new BukkitRunnable() {
            @Override public void run() { for (Player p : Bukkit.getOnlinePlayers()) passives(p); }
        }.runTaskTimer(this, 20, 20);
        new BukkitRunnable() {          // cooldown HUD + weapon auras + warden peace
            int t = 0;
            @Override public void run() {
                t++;
                tickRifts();
                Set<UUID> axeCarriers = new HashSet<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    String id = id(p.getInventory().getItemInMainHand());
                    if (id != null) {
                        auraFx(p, id); hud(p, id);
                        if (id.equals("divine_judgement") || id.equals("hammer_of_the_void")) recordMace(p, id);
                    } else armourHud(p);
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
        for (Map.Entry<Block, BlockData> en : domeOrig.entrySet()) en.getKey().setBlockData(en.getValue(), false);
        domeOrig.clear();
        dome.clear();
        for (Map.Entry<Block, BlockData> en : incinOrig.entrySet()) {
            Material st = incinSet.get(en.getKey());
            if (st != null && en.getKey().getType() == st) en.getKey().setBlockData(en.getValue(), false);
        }
        incinOrig.clear(); incinSet.clear();
    }

    void buildSmeltMap() {
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe r = it.next();
            if (r instanceof FurnaceRecipe fr && fr.getInputChoice() instanceof RecipeChoice.MaterialChoice mc) {
                for (Material m : mc.getChoices()) {
                    if (m == Material.NETHERRACK) continue;          // the pickaxe never smelts netherrack
                    smelt.putIfAbsent(m, fr.getResult().getType());
                }
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
                        "Passive: Bleed every 5 hits (10s cooldown), Speed II, Strength I");
            }
            case "gamblers_sword" -> {
                UUID gu = p.getUniqueId();
                Integer dh = doubleHits.get(gu);
                if (dh != null && dh > 0) {
                    e.setDamage(e.getDamage() * 1.5);                // Double: next 5 attacks deal 1.5x
                    if (dh <= 1) doubleHits.remove(gu); else doubleHits.put(gu, dh - 1);
                }
                double extra = 0;
                Integer fh = fortuneHits.get(gu);
                if (fh != null && fh > 0) {                          // Fortune: next 3 attacks +2 hearts true damage
                    extra += 4.0;
                    if (fh <= 1) fortuneHits.remove(gu); else fortuneHits.put(gu, fh - 1);
                }
                if (houseBonus.remove(gu) != null) extra += 2.0;     // House Edge: +1 heart on the next hit
                if (n % 10 == 0) houseEdge(p);
                if (extra > 0) { final double fxd = extra; Bukkit.getScheduler().runTask(this, () -> trueDamage(victim, pf, fxd)); }
            }
            case "stormcaller" -> {
                mat = Material.TRIDENT; name = "Stormcaller"; col = TextColor.color(0x5AC8FF);
                en.put(Enchantment.RIPTIDE, 3); en.put(Enchantment.SHARPNESS, 5); en.put(Enchantment.UNBREAKING, 3); en.put(Enchantment.MENDING, 1);
                lore = List.of("Ability 1 [F]: Lightning Storm - 3 strikes, 1 heart true damage each",
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
                lore = List.of("Ability 1 [F]: Stunning Strike - stun 3s + 2 hearts true damage",
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
                        "Ability 2 [Shift+F]: Incineration - turns the area into the Nether, enemies take 1.25x damage, 20s");
            }
            case "sculk_battle_axe" -> {
                mat = Material.NETHERITE_AXE; name = "Sculk Battle Axe"; col = TextColor.color(0x28C8DC);
                en.put(Enchantment.SHARPNESS, 5); en.put(Enchantment.UNBREAKING, 3); en.put(Enchantment.MENDING, 1);
                lore = List.of("Ability 1 [F]: Sculk Beams - 3 warden beams, 1 heart true damage + heavy armor durability each",
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
                        "Ability 2: Final Verdict - 3rd mace hit without touching the ground explodes: 2x damage, 60 durability to every armor piece",
                        "Passive: Shift+Right-click cycles Wind Burst I / II / III");
            }
            case "hammer_of_the_void" -> {
                mat = Material.MACE; name = "Hammer of the Void"; col = TextColor.color(0xAA50FF);
                en.put(Enchantment.DENSITY, 2); en.put(Enchantment.WIND_BURST, 1);
                en.put(Enchantment.UNBREAKING, 3); en.put(Enchantment.MENDING, 1);
                lore = List.of("Ability 1 [F]: throw the mace, then Right-click to teleport to it",
                        "Ability 2: Void Strike - the hit after 3 mace hits without touching the ground: 2x damage, Wither II + Poison II 20s",
                        "Passive: Shift+Right-click cycles Wind Burst I / II / III");
            }
            case "temporal_reaver" -> {
                mat = Material.NETHERITE_SWORD; name = "Temporal Reaver"; col = TextColor.color(0x32E164);
                en.put(Enchantment.SHARPNESS, 5); en.put(Enchantment.FIRE_ASPECT, 2); en.put(Enchantment.LOOTING, 3);
                en.put(Enchantment.UNBREAKING, 3); en.put(Enchantment.MENDING, 1);
                lore = List.of("Ability 1 [F]: Temporal Cleave - freezes time around you (players, mobs, projectiles) for 5s",
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
                en.put(Enchantment.QUICK_CHARGE, 5); en.put(Enchantment.MULTISHOT, 1); en.put(Enchantment.INFINITY, 1);
                en.put(Enchantment.UNBREAKING, 3); en.put(Enchantment.MENDING, 1);
                lore = List.of("Passive: Furious Cocktail - every arrow carries a random potion effect (never instant damage/health)",
                        "Passive: Grapple - every hit pulls the enemy 5 blocks toward you");
            }
            case "kings_shield" -> {
                mat = Material.SHIELD; name = "King's Shield"; col = TextColor.color(0xE2B560);
                en.put(Enchantment.UNBREAKING, 3); en.put(Enchantment.MENDING, 1);
                lore = List.of("Ability [/armour ability 1]: Imperial Reprisal - the next attack you block is reflected back:",
                        "3 hearts true damage + knockback (30s cooldown)");
            }
            case "kings_plate" -> {
                mat = Material.NETHERITE_CHESTPLATE; name = "King's Plate"; col = TextColor.color(0xE2B560);
                en.put(Enchantment.PROTECTION, 4); en.put(Enchantment.UNBREAKING, 3); en.put(Enchantment.MENDING, 1);
                lore = List.of("Ability [/armour ability 2]: Imperial Decree - 5s: no knockback and 50% less damage (30s cooldown)");
            }
            case "kings_legguards" -> {
                mat = Material.NETHERITE_LEGGINGS; name = "King's Legguards"; col = TextColor.color(0xE2B560);
                en.put(Enchantment.PROTECTION, 4); en.put(Enchantment.UNBREAKING, 3); en.put(Enchantment.MENDING, 1);
                lore = List.of("Ability [/armour ability 3]: Royal Slam - launch up and slam down: 4 hearts true damage in 10 blocks (60s)",
                        "Passive: no fall damage");
            }
            case "kings_step" -> {
                mat = Material.NETHERITE_BOOTS; name = "King's Step"; col = TextColor.color(0xE2B560);
                en.put(Enchantment.PROTECTION, 4); en.put(Enchantment.FEATHER_FALLING, 4); en.put(Enchantment.UNBREAKING, 3); en.put(Enchantment.MENDING, 1);
                lore = List.of("Ability [/armour ability 4]: Royal Dash - dash 20 blocks, enemies you pass take 2 hearts true damage (20s)",
                        "Passive: Speed III");
            }
            case "chrono_blade" -> {
                mat = Material.NETHERITE_SWORD; name = "Chrono Blade"; col = TextColor.color(0xFFD75A);
                en.put(Enchantment.SHARPNESS, 5); en.put(Enchantment.FIRE_ASPECT, 2); en.put(Enchantment.LOOTING, 3);
                en.put(Enchantment.UNBREAKING, 3); en.put(Enchantment.MENDING, 1);
                lore = List.of("Ability 1 [F]: Time Warp - mark this spot; after 5s you are pulled back here with your old health (acts as a totem), 5 min",
                        "Ability 2 [Shift+F]: Ageing Strike - Wither II + Poison II for 20s on the target (30s)");
            }
            case "soul_reaper" -> {
                mat = Material.NETHERITE_AXE; name = "Soul Reaper"; col = TextColor.color(0xAA3CFF);
                en.put(Enchantment.SHARPNESS, 5); en.put(Enchantment.SWEEPING_EDGE, 3); en.put(Enchantment.UNBREAKING, 3); en.put(Enchantment.MENDING, 1);
                lore = List.of("Ability 1 [F]: Health Drain - steal 3 hearts from every player within 5 blocks for 20s (60s)",
                        "Ability 2 [Shift+F]: Life Giver - Regeneration III for 10s + 1s of saturation");
            }
            case "dragons_rend" -> {
                mat = Material.NETHERITE_SWORD; name = "Dragon's Rend"; col = TextColor.color(0xA050FF);
                en.put(Enchantment.SHARPNESS, 5); en.put(Enchantment.FIRE_ASPECT, 2); en.put(Enchantment.UNBREAKING, 3); en.put(Enchantment.MENDING, 1);
                lore = List.of("Ability 1 [F]: Void Slash - 40 wide, 30 long slash: 4 hearts true damage + rifts that hurt and curse (30s)",
                        "Ability 2 [Shift+F]: Dragon's Domain - unbreakable black concrete sphere, End theme, dragon's breath floor, 1.25x damage (10s, 2 min)");
            }
            case "axe_of_abyss" -> {
                mat = Material.NETHERITE_AXE; name = "Axe of the Abyss"; col = TextColor.color(0x8C32C8);
                en.put(Enchantment.SHARPNESS, 5); en.put(Enchantment.UNBREAKING, 3); en.put(Enchantment.MENDING, 1);
                lore = List.of("Ability 1 [F]: Abyssal Cleave - 25 block slash: 3 hearts true damage + Wither II 5s (30s)",
                        "Ability 2 [Shift+F]: Unstable Power of the Void - 10s Strength II, Speed III, Resistance II, then 10s of weakness and +50% damage taken");
            }
            case "divine_executioner" -> {
                mat = Material.NETHERITE_SWORD; name = "Divine Executioner"; col = TextColor.color(0xFFDC64);
                en.put(Enchantment.SHARPNESS, 5); en.put(Enchantment.UNBREAKING, 3); en.put(Enchantment.MENDING, 1);
                lore = List.of("Ability 1 [F]: Execution - a slash for 3 hearts true damage (30s)",
                        "Ability 2 [Shift+F]: Judgement's Mark - Wither II + Slowness I and +25% damage from you for 20s (1 min)");
            }
            case "priests_staff" -> {
                mat = Material.NETHERITE_AXE; name = "Priest's Staff"; col = TextColor.color(0xFFF0AA);
                en.put(Enchantment.SHARPNESS, 5); en.put(Enchantment.UNBREAKING, 3); en.put(Enchantment.MENDING, 1);
                lore = List.of("Ability 1 [F]: Inspire - you and trusted allies within 20 blocks get Speed III + Strength II for 20s (1 min)",
                        "Ability 2 [Shift+F]: Sacrifice - offer ores to wear down nearby enemies' armor");
            }
            case "gamblers_sword" -> {
                mat = Material.NETHERITE_SWORD; name = "Jackpot"; col = TextColor.color(0xE6283C);
                en.put(Enchantment.SHARPNESS, 5); en.put(Enchantment.FIRE_ASPECT, 2); en.put(Enchantment.UNBREAKING, 3); en.put(Enchantment.MENDING, 1);
                lore = List.of("Ability 1 [F]: Lucky Draw - three reels decide your luck (45s)",
                        "Ability 2 [Shift+F]: Double or Nothing - 50/50, two Doubles in a row = JACKPOT (60s)",
                        "Passive: House Edge - every 10th hit rolls a small bonus");
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
        if (id.equals("stormcaller")) {
            meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(new NamespacedKey("dwarvenweapons", "trident_damage"),
                    8.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
            meta.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(new NamespacedKey("dwarvenweapons", "trident_speed"),
                    -2.4, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));   // 4.0 - 2.4 = 1.6, same as a sword
        }
        EquipmentSlot wornSlot = switch (id) {
            case "kings_crown" -> EquipmentSlot.HEAD;
            case "kings_plate" -> EquipmentSlot.CHEST;
            case "kings_legguards" -> EquipmentSlot.LEGS;
            case "kings_step" -> EquipmentSlot.FEET;
            default -> null;
        };
        if (wornSlot != null) {
            EquippableComponent eq = meta.getEquippable();      // worn model: assets/dwarven/equipment/<id>.json
            eq.setSlot(wornSlot);
            eq.setModel(new NamespacedKey("dwarven", id));
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
        String cn = c.getName().toLowerCase(Locale.ROOT);
        if (cn.equals("trust") || cn.equals("untrust")) return handleTrust(s, cn, a);
        if (cn.equals("armour")) return handleArmour(s, a);
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
            case "pack" -> { if (s instanceof Player pl) { sendPack(pl); s.sendMessage("Resource pack sent again."); } }
            case "info" -> {
                if (s instanceof Player pl) {
                    s.sendMessage("Held weapon id: " + id(pl.getInventory().getItemInMainHand())
                            + " | resource pack status: " + packStatus.getOrDefault(pl.getUniqueId(), "none yet"));
                }
            }
            default -> s.sendMessage("Unknown subcommand.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        String cn = c.getName().toLowerCase(Locale.ROOT);
        if (cn.equals("trust") || cn.equals("untrust")) {
            List<String> o = new ArrayList<>();
            if (cn.equals("trust") && a.length == 1) o.addAll(List.of("list", "remove"));
            if (a.length <= 2) Bukkit.getOnlinePlayers().forEach(pl -> o.add(pl.getName()));
            o.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(a[a.length - 1].toLowerCase(Locale.ROOT)));
            return o;
        }
        if (cn.equals("armour")) {
            List<String> o = new ArrayList<>();
            if (a.length == 1) o.add("ability"); else if (a.length == 2) o.addAll(List.of("1", "2", "3", "4"));
            return o;
        }
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("give", "list", "reload", "pack", "info"));
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
    public void onJoin(PlayerJoinEvent e) { sendPack(e.getPlayer()); }

    void sendPack(Player p) {
        String url = getConfig().getString("resource-pack-url", "");
        if (url == null || url.isBlank()) return;
        String sha = getConfig().getString("resource-pack-sha1", "");
        byte[] hash = null;
        if (sha != null && sha.length() == 40) {
            hash = new byte[20];
            for (int i = 0; i < 20; i++) hash[i] = (byte) Integer.parseInt(sha.substring(i * 2, i * 2 + 2), 16);
        }
        UUID packId = UUID.nameUUIDFromBytes(("marbles-op-ah-weapons:" + sha).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        p.setResourcePack(packId, url, hash, Component.text("Marble's OP Ah Weapons textures", NamedTextColor.GOLD),
                getConfig().getBoolean("resource-pack-required", false));
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent e) { packStatus.put(e.getPlayer().getUniqueId(), e.getStatus().name()); }

    // ------------------------------------------------------------------ trust system
    void loadTrust() {
        trustFile = new File(getDataFolder(), "trusted.yml");
        trusted.clear();
        if (!trustFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(trustFile);
        for (String k : y.getKeys(false)) {
            try {
                Set<UUID> set = new HashSet<>();
                for (String u : y.getStringList(k)) set.add(UUID.fromString(u));
                trusted.put(UUID.fromString(k), set);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    void saveTrust() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Set<UUID>> en : trusted.entrySet()) {
            List<String> l = new ArrayList<>();
            for (UUID u : en.getValue()) l.add(u.toString());
            y.set(en.getKey().toString(), l);
        }
        try { getDataFolder().mkdirs(); y.save(trustFile); }
        catch (java.io.IOException ex) { getLogger().warning("Could not save trusted.yml: " + ex.getMessage()); }
    }

    /** true when `e` is a player that `owner` has trusted - the owner's abilities never hit them */
    boolean ally(Player owner, Entity e) {
        if (owner == null || !(e instanceof Player other)) return false;
        Set<UUID> set = trusted.get(owner.getUniqueId());
        return set != null && set.contains(other.getUniqueId());
    }

    boolean handleTrust(CommandSender s, String cn, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Only players can use this."); return true; }
        Set<UUID> set = trusted.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
        if (cn.equals("trust") && (a.length == 0 || a[0].equalsIgnoreCase("list"))) {
            if (set.isEmpty()) { p.sendMessage(Component.text("You do not trust anyone. Use /trust <player>.", NamedTextColor.GRAY)); return true; }
            List<String> names = new ArrayList<>();
            for (UUID u : set) { String n = Bukkit.getOfflinePlayer(u).getName(); names.add(n == null ? u.toString() : n); }
            p.sendMessage(Component.text("Trusted players: " + String.join(", ", names), NamedTextColor.GREEN));
            return true;
        }
        boolean remove = cn.equals("untrust") || (a.length >= 2 && a[0].equalsIgnoreCase("remove"));
        String name = (a.length >= 2 && (a[0].equalsIgnoreCase("remove") || a[0].equalsIgnoreCase("add"))) ? a[1] : (a.length >= 1 ? a[0] : null);
        if (name == null) { p.sendMessage(Component.text("Usage: /trust <player>, /trust remove <player>, /untrust <player>, /trust list", NamedTextColor.GRAY)); return true; }
        OfflinePlayer t = Bukkit.getPlayerExact(name) != null ? Bukkit.getPlayerExact(name) : Bukkit.getOfflinePlayer(name);
        if (!t.isOnline() && !t.hasPlayedBefore()) { p.sendMessage(Component.text("Unknown player: " + name, NamedTextColor.RED)); return true; }
        if (t.getUniqueId().equals(p.getUniqueId())) { p.sendMessage(Component.text("You always count as yourself.", NamedTextColor.GRAY)); return true; }
        String shown = t.getName() == null ? name : t.getName();
        if (remove) {
            if (set.remove(t.getUniqueId())) { saveTrust(); p.sendMessage(Component.text("You no longer trust " + shown + ".", NamedTextColor.YELLOW)); }
            else p.sendMessage(Component.text(shown + " was not trusted.", NamedTextColor.GRAY));
        } else {
            if (set.add(t.getUniqueId())) { saveTrust(); p.sendMessage(Component.text("You now trust " + shown + ". Your abilities will not hit them.", NamedTextColor.GREEN)); }
            else p.sendMessage(Component.text(shown + " is already trusted.", NamedTextColor.GRAY));
        }
        return true;
    }

    // ------------------------------------------------------------------ fly kick protection
    void noKick(Player p, long ms) { kickImmune.merge(p.getUniqueId(), System.currentTimeMillis() + ms, Long::max); }

    @EventHandler
    public void onFlyKick(PlayerKickEvent e) {
        Player p = e.getPlayer();
        Long u = kickImmune.get(p.getUniqueId());
        if (!((u != null && u > System.currentTimeMillis()) || isStunned(p))) return;
        String reason = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(e.reason()).toLowerCase(Locale.ROOT);
        if (reason.contains("flying")) e.setCancelled(true);       // "Flying is not enabled on this server"
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
        if (!quiet) castBurst(p, key);
        return true;
    }

    boolean tryTotem(Player p) {
        PlayerInventory inv = p.getInventory();
        if (inv.getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING) {
            ItemStack it = inv.getItemInOffHand();
            if (it.getAmount() > 1) { it.setAmount(it.getAmount() - 1); inv.setItemInOffHand(it); } else inv.setItemInOffHand(null);
        } else if (inv.getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING) {
            ItemStack it = inv.getItemInMainHand();
            if (it.getAmount() > 1) { it.setAmount(it.getAmount() - 1); inv.setItemInMainHand(it); } else inv.setItemInMainHand(null);
        } else return false;
        p.setHealth(1.0);
        p.clearActivePotionEffects();
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 900, 1));
        p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 1));
        p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 800, 0));
        p.playEffect(EntityEffect.TOTEM_RESURRECT);
        return true;
    }

    void trueDamage(LivingEntity t, Player src, double amt) {
        if (t == null || t.isDead() || !t.isValid()) return;
        if (t instanceof Player pl && (pl.getGameMode() == GameMode.CREATIVE || pl.getGameMode() == GameMode.SPECTATOR)) return;
        if (t instanceof Player tq) {
            long now0 = System.currentTimeMillis();
            Long dq = decree.get(tq.getUniqueId());
            if (dq != null && dq > now0) amt *= 0.5;                 // Imperial Decree
            Long wq = voidWeak.get(tq.getUniqueId());
            if (wq != null && wq > now0) amt *= 1.5;                 // after the Void power fades
        }
        internal = true;
        try { if (src != null) t.damage(0.01, src); else t.damage(0.01); } finally { internal = false; }
        double nh = t.getHealth() - amt;
        if (nh <= 0) {
            if (t instanceof Player tw && warps.containsKey(tw.getUniqueId())) { rewind(tw, true); return; }   // Time Warp acts as a totem
            if (t instanceof Player tp && tryTotem(tp)) return;       // a totem saves them, like vanilla
            t.setHealth(0);
        } else t.setHealth(nh);
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
        return (t instanceof LivingEntity le && !(t instanceof ArmorStand) && !ally(p, t)) ? le : null;
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
                trueDamage(t, p, 2.0);
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
                    if (t instanceof Player kp) noKick(kp, 4000);
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
            if (c >= 3) {
                if (!cd(p, "final_verdict", 30, true)) { airHits.put(u, 3); return; }   // wait for cooldown, keep the charge
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
                    if (en instanceof LivingEntity le && en != p && !(en instanceof ArmorStand) && !ally(p, en)) drainAllArmor(le, 60);
                drainAllArmor(v, 60);
                msg(p, Component.text("FINAL VERDICT", NamedTextColor.GOLD));
                airHits.remove(u);
                return;
            }
        } else if (c >= 4) {   // 3 hits, then the NEXT hit is the void strike
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
        int ticks = getConfig().getInt("temporal-cleave.duration-seconds", 5) * 20;
        Location c = p.getLocation();
        List<LivingEntity> frozen = new ArrayList<>();
        Map<Projectile, Vector> proj = new HashMap<>();
        for (Entity en : p.getNearbyEntities(r, r, r)) {
            if (en.getLocation().distanceSquared(c) > (double) r * r) continue;
            if (en instanceof Projectile pr) { proj.put(pr, pr.getVelocity()); pr.setGravity(false); pr.setVelocity(new Vector()); }
            else if (en instanceof LivingEntity le && !(en instanceof ArmorStand) && !ally(p, en) && (en instanceof Player || en instanceof Mob)) { stun(le, ticks); frozen.add(le); }
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

    LivingEntity spawnClone(Player p, Location loc) {
        String tag = "mow_clone_" + UUID.randomUUID().toString().replace("-", "");
        World w = loc.getWorld();
        String cmd = String.format(Locale.ROOT,
                "execute in %s run summon minecraft:mannequin %.2f %.2f %.2f {Tags:[\"%s\"],NoGravity:1b,Invulnerable:1b,Silent:1b,immovable:1b,hide_description:1b,Rotation:[%.1ff,0.0f],profile:{name:\"%s\"}}",
                w.getKey(), loc.getX(), loc.getY(), loc.getZ(), tag, loc.getYaw(), p.getName());
        try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd); } catch (Exception ignored) { }
        for (Entity en : w.getNearbyEntities(loc, 2, 2, 2)) {
            if (en instanceof LivingEntity le && en.getScoreboardTags().contains(tag)) {
                EntityEquipment eq = le.getEquipment();
                if (eq != null) {
                    eq.setArmorContents(p.getInventory().getArmorContents());
                    eq.setItemInMainHand(p.getInventory().getItemInMainHand());
                }
                return le;
            }
        }
        return null;
    }

    void fractureClone(Player p, LivingEntity t, int idx, double dmg) {
        if (!t.isValid() || t.isDead() || !p.isOnline()) return;
        double ang = idx * (Math.PI * 2 / 5) + Math.random();
        Location loc = t.getLocation().add(Math.cos(ang) * 2.5, 0, Math.sin(ang) * 2.5);
        loc.setDirection(t.getLocation().toVector().subtract(loc.toVector()));
        World w = loc.getWorld();
        LivingEntity made = spawnClone(p, loc);          // a mannequin wearing the caster's skin
        if (made == null) {                              // fallback if the server cannot spawn mannequins
            made = w.spawn(loc, ArmorStand.class, as -> {
                as.setArms(true); as.setBasePlate(false); as.setInvulnerable(true); as.setGravity(false); as.setSilent(true);
                as.getEquipment().setArmorContents(p.getInventory().getArmorContents());
                as.getEquipment().setItemInMainHand(p.getInventory().getItemInMainHand());
            });
        }
        final LivingEntity ce = made;
        sp(w, Particle.PORTAL, loc.clone().add(0, 1, 0), 60, .3, .8, .3, .5);
        sp(w, Particle.END_ROD, loc.clone().add(0, 1, 0), 30, .3, .8, .3, 0.1);
        expandRing(loc, 2.5, 6, l -> { dustAt(l, 50, 230, 100, 1.6f, 1, 0.05); dustAt(l, 255, 205, 60, 1.2f, 1, 0.05); });
        w.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.6f);
        new BukkitRunnable() {          // flickering afterimage trail
            int n = 0;
            @Override public void run() {
                if (!ce.isValid() || n++ > 16) { cancel(); return; }
                Location b = ce.getLocation();
                for (int k = 0; k < 4; k++) {
                    Location l = b.clone().add((Math.random() - .5) * 0.8, Math.random() * 1.8, (Math.random() - .5) * 0.8);
                    dustAt(l, 50, 230, 100, 1.2f, 1, 0.02);
                    if (k == 0) sp(w, Particle.END_ROD, l, 1, 0, 0, 0, 0.01);
                }
            }
        }.runTaskTimer(this, 0, 1);
        Bukkit.getScheduler().runTaskLater(this, () -> {      // slash 1 deals the damage
            if (!t.isValid() || t.isDead()) return;
            ce.swingMainHand();
            Location from = ce.getLocation().add(0, 1.4, 0), to = t.getLocation().add(0, 1, 0);
            beam(from, to, 0.25, l -> { dustAt(l, 255, 205, 60, 1.5f, 1, 0.03); dustAt(l, 50, 230, 100, 1.3f, 1, 0.03); });
            sp(w, Particle.SWEEP_ATTACK, to, 3, .4, .4, .4, 0);
            sp(w, Particle.CRIT, to, 25, .4, .5, .4, 0.4);
            w.playSound(t.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.8f);
            trueDamage(t, p, dmg);
        }, 3L);
        Bukkit.getScheduler().runTaskLater(this, () -> {      // slash 2 is the afterimage
            if (!t.isValid()) return;
            ce.swingMainHand();
            Location from = ce.getLocation().add(0, 0.6, 0), to = t.getLocation().add(0, 1.4, 0);
            beam(from, to, 0.25, l -> { dustAt(l, 50, 230, 100, 1.5f, 1, 0.03); sp(w, Particle.END_ROD, l, 1, 0, 0, 0, 0.01); });
            sp(w, Particle.SWEEP_ATTACK, to, 3, .4, .4, .4, 0);
            w.playSound(t.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1.2f);
        }, 9L);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            sp(w, Particle.PORTAL, ce.getLocation().add(0, 1, 0), 50, .3, .8, .3, .5);
            sp(w, Particle.END_ROD, ce.getLocation().add(0, 1, 0), 20, .3, .8, .3, 0.1);
            ce.remove();
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
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WOLF_GROWL, 1.2f, 0.6f);
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
                PotionEffectType.BLINDNESS, PotionEffectType.HUNGER, PotionEffectType.NAUSEA,
                PotionEffectType.LEVITATION, PotionEffectType.GLOWING, PotionEffectType.SPEED, PotionEffectType.STRENGTH,
                PotionEffectType.REGENERATION, PotionEffectType.RESISTANCE, PotionEffectType.JUMP_BOOST};
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
        if (v instanceof Player kp) noKick(kp, 4000);
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
            int nm = Math.min(getConfig().getInt("hunters-momentum-max", 30), m + 1);   // only a successful hit builds momentum
            momentum.put(p.getUniqueId(), nm);
            msg(p, Component.text("Hunter's Momentum +" + nm, NamedTextColor.AQUA));
            dustAt(p.getEyeLocation(), 80, 225, 225, 1.2f, 6 + nm / 2, 0.5);
            sp(p.getWorld(), Particle.HAPPY_VILLAGER, p.getLocation().add(0, 1, 0), 3 + nm / 4, .5, .6, .5);
        }
        if (pdc.has(CROSS_KEY, PersistentDataType.BYTE))
            { if (cd(p, "grapple", 5, true)) Bukkit.getScheduler().runTask(this, () -> grapplePull(v, p, 5.0)); }
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
            if (en == p || !(en instanceof LivingEntity le) || en instanceof ArmorStand || ally(p, en)) continue;
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

    int[] swingColor(String id) {
        return switch (id) {
            case "bloody_eclipse" -> new int[]{220, 15, 30};
            case "stormcaller" -> new int[]{120, 210, 255};
            case "dwarven_pickaxe" -> new int[]{210, 210, 200};
            case "ten_ton_axe" -> new int[]{200, 70, 60};
            case "void_blade", "hammer_of_the_void" -> new int[]{160, 70, 255};
            case "inferno" -> new int[]{255, 140, 30};
            case "sculk_battle_axe" -> new int[]{30, 200, 230};
            case "royal_spear", "divine_judgement" -> new int[]{255, 205, 60};
            case "temporal_reaver" -> new int[]{50, 230, 100};
            case "chrono_blade" -> new int[]{255, 215, 90};
            case "soul_reaper" -> new int[]{170, 60, 255};
            case "dragons_rend" -> new int[]{150, 60, 255};
            case "axe_of_abyss" -> new int[]{100, 35, 150};
            case "divine_executioner" -> new int[]{255, 220, 100};
            case "priests_staff" -> new int[]{255, 240, 170};
            case "gamblers_sword" -> new int[]{230, 40, 60};
            default -> new int[]{255, 255, 255};
        };
    }

    Particle swingParticle(String id) {
        return switch (id) {
            case "stormcaller" -> Particle.ELECTRIC_SPARK;
            case "void_blade" -> Particle.PORTAL;
            case "hammer_of_the_void" -> Particle.REVERSE_PORTAL;
            case "inferno" -> Particle.FLAME;
            case "sculk_battle_axe" -> Particle.SCULK_SOUL;
            case "soul_reaper" -> Particle.SOUL;
            case "dragons_rend" -> Particle.REVERSE_PORTAL;
            case "axe_of_abyss" -> Particle.PORTAL;
            case "royal_spear", "divine_judgement", "temporal_reaver", "chrono_blade", "divine_executioner", "priests_staff" -> Particle.END_ROD;
            case "dwarven_pickaxe" -> Particle.ENCHANTED_HIT;
            default -> Particle.CRIT;
        };
    }

    @EventHandler
    public void onSwingTrail(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player p = e.getPlayer();
        String id = id(p.getInventory().getItemInMainHand());
        if (id == null || id.startsWith("kings_") || id.equals("primal_bow") || id.equals("mad_scientists_crossbow")) return;
        int[] col = swingColor(id);
        Particle part = swingParticle(id);
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Vector right = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1e-4) right = new Vector(1, 0, 0);
        right.normalize();
        Vector up = right.clone().crossProduct(dir).normalize();
        World w = p.getWorld();
        for (int i = 0; i <= 16; i++) {                       // glowing slash arc in front of the player
            double ang = -1.0 + i * (2.0 / 16);
            double lift = 0.35 - 0.7 * Math.abs(ang) / 1.0 * 0.5 + 0.2 * Math.sin(ang * 3);
            Location l = eye.clone().add(dir.clone().multiply(Math.cos(ang) * 2.3)).add(right.clone().multiply(Math.sin(ang) * 2.3)).add(up.clone().multiply(lift - 0.3));
            dustAt(l, col[0], col[1], col[2], 1.5f, 2, 0.03);
            if (i % 2 == 0) sp(w, part, l, 1, 0.02, 0.02, 0.02, 0.02);
        }
    }

    @EventHandler
    public void onJoinLunge(PlayerJoinEvent e) { setLunge(e.getPlayer(), true); }



    // ------------------------------------------------------------------ King's armour set (/armour ability 1-4)
    boolean holdsShield(Player p) {
        PlayerInventory i = p.getInventory();
        return "kings_shield".equals(id(i.getItemInMainHand())) || "kings_shield".equals(id(i.getItemInOffHand()));
    }

    boolean handleArmour(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
        if (a.length < 2 || !a[0].equalsIgnoreCase("ability")) {
            s.sendMessage("Usage: /armour ability <1-4>   (1 shield, 2 chestplate, 3 leggings, 4 boots)");
            return true;
        }
        int n;
        try { n = Integer.parseInt(a[1]); } catch (NumberFormatException ex) { s.sendMessage("Pick a number from 1 to 4."); return true; }
        switch (n) {
            case 1 -> imperialReprisal(p);
            case 2 -> imperialDecree(p);
            case 3 -> royalSlam(p);
            case 4 -> royalDash(p);
            default -> s.sendMessage("Pick a number from 1 to 4.");
        }
        return true;
    }

    void imperialReprisal(Player p) {
        if (!holdsShield(p)) { msg(p, Component.text("Hold the King's Shield to use this", NamedTextColor.RED)); return; }
        if (!cd(p, "imperial_reprisal", 30)) return;
        reprisal.put(p.getUniqueId(), System.currentTimeMillis() + 15_000);
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 0.6f);
        auraTask(p, 300, l -> dustAt(l, 255, 205, 60, 1.2f, 1, 0.05));
        msg(p, Component.text("IMPERIAL REPRISAL - block the next attack to reflect it", NamedTextColor.GOLD));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onShieldReflect(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Long armed = reprisal.get(p.getUniqueId());
        if (armed == null || armed < System.currentTimeMillis()) return;
        if (!p.isBlocking() || !holdsShield(p) || e.getFinalDamage() > 0.001) return;      // must be a fully blocked hit
        Entity d = e.getDamager();
        LivingEntity atk = null;
        if (d instanceof LivingEntity dl) atk = dl;
        else if (d instanceof Projectile pr && pr.getShooter() instanceof LivingEntity sl) atk = sl;
        if (atk == null || atk == p || ally(p, atk)) return;
        reprisal.remove(p.getUniqueId());
        final LivingEntity target = atk;
        Bukkit.getScheduler().runTask(this, () -> {
            if (!target.isValid() || target.isDead()) return;
            World w = target.getWorld(); Location tl = target.getLocation();
            trueDamage(target, p, 6.0);
            Vector kb = tl.toVector().subtract(p.getLocation().toVector()).setY(0);
            if (kb.lengthSquared() < 1e-4) kb = p.getLocation().getDirection().setY(0);
            kb.normalize().multiply(1.6).setY(0.45);
            target.setVelocity(kb);
            if (target instanceof Player tp) noKick(tp, 5000);
            sp(w, Particle.EXPLOSION, tl.clone().add(0, 1, 0), 2, .3, .3, .3);
            sp(w, Particle.END_ROD, tl.clone().add(0, 1, 0), 40, .5, .8, .5, 0.3);
            expandRing(p.getLocation(), 4, 8, l -> dustAt(l, 255, 205, 60, 1.7f, 1, 0.05));
            w.playSound(tl, Sound.ITEM_SHIELD_BREAK, 1f, 1.2f);
            w.playSound(tl, Sound.BLOCK_ANVIL_LAND, 1f, 1.5f);
            msg(p, Component.text("REPRISAL!", NamedTextColor.GOLD));
        });
    }

    void imperialDecree(Player p) {
        if (!"kings_plate".equals(id(p.getInventory().getChestplate()))) { msg(p, Component.text("Wear the King's Plate to use this", NamedTextColor.RED)); return; }
        if (!cd(p, "imperial_decree", 30)) return;
        decree.put(p.getUniqueId(), System.currentTimeMillis() + 5000);
        AttributeInstance kbi = p.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        AttributeModifier old = decreeMods.remove(p.getUniqueId());
        if (kbi != null) {
            if (old != null) kbi.removeModifier(old);
            AttributeModifier mod = new AttributeModifier(new NamespacedKey("dwarvenweapons", "decree_kb"), 1.0, AttributeModifier.Operation.ADD_NUMBER);
            try { kbi.addTransientModifier(mod); decreeMods.put(p.getUniqueId(), mod); } catch (IllegalArgumentException ignored) { }
        }
        World w = p.getWorld();
        w.playSound(p.getLocation(), Sound.ITEM_TOTEM_USE, 0.6f, 1.4f);
        w.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.2f);
        expandRing(p.getLocation(), 4, 8, l -> { dustAt(l, 255, 205, 60, 1.8f, 1, 0.05); dustAt(l, 140, 20, 40, 1.4f, 1, 0.05); });
        auraTask(p, 100, l -> { dustAt(l, 255, 205, 60, 1.4f, 1, 0.05); sp(w, Particle.END_ROD, l, 1, 0, 0, 0, 0.01); });
        msg(p, Component.text("IMPERIAL DECREE - 50% less damage and no knockback for 5s", NamedTextColor.GOLD));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            decree.remove(p.getUniqueId());
            AttributeModifier m2 = decreeMods.remove(p.getUniqueId());
            AttributeInstance k2 = p.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
            if (m2 != null && k2 != null) k2.removeModifier(m2);
        }, 100L);
    }

    void royalSlam(Player p) {
        if (!"kings_legguards".equals(id(p.getInventory().getLeggings()))) { msg(p, Component.text("Wear the King's Legguards to use this", NamedTextColor.RED)); return; }
        if (!cd(p, "royal_slam", 60)) return;
        World w = p.getWorld();
        noKick(p, 12000);
        p.setVelocity(new Vector(0, 1.6, 0));
        w.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1f, 0.7f);
        sp(w, Particle.EXPLOSION, p.getLocation(), 1);
        new BukkitRunnable() {
            int n = 0, slamTicks = 0;
            boolean slamming = false;
            @Override public void run() {
                if (!p.isOnline() || p.isDead() || n++ > 140) { cancel(); return; }
                Location b = p.getLocation();
                if (!slamming) {
                    dustAt(b, 255, 205, 60, 1.4f, 3, 0.3);
                    sp(w, Particle.END_ROD, b, 2, .3, .1, .3, 0.01);
                    if (n > 8 && p.getVelocity().getY() <= 0.05) {
                        slamming = true;
                        w.playSound(b, Sound.ENTITY_WITHER_SHOOT, 1f, 0.6f);
                    }
                } else {
                    p.setVelocity(new Vector(0, -3.0, 0));
                    slamTicks++;
                    column(b, 6, l -> dustAt(l, 255, 205, 60, 1.4f, 1, 0.1));
                    if (slamTicks > 2 && (p.isOnGround() || b.clone().subtract(0, 0.4, 0).getBlock().getType().isSolid())) {
                        cancel();
                        slamImpact(p);
                    }
                }
            }
        }.runTaskTimer(this, 0, 1);
    }

    void slamImpact(Player p) {
        World w = p.getWorld(); Location c = p.getLocation();
        for (Entity en : p.getNearbyEntities(10, 10, 10)) {
            if (!(en instanceof LivingEntity le) || en instanceof ArmorStand || ally(p, en)) continue;
            if (en.getLocation().distanceSquared(c) > 100) continue;
            trueDamage(le, p, 8.0);
            Vector away = en.getLocation().toVector().subtract(c.toVector()).setY(0);
            if (away.lengthSquared() < 1e-4) away = new Vector(0, 0, 1);
            away.normalize().multiply(0.9).setY(0.7);
            le.setVelocity(away);
            if (le instanceof Player kp) noKick(kp, 4000);
        }
        sp(w, Particle.EXPLOSION_EMITTER, c, 2, 1, 0.5, 1);
        sp(w, Particle.EXPLOSION, c.clone().add(0, 0.5, 0), 6, 3, .3, 3);
        expandRing(c, 10, 12, l -> { dustAt(l, 255, 205, 60, 2f, 1, 0.05); sp(w, Particle.CLOUD, l, 2, .3, .2, .3, 0.05); sp(w, Particle.FLAME, l, 1, .1, .1, .1, 0.02); });
        column(c, 8, l -> sp(w, Particle.END_ROD, l, 3, .8, .1, .8, 0.05));
        sp(w, Particle.BLOCK, c, 80, 2, .3, 2, 0, c.clone().subtract(0, 1, 0).getBlock().getBlockData());
        w.strikeLightningEffect(c);
        w.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.7f);
        w.playSound(c, Sound.BLOCK_ANVIL_LAND, 1f, 0.5f);
        msg(p, Component.text("ROYAL SLAM", NamedTextColor.GOLD));
    }

    void royalDash(Player p) {
        if (!"kings_step".equals(id(p.getInventory().getBoots()))) { msg(p, Component.text("Wear the King's Step to use this", NamedTextColor.RED)); return; }
        if (!cd(p, "royal_dash", 20)) return;
        Vector dir = p.getLocation().getDirection().normalize();
        Location cur = p.getLocation().clone();
        World w = p.getWorld();
        Set<UUID> hit = new HashSet<>();
        noKick(p, 6000);
        w.playSound(cur, Sound.ENTITY_ENDER_DRAGON_FLAP, 1f, 1.4f);
        w.playSound(cur, Sound.ITEM_TRIDENT_RIPTIDE_3, 1f, 1.2f);
        new BukkitRunnable() {
            int tick = 0;
            @Override public void run() {
                if (!p.isOnline() || tick++ >= 5) { cancel(); return; }
                boolean blocked = false;
                for (int st = 0; st < 8; st++) {                  // 8 x 0.5 = 4 blocks per tick -> 20 blocks in 5 ticks
                    Location next = cur.clone().add(dir.clone().multiply(0.5));
                    if (next.getBlock().getType().isSolid() || next.clone().add(0, 1, 0).getBlock().getType().isSolid()) { blocked = true; break; }
                    cur.setX(next.getX()); cur.setY(next.getY()); cur.setZ(next.getZ());
                    for (Entity en : w.getNearbyEntities(cur, 1.3, 1.3, 1.3)) {
                        if (en == p || !(en instanceof LivingEntity le) || en instanceof ArmorStand || ally(p, en)) continue;
                        if (hit.add(en.getUniqueId())) {
                            trueDamage(le, p, 4.0);
                            sp(w, Particle.CRIT, le.getLocation().add(0, 1, 0), 30, .4, .6, .4, 0.5);
                            sp(w, Particle.END_ROD, le.getLocation().add(0, 1, 0), 15, .3, .5, .3, 0.2);
                            w.playSound(le.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1f);
                        }
                    }
                    dustAt(cur.clone().add(0, 1, 0), 255, 205, 60, 1.5f, 2, 0.2);
                    if (st % 2 == 0) sp(w, Particle.END_ROD, cur.clone().add(0, 1, 0), 1, .1, .3, .1, 0.01);
                }
                Location dest = cur.clone();
                dest.setYaw(p.getLocation().getYaw()); dest.setPitch(p.getLocation().getPitch());
                p.teleport(dest);
                if (blocked) cancel();
            }
        }.runTaskTimer(this, 0, 1);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerHurt(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && "kings_legguards".equals(id(p.getInventory().getLeggings()))) {
            e.setCancelled(true);                                       // King's Legguards: no fall damage
            return;
        }
        long now = System.currentTimeMillis();
        Long d = decree.get(p.getUniqueId());
        if (d != null && d > now) e.setDamage(e.getDamage() * 0.5);
        Long w = voidWeak.get(p.getUniqueId());
        if (w != null && w > now) e.setDamage(e.getDamage() * 1.5);
        if (warps.containsKey(p.getUniqueId()) && p.getHealth() - e.getFinalDamage() <= 0) {   // Time Warp acts as a totem
            e.setCancelled(true);
            rewind(p, true);
        }
    }

    @EventHandler
    public void onQuitCleanup(PlayerQuitEvent e) { warps.remove(e.getPlayer().getUniqueId()); }

    void armourHud(Player p) {
        if (!getConfig().getBoolean("cooldown-hud", true)) return;
        Long hold = holdUntil.get(p.getUniqueId());
        if (hold != null && hold > System.currentTimeMillis()) return;
        PlayerInventory inv = p.getInventory();
        List<String[]> parts = new ArrayList<>();
        if (holdsShield(p)) parts.add(new String[]{"/armour 1 Reprisal", "imperial_reprisal"});
        if ("kings_plate".equals(id(inv.getChestplate()))) parts.add(new String[]{"/armour 2 Decree", "imperial_decree"});
        if ("kings_legguards".equals(id(inv.getLeggings()))) parts.add(new String[]{"/armour 3 Slam", "royal_slam"});
        if ("kings_step".equals(id(inv.getBoots()))) parts.add(new String[]{"/armour 4 Dash", "royal_dash"});
        if (parts.isEmpty()) return;
        Component c = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) c = c.append(Component.text("  |  ", NamedTextColor.DARK_GRAY));
            long rem = remaining(p, parts.get(i)[1]);
            c = c.append(rem > 0 ? Component.text(parts.get(i)[0] + " " + rem + "s", NamedTextColor.RED)
                                 : Component.text(parts.get(i)[0] + " READY", NamedTextColor.GREEN));
        }
        p.sendActionBar(c);
    }

    // ------------------------------------------------------------------ Chrono Blade
    void timeWarp(Player p) {
        if (warps.containsKey(p.getUniqueId())) return;
        if (!cd(p, "time_warp", 300)) return;
        WarpState ws = new WarpState();
        ws.loc = p.getLocation().clone(); ws.health = p.getHealth(); ws.food = p.getFoodLevel();
        warps.put(p.getUniqueId(), ws);
        noKick(p, 8000);
        World w = p.getWorld(); Location mark = ws.loc.clone();
        w.playSound(mark, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.4f);
        msg(p, Component.text("TIME WARP - this moment is marked. Returning in 5 seconds...", NamedTextColor.GOLD));
        new BukkitRunnable() {
            int n = 0;
            @Override public void run() {
                if (warps.get(p.getUniqueId()) != ws) { cancel(); return; }
                if (!p.isOnline()) { warps.remove(p.getUniqueId()); cancel(); return; }
                for (int i = 0; i < 28; i++) {
                    double a = i * Math.PI * 2 / 28 + n * 0.15;
                    dustAt(mark.clone().add(Math.cos(a) * 1.3, 0.1, Math.sin(a) * 1.3), 255, 210, 80, 1.2f, 1, 0.02);
                    dustAt(mark.clone().add(Math.cos(a) * 0.7, 0.1 + n * 0.02, Math.sin(a) * 0.7), 70, 110, 255, 1.0f, 1, 0.02);
                }
                column(mark, 3, l -> sp(w, Particle.END_ROD, l, 1, 0.1, 0, 0.1, 0.005));
                if (n % 10 == 0) w.playSound(mark, Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 0.6f + n / 40f);
                if (++n * 2 >= 100) { cancel(); rewind(p, false); }
            }
        }.runTaskTimer(this, 0, 2);
    }

    void rewind(Player p, boolean saved) {
        WarpState ws = warps.remove(p.getUniqueId());
        if (ws == null) return;
        World w = p.getWorld();
        Location from = p.getLocation().clone();
        beam(from.clone().add(0, 1, 0), ws.loc.clone().add(0, 1, 0), 0.5, l -> dustAt(l, 255, 210, 80, 1.2f, 1, 0.05));
        sp(w, Particle.PORTAL, from.clone().add(0, 1, 0), 60, .4, .8, .4, 0.8);
        noKick(p, 6000);
        p.teleport(ws.loc);
        AttributeInstance mh = p.getAttribute(Attribute.MAX_HEALTH);
        double max = mh != null ? mh.getValue() : 20.0;
        p.setHealth(Math.max(1.0, Math.min(max, ws.health)));
        p.setFoodLevel(ws.food); p.setFireTicks(0); p.setFallDistance(0f);
        World w2 = p.getWorld();
        sp(w2, Particle.TOTEM_OF_UNDYING, ws.loc.clone().add(0, 1, 0), saved ? 80 : 40, .5, 1, .5, 0.4);
        expandRing(ws.loc, 4, 8, l -> dustAt(l, 255, 210, 80, 1.6f, 1, 0.05));
        w2.playSound(ws.loc, saved ? Sound.ITEM_TOTEM_USE : Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        msg(p, Component.text(saved ? "TIME WARP saved you from death!" : "Time rewound.", NamedTextColor.GOLD));
    }

    void ageingStrike(Player p) {
        LivingEntity t = meleeTarget(p);
        if (t == null) { msg(p, Component.text("No target in reach", NamedTextColor.GRAY)); return; }
        if (!cd(p, "ageing_strike", 30)) return;
        t.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 400, 1));
        t.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 400, 1));
        World w = t.getWorld(); Location b = t.getLocation();
        w.playSound(b, Sound.BLOCK_BEACON_DEACTIVATE, 1f, 0.6f);
        w.playSound(b, Sound.ENTITY_WITHER_HURT, 0.8f, 0.7f);
        expandRing(b, 3, 8, l -> { dustAt(l, 170, 170, 150, 1.6f, 1, 0.05); dustAt(l, 255, 215, 90, 1.3f, 1, 0.05); });
        sp(w, Particle.SMOKE, b.clone().add(0, 1, 0), 40, .4, .8, .4, 0.05);
        sp(w, Particle.END_ROD, b.clone().add(0, 1, 0), 25, .4, .8, .4, 0.1);
        new BukkitRunnable() {          // an hourglass of grey sand falls over the aged target
            int n = 0;
            @Override public void run() {
                if (!t.isValid() || n++ >= 20) { cancel(); return; }
                Location head = t.getEyeLocation().add(0, 0.6, 0);
                dustAt(head, 170, 170, 150, 1.3f, 4, 0.3);
                dustAt(head.clone().add(0, -0.5, 0), 255, 215, 90, 1.0f, 2, 0.15);
            }
        }.runTaskTimer(this, 0, 5);
    }

    // ------------------------------------------------------------------ Soul Reaper
    void healthDrain(Player p) {
        List<Player> victims = new ArrayList<>();
        for (Entity en : p.getNearbyEntities(5, 5, 5)) {
            if (en instanceof Player v && !ally(p, v) && v.getGameMode() != GameMode.CREATIVE && v.getGameMode() != GameMode.SPECTATOR
                    && v.getLocation().distanceSquared(p.getLocation()) <= 25) victims.add(v);
        }
        if (victims.isEmpty()) { msg(p, Component.text("No players within 5 blocks", NamedTextColor.GRAY)); return; }
        if (!cd(p, "health_drain", 60)) return;
        World w = p.getWorld();
        Map<Player, AttributeModifier> taken = new HashMap<>();
        double gain = 0;
        for (Player v : victims) {
            AttributeInstance vi = v.getAttribute(Attribute.MAX_HEALTH);
            if (vi == null) continue;
            AttributeModifier mod = new AttributeModifier(new NamespacedKey("dwarvenweapons",
                    "drain_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)), -6.0, AttributeModifier.Operation.ADD_NUMBER);
            vi.addTransientModifier(mod);
            taken.put(v, mod);
            gain += 6.0;
            beam(v.getLocation().add(0, 1, 0), p.getLocation().add(0, 1, 0), 0.4, l -> { dustAt(l, 170, 60, 255, 1.4f, 1, 0.05); sp(w, Particle.SOUL, l, 1, 0, 0, 0, 0.01); });
            sp(w, Particle.SOUL, v.getLocation().add(0, 1, 0), 20, .4, .7, .4, 0.05);
        }
        gain = Math.min(gain, getConfig().getDouble("health-drain-max-gain", 20.0));
        AttributeInstance pi = p.getAttribute(Attribute.MAX_HEALTH);
        AttributeModifier mine = null;
        if (pi != null && gain > 0) {
            mine = new AttributeModifier(new NamespacedKey("dwarvenweapons",
                    "drain_self_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)), gain, AttributeModifier.Operation.ADD_NUMBER);
            pi.addTransientModifier(mine);
            p.setHealth(Math.min(pi.getValue(), p.getHealth() + gain));
        }
        final AttributeModifier fmine = mine;
        expandRing(p.getLocation(), 5, 8, l -> dustAt(l, 170, 60, 255, 1.6f, 1, 0.05));
        w.playSound(p.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 1f, 0.6f);
        msg(p, Component.text("HEALTH DRAIN - hearts stolen for 20 seconds", NamedTextColor.DARK_PURPLE));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (Map.Entry<Player, AttributeModifier> en : taken.entrySet()) {
                Player vv = en.getKey();
                AttributeInstance vi2 = vv.getAttribute(Attribute.MAX_HEALTH);
                if (vi2 != null) {
                    vi2.removeModifier(en.getValue());
                    if (!vv.isDead()) vv.setHealth(Math.min(vi2.getValue(), vv.getHealth() + 6.0));   // their hearts are returned
                }
            }
            if (fmine != null) { AttributeInstance pi2 = p.getAttribute(Attribute.MAX_HEALTH); if (pi2 != null) pi2.removeModifier(fmine); }
        }, 400L);
    }

    void lifeGiver(Player p) {
        if (!cd(p, "life_giver", 30)) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 200, 2));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, 20, 0));
        World w = p.getWorld();
        w.playSound(p.getLocation(), Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 1f, 1.2f);
        auraTask(p, 200, l -> { dustAt(l, 90, 255, 120, 1.3f, 1, 0.05); sp(w, Particle.SOUL, l, 1, 0, 0, 0, 0.02); });
        expandRing(p.getLocation(), 3, 8, l -> sp(w, Particle.HAPPY_VILLAGER, l, 2, .2, .3, .2));
        msg(p, Component.text("LIFE GIVER", NamedTextColor.GREEN));
    }

    // ------------------------------------------------------------------ Dragon's Rend
    void voidSlash(Player p) {
        if (!cd(p, "void_slash", 30)) return;
        World w = p.getWorld(); Location o = p.getLocation().clone();
        Vector fd = p.getLocation().getDirection().setY(0);
        if (fd.lengthSquared() < 1e-4) fd = new Vector(0, 0, 1);
        fd.normalize();
        final Vector fwd = fd.clone();
        final Vector side = new Vector(-fwd.getZ(), 0, fwd.getX());
        Set<UUID> hit = new HashSet<>();
        w.playSound(o, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.2f, 0.7f);
        long riftEnd = System.currentTimeMillis() + getConfig().getInt("rift-lifetime-seconds", 30) * 1000L;
        for (int i = 0; i < 28; i++) {                          // unstable rifts scattered through the slash
            double lat = (rng.nextDouble() * 2 - 1) * 19, dist = 3 + rng.nextDouble() * 26.5;
            double bow = (lat * lat) / 80.0;
            Rift r = new Rift(); r.owner = p.getUniqueId(); r.end = riftEnd;
            r.loc = o.clone().add(fwd.clone().multiply(Math.max(1, dist - bow))).add(side.clone().multiply(lat)).add(0, 0.6 + rng.nextDouble() * 1.6, 0);
            rifts.add(r);
        }
        new BukkitRunnable() {
            int step = 0;
            @Override public void run() {
                double d0 = step * 2.0;
                for (double lat = -20; lat <= 20; lat += 0.7) {
                    double bow = (lat * lat) / 80.0;
                    Location pt = o.clone().add(fwd.clone().multiply(d0 + 2.0 - bow)).add(side.clone().multiply(lat)).add(0, 1, 0);
                    dustAt(pt, 150, 60, 255, 1.9f, 1, 0.1);
                    sp(w, Particle.REVERSE_PORTAL, pt, 1, 0.1, 0.5, 0.1, 0.05);
                    if (((int) (lat * 10)) % 4 == 0) sp(w, Particle.DRAGON_BREATH, pt.clone().add(0, 0.5, 0), 1, 0.1, 0.1, 0.1, 0.01);
                }
                for (Entity en : w.getNearbyEntities(o.clone().add(fwd.clone().multiply(d0 + 1)), 23, 6, 23)) {
                    if (!(en instanceof LivingEntity le) || en == p || en instanceof ArmorStand || ally(p, en)) continue;
                    if (hit.contains(en.getUniqueId())) continue;
                    Vector rel = en.getLocation().toVector().subtract(o.toVector());
                    double f = rel.dot(fwd), lt = rel.dot(side), bw = (lt * lt) / 80.0;
                    if (Math.abs(lt) > 20 || f < d0 - bw || f > d0 + 2.0 - bw) continue;
                    hit.add(en.getUniqueId());
                    trueDamage(le, p, 8.0);
                    sp(w, Particle.REVERSE_PORTAL, le.getLocation().add(0, 1, 0), 40, .4, .8, .4, 0.5);
                    if (le instanceof Player kp) noKick(kp, 3000);
                }
                if (++step >= 15) cancel();
            }
        }.runTaskTimer(this, 0, 1);
    }

    void tickRifts() {
        long now = System.currentTimeMillis();
        Iterator<Rift> it = rifts.iterator();
        while (it.hasNext()) {
            Rift r = it.next();
            if (r.end < now || r.loc.getWorld() == null) { it.remove(); continue; }
            World w = r.loc.getWorld();
            sp(w, Particle.PORTAL, r.loc, 6, .35, .5, .35, 0.4);
            dustAt(r.loc, 150, 60, 255, 1.4f, 2, 0.3);
            sp(w, Particle.END_ROD, r.loc, 1, .3, .5, .3, 0.01);
            for (Entity en : w.getNearbyEntities(r.loc, 1.4, 1.6, 1.4)) {
                if (!(en instanceof Player v) || v.getUniqueId().equals(r.owner)) continue;
                Player owner = Bukkit.getPlayer(r.owner);
                if (owner != null && ally(owner, v)) continue;
                if (v.getGameMode() == GameMode.CREATIVE || v.getGameMode() == GameMode.SPECTATOR) continue;
                it.remove();
                trueDamage(v, owner, 2.0);
                PotionEffectType[] neg = {PotionEffectType.SLOWNESS, PotionEffectType.WEAKNESS, PotionEffectType.BLINDNESS, PotionEffectType.NAUSEA,
                        PotionEffectType.HUNGER, PotionEffectType.MINING_FATIGUE, PotionEffectType.WITHER, PotionEffectType.POISON, PotionEffectType.DARKNESS};
                v.addPotionEffect(new PotionEffect(neg[rng.nextInt(neg.length)], 400, 0));
                sp(w, Particle.REVERSE_PORTAL, r.loc, 50, .4, .6, .4, 0.6);
                sp(w, Particle.DRAGON_BREATH, r.loc, 20, .3, .4, .3, 0.05);
                w.playSound(r.loc, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.5f);
                break;
            }
        }
    }

    List<Block> sphereShell(Location c, int R) {
        World w = c.getWorld();
        List<Block> out = new ArrayList<>();
        for (int x = -R - 1; x <= R + 1; x++) for (int y = -R - 1; y <= R + 1; y++) for (int z = -R - 1; z <= R + 1; z++) {
            double d = Math.sqrt(x * x + y * y + z * z);
            if (d < R - 0.5 || d >= R + 0.5) continue;
            int by = c.getBlockY() + y, bx = c.getBlockX() + x, bz = c.getBlockZ() + z;
            if (by < w.getMinHeight() || by >= w.getMaxHeight() || !w.isChunkLoaded(bx >> 4, bz >> 4)) continue;
            Block b = w.getBlockAt(bx, by, bz);
            if (domeReplaceable(b)) out.add(b);
        }
        out.sort(Comparator.comparingInt(Block::getY));
        return out;
    }

    Material endOf(Material m, Random rnd) {
        String n = m.name();
        if (n.contains("END_") || n.contains("PURPUR") || n.equals("OBSIDIAN") || n.equals("BLACK_CONCRETE") || n.equals("BEDROCK")
                || n.contains("PORTAL") || n.contains("CHORUS")) return null;
        switch (m) {
            case WATER, BUBBLE_COLUMN, KELP, KELP_PLANT, SEAGRASS, TALL_SEAGRASS, SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN,
                 DEAD_BUSH, SNOW, VINE, SUGAR_CANE -> { return Material.AIR; }
            default -> { }
        }
        if (n.endsWith("_LOG") || n.endsWith("_WOOD") || n.endsWith("_PLANKS")) return Material.PURPUR_BLOCK;
        if (n.endsWith("_LEAVES")) return Material.END_STONE_BRICKS;
        if (m.isBlock() && m.isSolid() && m.isOccluding()) {
            int r = rnd.nextInt(100);
            return r < 8 ? Material.OBSIDIAN : (r < 18 ? Material.END_STONE_BRICKS : Material.END_STONE);
        }
        return null;
    }

    void endify(Location c, int R, World w, List<Block> changed) {
        Random rnd = new Random();
        for (int x = -R; x <= R; x++) for (int dy = -R; dy <= R; dy++) for (int dz = -R; dz <= R; dz++) {
            if (x * x + dy * dy + dz * dz > R * R) continue;
            int bx = c.getBlockX() + x, by = c.getBlockY() + dy, bz = c.getBlockZ() + dz;
            if (by < w.getMinHeight() || by >= w.getMaxHeight() || !w.isChunkLoaded(bx >> 4, bz >> 4)) continue;
            Block b = w.getBlockAt(bx, by, bz);
            Material t = b.getType();
            if (t.isAir() || dome.containsKey(b) || incinOrig.containsKey(b)) continue;
            if (t != Material.WATER && b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged()) {
                incinOrig.put(b, b.getBlockData()); incinSet.put(b, t); changed.add(b);
                wl.setWaterlogged(false); b.setBlockData(wl, false);
                continue;
            }
            Material to = endOf(t, rnd);
            if (to == null) continue;
            if (!to.isAir() && b.getState(false) instanceof TileState) continue;
            incinOrig.put(b, b.getBlockData()); incinSet.put(b, to); changed.add(b);
            b.setType(to, false);
        }
    }

    boolean domainBlocks(Player enemy) {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Domain> en : domains.entrySet()) {
            Domain d = en.getValue();
            if (d.end() < now || en.getKey().equals(enemy.getUniqueId())) continue;
            if (!d.center().getWorld().equals(enemy.getWorld())
                    || d.center().distanceSquared(enemy.getLocation()) > (double) d.radius() * d.radius()) continue;
            Player owner = Bukkit.getPlayer(en.getKey());
            if (owner != null && ally(owner, enemy)) continue;
            return true;
        }
        return false;
    }

    boolean inDomain(Location l) {
        long now = System.currentTimeMillis();
        for (Domain d : domains.values()) {
            if (d.end() < now || !d.center().getWorld().equals(l.getWorld())) continue;
            if (d.center().distanceSquared(l) <= (double) d.radius() * d.radius()) return true;
        }
        return false;
    }

    void dragonsDomain(Player p) {
        if (!cd(p, "dragons_domain", 120)) return;
        int R = 5;
        World w = p.getWorld(); Location c = p.getLocation().clone();
        domains.put(p.getUniqueId(), new Domain(c, R, System.currentTimeMillis() + 10_000));
        List<Block> shell = sphereShell(c, R);
        for (Block b : shell) {
            domeOrig.put(b, b.getBlockData());
            b.setType(Material.BLACK_CONCRETE, false);
            dome.put(b, Material.AIR);                          // unbreakable
        }
        Bukkit.getScheduler().runTaskLater(this, () -> restoreDome(shell), 200L);
        List<Block> changed = new ArrayList<>();
        endify(c, R - 1, w, changed);                           // everything inside turns End themed
        Bukkit.getScheduler().runTaskLater(this, () -> revertNether(changed), 200L);
        w.playSound(c, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.2f, 0.8f);
        w.playSound(c, Sound.BLOCK_END_PORTAL_SPAWN, 0.8f, 1.2f);
        expandRing(c, R + 2, 10, l -> { dustAt(l, 30, 15, 45, 2f, 1, 0.05); sp(w, Particle.DRAGON_BREATH, l, 1, .1, .2, .1, .01); });
        msg(p, Component.text("DRAGON'S DOMAIN", NamedTextColor.DARK_PURPLE));
        double dmg = getConfig().getDouble("domain-breath-damage", 1.0);
        new BukkitRunnable() {
            int n = 0;
            @Override public void run() {
                if (n++ >= 20) { cancel(); return; }                // 20 x 10 ticks = 10 seconds
                for (double fx = -R; fx <= R; fx += 0.9) for (double fz = -R; fz <= R; fz += 0.9) {
                    if (fx * fx + fz * fz > (R - 0.5) * (R - 0.5)) continue;
                    sp(w, Particle.DRAGON_BREATH, c.clone().add(fx, 0.15, fz), 1, 0.1, 0.05, 0.1, 0.01);
                }
                for (Entity en : w.getNearbyEntities(c, R, R, R)) {
                    if (en == p || !(en instanceof LivingEntity le) || en instanceof ArmorStand || ally(p, en)) continue;
                    if (le.getLocation().distanceSquared(c) > (double) R * R) continue;
                    trueDamage(le, p, dmg);                         // dragon's breath floor
                    sp(w, Particle.DRAGON_BREATH, le.getLocation().add(0, 0.3, 0), 4, .3, .2, .3, 0.02);
                }
                for (int k = 0; k < 12 && !shell.isEmpty(); k++) {
                    Block sb = shell.get(rng.nextInt(shell.size()));
                    dustAt(sb.getLocation().add(0.5, 0.5, 0.5), 150, 60, 255, 1.3f, 1, 0.3);
                }
            }
        }.runTaskTimer(this, 0, 10);
    }

    // ------------------------------------------------------------------ Axe of the Abyss
    void abyssalCleave(Player p) {
        if (!cd(p, "abyssal_cleave", 30)) return;
        World w = p.getWorld();
        Vector dir = p.getLocation().getDirection().normalize();
        Location start = p.getLocation().add(0, 1, 0);
        Vector side = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 1e-4) side = new Vector(1, 0, 0);
        side.normalize();
        final Vector fside = side;
        w.playSound(start, Sound.ENTITY_WITHER_SHOOT, 1f, 0.5f);
        w.playSound(start, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 0.5f);
        for (Entity en : w.getNearbyEntities(start.clone().add(dir.clone().multiply(12.5)), 14, 14, 14)) {
            if (en == p || !(en instanceof LivingEntity le) || en instanceof ArmorStand || ally(p, en)) continue;
            BoundingBox box = en.getBoundingBox().expand(1.5);
            if (box.rayTrace(start.toVector(), dir, 25) == null) continue;
            trueDamage(le, p, 6.0);
            le.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 100, 1));
            sp(w, Particle.SMOKE, le.getLocation().add(0, 1, 0), 40, .4, .8, .4, 0.08);
            sp(w, Particle.PORTAL, le.getLocation().add(0, 1, 0), 40, .4, .8, .4, 0.8);
        }
        new BukkitRunnable() {                                  // the slash races down the line
            int n = 0;
            @Override public void run() {
                if (n >= 6) { cancel(); return; }
                double d0 = n * 25.0 / 6, d1 = (n + 1) * 25.0 / 6;
                for (double d = d0; d < d1; d += 0.5) {
                    for (double off = -2.2; off <= 2.2; off += 0.4) {
                        Location pt = start.clone().add(dir.clone().multiply(d)).add(fside.clone().multiply(off)).add(0, Math.abs(off) * 0.25, 0);
                        dustAt(pt, 60, 20, 90, 1.9f, 1, 0.05);
                        if (((int) (off * 10)) % 8 == 0) sp(w, Particle.PORTAL, pt, 2, .1, .1, .1, 0.4);
                    }
                    sp(w, Particle.SMOKE, start.clone().add(dir.clone().multiply(d)), 2, .3, .3, .3, 0.02);
                }
                n++;
            }
        }.runTaskTimer(this, 0, 1);
    }

    void voidPower(Player p) {
        if (!cd(p, "void_power", 90)) return;
        World w = p.getWorld();
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 200, 1));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 2));
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 200, 1));
        w.strikeLightningEffect(p.getLocation());
        w.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 0.5f);
        expandRing(p.getLocation(), 8, 12, l -> { dustAt(l, 90, 25, 130, 2f, 1, 0.05); sp(w, Particle.PORTAL, l, 3, .2, .6, .2, 0.5); });
        auraTask(p, 200, l -> { dustAt(l, 100, 35, 150, 1.6f, 2, 0.1); sp(w, Particle.SMOKE, l, 1, .1, .1, .1, 0.02); });
        msg(p, Component.text("THE VOID EMPOWERS YOU", NamedTextColor.DARK_PURPLE));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 200, 1));
            p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 200, 0));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 200, 0));
            voidWeak.put(p.getUniqueId(), System.currentTimeMillis() + 10_000);      // +50% damage taken
            sp(w, Particle.SMOKE, p.getLocation().add(0, 1, 0), 60, .5, 1, .5, 0.05);
            msg(p, Component.text("The void leaves you weakened...", NamedTextColor.GRAY));
        }, 200L);
    }

    // ------------------------------------------------------------------ Divine Executioner
    void execution(Player p) {
        if (!cd(p, "execution", 30)) return;
        World w = p.getWorld(); Location base = p.getLocation().add(0, 1, 0);
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) dir = new Vector(0, 0, 1);
        dir.normalize();
        Vector side = new Vector(-dir.getZ(), 0, dir.getX());
        for (double ang = -1.2; ang <= 1.2; ang += 0.07) {
            Vector off = dir.clone().multiply(Math.cos(ang) * 5).add(side.clone().multiply(Math.sin(ang) * 5));
            Location pt = base.clone().add(off);
            dustAt(pt, 255, 215, 90, 2f, 2, 0.1);
            sp(w, Particle.END_ROD, pt, 1, .05, .3, .05, 0.02);
        }
        w.playSound(base, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.2f, 0.6f);
        w.playSound(base, Sound.ITEM_TRIDENT_THUNDER, 0.8f, 1.5f);
        for (Entity en : p.getNearbyEntities(6.5, 4, 6.5)) {
            if (en == p || !(en instanceof LivingEntity le) || en instanceof ArmorStand || ally(p, en)) continue;
            Vector rel = en.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
            double d = rel.length();
            if (d > 6.5 || (d > 0.01 && rel.clone().normalize().dot(dir) < 0.4)) continue;
            trueDamage(le, p, 6.0);
            sp(w, Particle.END_ROD, le.getLocation().add(0, 1, 0), 40, .4, .7, .4, 0.3);
            sp(w, Particle.TOTEM_OF_UNDYING, le.getLocation().add(0, 1, 0), 25, .4, .7, .4, 0.3);
            w.strikeLightningEffect(le.getLocation());
        }
    }

    void judgementsMark(Player p) {
        LivingEntity t = lookTarget(p, 30);
        if (t == null) { msg(p, Component.text("No target in sight", NamedTextColor.GRAY)); return; }
        if (!cd(p, "judgements_mark", 60)) return;
        t.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 400, 1));
        t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 400, 0));
        marks.put(t.getUniqueId(), new Mark(p.getUniqueId(), System.currentTimeMillis() + 20_000, 0.25));
        World w = t.getWorld();
        w.playSound(t.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.4f);
        beam(t.getLocation().add(0, 20, 0), t.getLocation(), 0.4, l -> { dustAt(l, 255, 220, 100, 1.8f, 2, 0.15); sp(w, Particle.END_ROD, l, 1, 0, 0, 0, 0.01); });
        expandRing(t.getLocation(), 3, 8, l -> dustAt(l, 255, 220, 100, 1.7f, 1, 0.05));
        msg(p, Component.text("JUDGEMENT'S MARK placed", NamedTextColor.GOLD));
        new BukkitRunnable() {                                  // a golden halo marks the judged target for 20 seconds
            int n = 0;
            @Override public void run() {
                if (!t.isValid() || t.isDead() || n++ >= 40) { cancel(); return; }
                Location head = t.getEyeLocation().add(0, 0.8, 0);
                for (int i = 0; i < 10; i++) {
                    double a = i * Math.PI / 5 + n * 0.4;
                    dustAt(head.clone().add(Math.cos(a) * 0.5, 0, Math.sin(a) * 0.5), 255, 220, 100, 1.1f, 1, 0.01);
                }
                sp(w, Particle.END_ROD, head, 1, .2, .1, .2, 0.01);
            }
        }.runTaskTimer(this, 0, 10);
    }

    // ------------------------------------------------------------------ Priest's Staff
    void inspire(Player p) {
        if (!cd(p, "inspire", 60)) return;
        List<Player> targets = new ArrayList<>();
        targets.add(p);
        for (Entity en : p.getNearbyEntities(20, 20, 20))
            if (en instanceof Player pl && ally(p, pl) && pl.getLocation().distanceSquared(p.getLocation()) <= 400) targets.add(pl);
        World w = p.getWorld();
        for (Player t : targets) {
            t.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 400, 2));
            t.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 400, 1));
            sp(t.getWorld(), Particle.END_ROD, t.getLocation().add(0, 1, 0), 40, .5, .9, .5, 0.1);
            column(t.getLocation(), 4, l -> dustAt(l, 255, 240, 170, 1.5f, 2, 0.3));
            t.playSound(t.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.3f);
            if (t != p) msg(t, Component.text("Inspired by " + p.getName() + "!", NamedTextColor.GOLD));
        }
        expandRing(p.getLocation(), 20, 16, l -> { dustAt(l, 255, 240, 170, 1.8f, 1, 0.05); sp(w, Particle.END_ROD, l, 1, 0, 0.2, 0, 0.02); });
        msg(p, Component.text("INSPIRE - " + targets.size() + " inspired", NamedTextColor.GOLD));
    }

    int offeringValue(Material m) {
        return switch (m) {
            case COAL, COAL_ORE, DEEPSLATE_COAL_ORE, RAW_COPPER, COPPER_INGOT, COPPER_ORE, DEEPSLATE_COPPER_ORE -> 1;
            case IRON_INGOT, RAW_IRON, IRON_ORE, DEEPSLATE_IRON_ORE -> 2;
            case GOLD_INGOT, RAW_GOLD, GOLD_ORE, DEEPSLATE_GOLD_ORE, NETHER_GOLD_ORE -> 4;
            case DIAMOND, DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE -> 5;
            case NETHERITE_INGOT, NETHERITE_SCRAP, ANCIENT_DEBRIS -> 10;
            case COAL_BLOCK, RAW_COPPER_BLOCK, COPPER_BLOCK -> 10;
            case IRON_BLOCK, RAW_IRON_BLOCK -> 20;
            case GOLD_BLOCK, RAW_GOLD_BLOCK -> 40;
            case DIAMOND_BLOCK -> 50;
            case NETHERITE_BLOCK -> 100;
            default -> 0;
        };
    }

    int sacPercent(Inventory inv) {
        int sum = 0;
        for (int i = 0; i < 18; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null) sum += offeringValue(it.getType()) * it.getAmount();
        }
        return Math.min(100, sum);
    }

    void updateSacButton(Inventory inv) {
        int pct = sacPercent(inv);
        ItemStack b = new ItemStack(Material.LIME_CONCRETE);
        ItemMeta m = b.getItemMeta();
        m.displayName(Component.text("CONFIRM - " + pct + "%", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Each percent = 1 durability point on every armor", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("piece worn by nearby enemies (max 100)", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        b.setItemMeta(m);
        inv.setItem(26, b);
    }

    void openSacrifice(Player p) {
        if (remaining(p, "sacrifice") > 0) { msg(p, Component.text("Sacrifice is on cooldown: " + remaining(p, "sacrifice") + "s", NamedTextColor.RED)); return; }
        Inventory inv = Bukkit.createInventory(null, 27, Component.text("Sacrifice to the Gods", NamedTextColor.GOLD));
        ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta pm = pane.getItemMeta();
        pm.displayName(Component.text(" "));
        pane.setItemMeta(pm);
        for (int i = 18; i < 26; i++) inv.setItem(i, pane);
        SacGui g = new SacGui();
        g.inv = inv;
        sacGuis.put(p.getUniqueId(), g);
        updateSacButton(inv);
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 0.8f);
    }

    void confirmSacrifice(Player p, SacGui g) {
        int pct = sacPercent(g.inv);
        if (pct <= 0) { msg(p, Component.text("Offer valuable ores first", NamedTextColor.GRAY)); return; }
        if (!cd(p, "sacrifice", 90, true)) { msg(p, Component.text("Sacrifice is on cooldown", NamedTextColor.RED)); return; }
        for (int i = 0; i < 18; i++) {                          // offerings are consumed, anything else is handed back on close
            ItemStack it = g.inv.getItem(i);
            if (it != null && offeringValue(it.getType()) > 0) g.inv.setItem(i, null);
        }
        p.closeInventory();
        double r = getConfig().getDouble("priest-sacrifice-radius", 15);
        World w = p.getWorld();
        int hits = 0;
        for (Entity en : p.getNearbyEntities(r, r, r)) {
            if (!(en instanceof LivingEntity le) || en instanceof ArmorStand || ally(p, en)) continue;
            if (!(le instanceof Player || le instanceof Enemy)) continue;
            if (en.getLocation().distanceSquared(p.getLocation()) > r * r) continue;
            drainAllArmor(le, pct);
            hits++;
            beam(p.getEyeLocation(), le.getLocation().add(0, 1, 0), 0.4, l -> { dustAt(l, 255, 235, 140, 1.5f, 1, 0.05); sp(w, Particle.END_ROD, l, 1, 0, 0, 0, 0.01); });
            sp(w, Particle.TOTEM_OF_UNDYING, le.getLocation().add(0, 1, 0), 25, .4, .7, .4, 0.3);
        }
        w.playSound(p.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1f, 1.4f);
        w.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        column(p.getLocation(), 12, l -> { dustAt(l, 255, 240, 170, 2f, 3, 0.4); sp(w, Particle.END_ROD, l, 1, .3, 0, .3, 0.02); });
        expandRing(p.getLocation(), r, 14, l -> dustAt(l, 255, 235, 140, 1.8f, 1, 0.05));
        msg(p, Component.text("SACRIFICE " + pct + "% - " + hits + " enemies' armor damaged", NamedTextColor.GOLD));
    }

    @EventHandler
    public void onSacClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        SacGui g = sacGuis.get(p.getUniqueId());
        if (g == null || e.getView().getTopInventory() != g.inv) return;
        int raw = e.getRawSlot();
        if (raw >= 18 && raw < 27) {
            e.setCancelled(true);
            if (raw == 26) Bukkit.getScheduler().runTask(this, () -> confirmSacrifice(p, g));
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> updateSacButton(g.inv));
    }

    @EventHandler
    public void onSacDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        SacGui g = sacGuis.get(p.getUniqueId());
        if (g == null || e.getView().getTopInventory() != g.inv) return;
        for (int raw : e.getRawSlots()) if (raw >= 18 && raw < 27) { e.setCancelled(true); return; }
        Bukkit.getScheduler().runTask(this, () -> updateSacButton(g.inv));
    }

    @EventHandler
    public void onSacClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        SacGui g = sacGuis.get(p.getUniqueId());
        if (g == null || e.getInventory() != g.inv) return;
        sacGuis.remove(p.getUniqueId());
        for (int i = 0; i < 18; i++) {
            ItemStack it = g.inv.getItem(i);
            if (it == null) continue;
            g.inv.setItem(i, null);
            p.getInventory().addItem(it).values().forEach(x -> p.getWorld().dropItemNaturally(p.getLocation(), x));
        }
    }

    // ------------------------------------------------------------------ Jackpot (Gambler's Sword)
    Location reelLoc(Player p, int i) {
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) dir = new Vector(0, 0, 1);
        dir.normalize();
        Vector right = new Vector(-dir.getZ(), 0, dir.getX());
        return p.getLocation().add(0, 2.7, 0).add(right.multiply((i - 1) * 0.9));
    }

    void addAbsorption(Player p, double hp, int ticks) {
        p.setAbsorptionAmount(p.getAbsorptionAmount() + hp);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) p.setAbsorptionAmount(Math.max(0, p.getAbsorptionAmount() - hp));
        }, ticks);
    }

    void luckyDraw(Player p) {
        if (!cd(p, "lucky_draw", 45)) return;
        int r = rng.nextInt(100);
        final int outcome = r < 15 ? 0 : (r < 45 ? 1 : (r < 75 ? 2 : 3));     // 15% emeralds, 30% gold, 30% diamonds, 25% skull
        final Material[] syms = {Material.EMERALD, Material.GOLD_INGOT, Material.DIAMOND, Material.SKELETON_SKULL};
        World w = p.getWorld();
        ItemDisplay[] reels = new ItemDisplay[3];
        for (int i = 0; i < 3; i++) {
            reels[i] = w.spawn(reelLoc(p, i), ItemDisplay.class, dsp -> {
                dsp.setItemStack(new ItemStack(Material.EMERALD));
                dsp.setBillboard(Display.Billboard.CENTER);
            });
        }
        new BukkitRunnable() {
            int n = 0;
            @Override public void run() {
                if (!p.isOnline()) { for (ItemDisplay rd : reels) rd.remove(); cancel(); return; }
                for (int i = 0; i < 3; i++) {
                    int stopAt = 14 + i * 6;
                    if (n < stopAt) reels[i].setItemStack(new ItemStack(syms[rng.nextInt(4)]));
                    else if (n == stopAt) {
                        reels[i].setItemStack(new ItemStack(syms[outcome]));
                        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 0.8f + i * 0.3f);
                    }
                    reels[i].teleport(reelLoc(p, i));
                }
                if (n % 2 == 0 && n < 26) p.getWorld().playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.6f);
                n++;
                if (n == 28) applyDraw(p, outcome);
                if (n > 48) { for (ItemDisplay rd : reels) rd.remove(); cancel(); }
            }
        }.runTaskTimer(this, 0, 2);
    }

    void applyDraw(Player p, int outcome) {
        World w = p.getWorld(); Location b = p.getLocation().add(0, 1, 0);
        switch (outcome) {
            case 0 -> {                                                        // 3 emeralds: JACKPOT
                p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 300, 2));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 300, 2));
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 300, 1));
                sp(w, Particle.HAPPY_VILLAGER, b, 60, 1, 1.2, 1, 0);
                sp(w, Particle.FIREWORK, b, 60, 1, 1.2, 1, 0.2);
                w.playSound(b, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                msg(p, Component.text("JACKPOT! Three emeralds", NamedTextColor.GREEN));
            }
            case 1 -> {                                                        // 3 gold: Big Win
                p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 300, 2));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 300, 1));
                addAbsorption(p, 6.0, 300);
                sp(w, Particle.ITEM, b, 60, .8, 1, .8, 0.2, new ItemStack(Material.GOLD_NUGGET));
                w.playSound(b, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                msg(p, Component.text("BIG WIN! Three gold", NamedTextColor.GOLD));
            }
            case 2 -> {                                                        // 3 diamonds: Fortune
                fortuneHits.put(p.getUniqueId(), 3);
                sp(w, Particle.END_ROD, b, 50, .8, 1, .8, 0.15);
                w.playSound(b, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.4f);
                msg(p, Component.text("FORTUNE! Your next 3 attacks deal +2 hearts true damage", NamedTextColor.AQUA));
            }
            default -> {                                                       // skull: Bust
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 400, 1));
                p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 400, 1));
                sp(w, Particle.SMOKE, b, 50, .6, 1, .6, 0.05);
                w.playSound(b, Sound.ENTITY_WITHER_HURT, 1f, 0.8f);
                msg(p, Component.text("BUST... Skull", NamedTextColor.DARK_GRAY));
            }
        }
    }

    void doubleOrNothing(Player p) {
        if (!cd(p, "double_or_nothing", 60)) return;
        World w = p.getWorld(); Location b = p.getLocation().add(0, 1, 0);
        if (rng.nextBoolean()) {
            int streak = doubleStreak.merge(p.getUniqueId(), 1, Integer::sum);
            if (streak >= 3) {                                                 // three Doubles in a row
                doubleStreak.remove(p.getUniqueId());
                jackpotBurst(p);
            } else {
                doubleHits.put(p.getUniqueId(), 5);
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 1));
                sp(w, Particle.ITEM, b, 40, .7, 1, .7, 0.2, new ItemStack(Material.GOLD_NUGGET));
                sp(w, Particle.FIREWORK, b, 25, .6, 1, .6, 0.1);
                w.playSound(b, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
                msg(p, Component.text("DOUBLE! (" + streak + "/3) Next 5 attacks deal 1.5x", NamedTextColor.GOLD));
            }
        } else {
            doubleStreak.remove(p.getUniqueId());
            sp(w, Particle.SMOKE, b, 30, .5, .8, .5, 0.03);
            w.playSound(b, Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
            msg(p, Component.text("Nothing...", NamedTextColor.GRAY));
        }
    }

    void jackpotBurst(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 200, 3));       // Strength IV
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 2));          // Speed III
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 200, 1));   // Regeneration II
        addAbsorption(p, 10.0, 200);                                                  // 5 hearts
        World w = p.getWorld(); Location b = p.getLocation().add(0, 1, 0);
        sp(w, Particle.ITEM, b, 140, 1.2, 1.5, 1.2, 0.3, new ItemStack(Material.GOLD_NUGGET));
        sp(w, Particle.FIREWORK, b, 80, 1, 1.5, 1, 0.2);
        sp(w, Particle.TOTEM_OF_UNDYING, b, 80, 1, 1.5, 1, 0.5);
        expandRing(p.getLocation(), 6, 12, l -> { dustAt(l, 255, 215, 60, 2f, 1, 0.05); sp(w, Particle.FIREWORK, l, 1, 0, 0.2, 0, 0.1); });
        w.playSound(b, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        w.playSound(b, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.2f, 1f);
        msg(p, Component.text("JACKPOT!!! Three doubles in a row", NamedTextColor.GOLD));
    }

    void houseEdge(Player p) {
        switch (rng.nextInt(5)) {
            case 0 -> { p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 2)); msg(p, Component.text("House Edge: Speed III", NamedTextColor.GOLD)); }
            case 1 -> { p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 2)); msg(p, Component.text("House Edge: Regeneration III", NamedTextColor.GOLD)); }
            case 2 -> { p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 200, 4)); msg(p, Component.text("House Edge: Absorption V", NamedTextColor.GOLD)); }
            case 3 -> { houseBonus.put(p.getUniqueId(), 1); msg(p, Component.text("House Edge: your next hit deals +1 heart", NamedTextColor.GOLD)); }
            default -> msg(p, Component.text("House Edge: nothing this time", NamedTextColor.GRAY));
        }
        sp(p.getWorld(), Particle.ITEM, p.getLocation().add(0, 1.5, 0), 12, .4, .4, .4, 0.1, new ItemStack(Material.GOLD_NUGGET));
    }

    // ------------------------------------------------------------------ melee-range targeting (Ten Ton Axe, Chrono Blade)
    LivingEntity meleeTarget(Player p) {
        LivingEntity t = lookTarget(p, 6);
        if (t != null) return t;
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) return null;
        dir.normalize();
        LivingEntity best = null;
        double bd = 99;
        for (Entity en : p.getNearbyEntities(3.5, 2.5, 3.5)) {
            if (!(en instanceof LivingEntity le) || en instanceof ArmorStand || ally(p, en)) continue;
            Vector rel = en.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
            double d = rel.length();
            if (d < 0.01 || d > 3.5) continue;
            if (rel.clone().normalize().dot(dir) < 0.35) continue;     // in front of you, about 70 degrees to each side
            if (d < bd) { bd = d; best = le; }
        }
        return best;
    }

    // ------------------------------------------------------------------ visual effects + HUD
    void sp(World w, Particle p, Location l, int n) { sp(w, p, l, n, 0, 0, 0, 0); }
    void sp(World w, Particle p, Location l, int n, double dx, double dy, double dz) { sp(w, p, l, n, dx, dy, dz, 0); }
    void sp(World w, Particle p, Location l, int n, double dx, double dy, double dz, double speed) {
        try { w.spawnParticle(p, l, n, dx, dy, dz, speed); }
        catch (Exception ex) {
            if (p == Particle.DRAGON_BREATH) { try { w.spawnParticle(p, l, n, dx, dy, dz, speed, 1.0f); } catch (Exception ignored) { } }
        }
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

    boolean isVanillaCrit(Player p) {
        return p.getFallDistance() > 0 && !p.isOnGround() && !p.isInWater() && !p.isClimbing()
                && !p.isInsideVehicle() && !p.isSprinting() && !p.hasPotionEffect(PotionEffectType.BLINDNESS);
    }

    void recordMace(Player p, String id) {
        lastMaceId.put(p.getUniqueId(), id);
        lastMaceTime.put(p.getUniqueId(), System.currentTimeMillis());
    }

    String recentMace(Player p) {          // a mace held in the last 0.8s still counts (attribute swapping)
        Long t = lastMaceTime.get(p.getUniqueId());
        String m = lastMaceId.get(p.getUniqueId());
        if (t == null || m == null || System.currentTimeMillis() - t > 800) return null;
        return hasWeapon(p, m) ? m : null;
    }

    @EventHandler
    public void onHeldChange(PlayerItemHeldEvent e) {
        String prev = id(e.getPlayer().getInventory().getItem(e.getPreviousSlot()));
        if ("divine_judgement".equals(prev) || "hammer_of_the_void".equals(prev)) recordMace(e.getPlayer(), prev);
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
            case "chrono_blade" -> { dustAt(l, 255, 215, 90, 1.4f, 14, 0.4); dustAt(l, 80, 110, 255, 1.2f, 8, 0.4); sp(w, Particle.END_ROD, l, 8, .3, .5, .3, 0.1); }
            case "soul_reaper" -> { sp(w, Particle.SOUL, l, 8, .3, .5, .3, 0.05); dustAt(l, 170, 60, 255, 1.4f, 12, 0.4); }
            case "dragons_rend" -> { dustAt(l, 150, 60, 255, 1.5f, 14, 0.4); sp(w, Particle.REVERSE_PORTAL, l, 25, .4, .5, .4, 0.3); sp(w, Particle.DRAGON_BREATH, l, 6, .3, .4, .3, 0.02); }
            case "axe_of_abyss" -> { dustAt(l, 60, 20, 90, 1.8f, 14, 0.4); sp(w, Particle.SMOKE, l, 10, .3, .5, .3, 0.05); sp(w, Particle.PORTAL, l, 20, .4, .5, .4, 0.6); }
            case "divine_executioner" -> { dustAt(l, 255, 220, 100, 1.5f, 14, 0.4); sp(w, Particle.END_ROD, l, 14, .3, .5, .3, 0.15); sp(w, Particle.TOTEM_OF_UNDYING, l, 4, .3, .5, .3, 0.2); }
            case "priests_staff" -> { dustAt(l, 255, 240, 170, 1.4f, 12, 0.4); sp(w, Particle.END_ROD, l, 10, .3, .5, .3, 0.1); sp(w, Particle.HAPPY_VILLAGER, l, 5, .3, .5, .3, 0); }
            case "gamblers_sword" -> { dustAt(l, 220, 20, 40, 1.4f, 10, 0.4); dustAt(l, 255, 215, 60, 1.2f, 8, 0.4); sp(w, Particle.ITEM, l, 8, .3, .5, .3, 0.15, new ItemStack(Material.GOLD_NUGGET)); }
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
            case "chrono_blade" -> { dustAt(h, 255, 215, 90, 0.9f, 2, 0.6); dustAt(h, 80, 110, 255, 0.8f, 1, 0.6); }
            case "soul_reaper" -> { sp(w, Particle.SOUL, h, 1, .5, .6, .5, 0.02); dustAt(h, 170, 60, 255, 0.9f, 2, 0.6); }
            case "dragons_rend" -> { sp(w, Particle.REVERSE_PORTAL, h, 2, .5, .7, .5, 0.2); dustAt(h, 150, 60, 255, 0.9f, 2, 0.6); }
            case "axe_of_abyss" -> { sp(w, Particle.SMOKE, h, 2, .5, .7, .5, 0.02); dustAt(h, 80, 25, 120, 1.0f, 2, 0.6); }
            case "divine_executioner", "priests_staff" -> { sp(w, Particle.END_ROD, h, 1, .5, .7, .5, 0.01); dustAt(h, 255, 230, 140, 0.9f, 2, 0.6); }
            case "gamblers_sword" -> { dustAt(h, 220, 30, 50, 0.9f, 1, 0.6); dustAt(h, 255, 215, 60, 0.8f, 1, 0.6); }
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
            case "bloody_eclipse" -> new String[][]{{"F Bloodbath", "bloodbath"}, {"Shift+F Eclipse", "blinding_eclipse"}, {"Bleed", "bleed"}};
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
            case "mad_scientists_crossbow" -> new String[][]{{"Grapple", "grapple"}};
            case "chrono_blade" -> new String[][]{{"F Time Warp", "time_warp"}, {"Shift+F Ageing", "ageing_strike"}};
            case "soul_reaper" -> new String[][]{{"F Drain", "health_drain"}, {"Shift+F Life", "life_giver"}};
            case "dragons_rend" -> new String[][]{{"F Void Slash", "void_slash"}, {"Shift+F Domain", "dragons_domain"}};
            case "axe_of_abyss" -> new String[][]{{"F Cleave", "abyssal_cleave"}, {"Shift+F Void Power", "void_power"}};
            case "divine_executioner" -> new String[][]{{"F Execution", "execution"}, {"Shift+F Mark", "judgements_mark"}};
            case "priests_staff" -> new String[][]{{"F Inspire", "inspire"}, {"Shift+F Sacrifice", "sacrifice"}};
            case "gamblers_sword" -> new String[][]{{"F Lucky Draw", "lucky_draw"}, {"Shift+F Double/Nothing", "double_or_nothing"}};
            default -> new String[0][];
        };
    }

    int[] keyColor(String key) {
        return switch (key) {
            case "bloodbath" -> new int[]{220, 15, 30};
            case "blinding_eclipse" -> new int[]{120, 40, 190};
            case "lightning_storm", "tides_call" -> new int[]{110, 200, 255};
            case "call_of_the_deep" -> new int[]{170, 170, 160};
            case "stunning_strike", "durability_drain" -> new int[]{255, 120, 50};
            case "void_walk", "pull_of_the_void", "void_throw" -> new int[]{160, 70, 255};
            case "acidic_blaze", "incineration" -> new int[]{255, 150, 30};
            case "sculk_beams", "summon_warden", "live_for_the_hunt" -> new int[]{40, 210, 230};
            case "royal_judgement", "piercing_strike", "smite" -> new int[]{255, 205, 60};
            case "temporal_cleave", "temporal_dismemberment" -> new int[]{50, 230, 100};
            case "imperial_reprisal", "imperial_decree", "royal_slam", "royal_dash" -> new int[]{255, 205, 60};
            case "time_warp" -> new int[]{255, 215, 90};
            case "ageing_strike" -> new int[]{170, 170, 150};
            case "health_drain" -> new int[]{170, 60, 255};
            case "life_giver" -> new int[]{90, 255, 120};
            case "void_slash", "dragons_domain" -> new int[]{150, 60, 255};
            case "abyssal_cleave", "void_power" -> new int[]{110, 40, 160};
            case "execution", "judgements_mark" -> new int[]{255, 220, 100};
            case "inspire", "sacrifice" -> new int[]{255, 240, 170};
            case "lucky_draw", "double_or_nothing" -> new int[]{230, 40, 60};
            default -> new int[]{255, 255, 255};
        };
    }

    void castBurst(Player p, String key) {
        int[] c = keyColor(key);
        World w = p.getWorld(); Location b = p.getLocation();
        for (int i = 0; i < 48; i++) {           // rising double spiral
            double a = i * 0.45;
            dustAt(b.clone().add(Math.cos(a) * 1.1, i * 0.05, Math.sin(a) * 1.1), c[0], c[1], c[2], 1.5f, 1, 0.02);
            dustAt(b.clone().add(Math.cos(a + Math.PI) * 1.1, i * 0.05, Math.sin(a + Math.PI) * 1.1), c[0], c[1], c[2], 1.5f, 1, 0.02);
        }
        sp(w, Particle.END_ROD, b.clone().add(0, 1, 0), 40, .6, .9, .6, 0.1);
        sp(w, Particle.FIREWORK, b.clone().add(0, 1, 0), 20, .5, .8, .5, 0.1);
        expandRing(b, 4, 8, l -> dustAt(l, c[0], c[1], c[2], 1.7f, 1, 0.02));
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
        if ("kings_step".equals(id(p.getInventory().getBoots())))
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 2, true, false, true));       // Speed III
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
        if (id == null || id.startsWith("kings_") || id.equals("mad_scientists_crossbow")) return;
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
            case "chrono_blade" -> { if (sh) ageingStrike(p); else timeWarp(p); }
            case "soul_reaper" -> { if (sh) lifeGiver(p); else healthDrain(p); }
            case "dragons_rend" -> { if (sh) dragonsDomain(p); else voidSlash(p); }
            case "axe_of_abyss" -> { if (sh) voidPower(p); else abyssalCleave(p); }
            case "divine_executioner" -> { if (sh) judgementsMark(p); else execution(p); }
            case "priests_staff" -> { if (sh) openSacrifice(p); else inspire(p); }
            case "gamblers_sword" -> { if (sh) doubleOrNothing(p); else luckyDraw(p); }
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
            if (!(en instanceof LivingEntity le) || en instanceof ArmorStand || ally(p, en)) continue;
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
        if (e.getEntity() instanceof WindCharge w && w.getShooter() instanceof Player p && (isEclipsed(p) || domainBlocks(p))) {
            e.setCancelled(true);
            msg(p, Component.text("Wind charges are disabled by the eclipse!", NamedTextColor.RED));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCobweb(BlockPlaceEvent e) {
        if (e.getBlock().getType() == Material.COBWEB && (isEclipsed(e.getPlayer()) || domainBlocks(e.getPlayer()))) {
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
                trueDamage(t, p, 2.0);
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
                    if (!(en instanceof LivingEntity le) || en instanceof ArmorStand || ally(p, en)) continue;
                    if (en.getLocation().distanceSquared(p.getLocation()) > 400) continue;
                    Vector v = p.getLocation().toVector().subtract(en.getLocation().toVector());
                    if (v.lengthSquared() > 9) en.setVelocity(v.normalize().multiply(0.7).setY(0.12));   // fast pull
                    if (en instanceof Player kp) noKick(kp, 4000);
                    if (!(en instanceof WaterMob) && !(en instanceof Drowned) && !le.hasPotionEffect(PotionEffectType.WATER_BREATHING))
                        le.setRemainingAir(Math.max(-20, le.getRemainingAir() - 12));
                    sp(w, Particle.BUBBLE_POP, en.getLocation().add(0, 1, 0), 5, .3, .5, .3);
                    if (n % 3 == 0) beam(en.getLocation().add(0, 1, 0), p.getLocation().add(0, 1, 0), 1.3, l -> sp(w, Particle.BUBBLE_POP, l, 1));
                }
            }
        }.runTaskTimer(this, 0, 2);
    }

    // ---- Dwarven Pickaxe
    boolean domeReplaceable(Block b) {
        Material t = b.getType();
        if (dome.containsKey(b)) return false;
        switch (t) {
            case BEDROCK, BARRIER, END_PORTAL_FRAME, END_PORTAL, NETHER_PORTAL, END_GATEWAY, COMMAND_BLOCK, CHAIN_COMMAND_BLOCK,
                 REPEATING_COMMAND_BLOCK, STRUCTURE_BLOCK, JIGSAW, SPAWNER, REINFORCED_DEEPSLATE -> { return false; }
            default -> { }
        }
        return t.isAir() || !(b.getState(false) instanceof TileState);   // chests, signs, etc. keep their contents
    }

    void restoreDome(List<Block> order) {
        new BukkitRunnable() {
            int i = order.size() - 1;
            @Override public void run() {
                for (int n = 0; n < 400 && i >= 0; n++, i--) {
                    Block b = order.get(i);
                    if (dome.remove(b) == null) continue;
                    BlockData od = domeOrig.remove(b);
                    if (od != null) b.setBlockData(od, false);
                }
                if (i < 0) cancel();
            }
        }.runTaskTimer(this, 0, 1);
    }

    void callOfTheDeep(Player p) {
        if (!cd(p, "call_of_the_deep", 120)) return;
        int R = getConfig().getInt("dome-radius", 10);
        Location c = p.getLocation(); World w = c.getWorld(); Random rnd = new Random();
        List<Block> shell = new ArrayList<>(), floor = new ArrayList<>();
        for (int x = -R - 1; x <= R + 1; x++) for (int y = -R - 1; y <= R + 1; y++) for (int z = -R - 1; z <= R + 1; z++) {
            double d = Math.sqrt(x * x + y * y + z * z);
            if (d < R - 0.5 || d >= R + 0.5) continue;
            int by = c.getBlockY() + y, bx = c.getBlockX() + x, bz = c.getBlockZ() + z;
            if (by < w.getMinHeight() || by >= w.getMaxHeight() || !w.isChunkLoaded(bx >> 4, bz >> 4)) continue;
            Block b = w.getBlockAt(bx, by, bz);
            if (domeReplaceable(b)) shell.add(b);          // terrain, water, anything: it all becomes dome
        }
        for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {   // 3-layer floor under the holder, over air only
            if (dx * dx + dz * dz > R * R) continue;
            for (int layer = 1; layer <= 3; layer++) {
                int by = c.getBlockY() - layer, bx = c.getBlockX() + dx, bz = c.getBlockZ() + dz;
                if (by < w.getMinHeight() || !w.isChunkLoaded(bx >> 4, bz >> 4)) break;
                Block fb = w.getBlockAt(bx, by, bz);
                if (fb.getType().isAir() && !dome.containsKey(fb)) floor.add(fb);
            }
        }
        shell.sort(Comparator.comparingInt(Block::getY));      // the dome grows from the bottom up
        List<Block> order = new ArrayList<>(floor);
        order.addAll(shell);
        w.playSound(c, Sound.BLOCK_ANVIL_LAND, 1f, 0.5f);
        w.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.5f);
        expandRing(c, R, 16, l -> { sp(w, Particle.CLOUD, l, 2, .3, .2, .3, 0.02); sp(w, Particle.BLOCK, l, 3, .3, .2, .3, 0, Material.STONE.createBlockData()); });
        column(c, 6, l -> sp(w, Particle.CLOUD, l, 2, .5, .1, .5, 0.02));
        msg(p, Component.text("CALL OF THE DEEP", NamedTextColor.GRAY));
        new BukkitRunnable() {
            int i = 0;
            @Override public void run() {
                for (int n = 0; n < 220 && i < order.size(); n++, i++) {
                    Block b = order.get(i);
                    domeOrig.put(b, b.getBlockData());
                    Material m = rnd.nextBoolean() ? Material.DIRT : Material.STONE;
                    b.setType(m, false);
                    dome.put(b, Material.AIR);
                    if (i % 9 == 0) sp(w, Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 6, .3, .3, .3, 0, m.createBlockData());
                    if (i % 70 == 0) w.playSound(b.getLocation(), Sound.BLOCK_STONE_PLACE, 1f, 0.6f);
                }
                if (i >= order.size()) cancel();
            }
        }.runTaskTimer(this, 0, 1);
        Bukkit.getScheduler().runTaskLater(this, () -> restoreDome(order), 1200L);
        new BukkitRunnable() {          // everything hostile inside is slowed (Slowness II); the walls shimmer
            int n = 0;
            @Override public void run() {
                if (n++ >= 60) { cancel(); return; }                 // 60 x 1 second = the dome's lifetime
                for (Entity en : w.getNearbyEntities(c, R, R, R)) {
                    if (en == p || !(en instanceof LivingEntity le) || en instanceof ArmorStand || ally(p, en)) continue;
                    if (!(le instanceof Player || le instanceof Enemy)) continue;
                    if (le.getLocation().distanceSquared(c) > (double) R * R) continue;
                    le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1, true, true, true));
                    dustAt(le.getLocation().add(0, 1, 0), 130, 130, 130, 1.2f, 6, 0.4);
                    sp(w, Particle.CRIT, le.getLocation().add(0, 0.2, 0), 3, .3, .1, .3, 0.02);
                }
                for (int k = 0; k < 40 && !order.isEmpty(); k++) {
                    Block b = order.get(rnd.nextInt(order.size()));
                    sp(w, Particle.ENCHANT, b.getLocation().add(0.5, 0.5, 0.5), 2, .3, .3, .3, 0.3);
                }
            }
        }.runTaskTimer(this, 20, 20);
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
            noKick(pl, ticks * 50L + 5000);
            stunned.put(pl.getUniqueId(), System.currentTimeMillis() + ticks * 50L);
            pl.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, ticks, 128, false, false));
        } else if (t instanceof Mob m) {
            m.setAware(false);
            Bukkit.getScheduler().runTaskLater(this, () -> { if (m.isValid()) m.setAware(true); }, ticks);
        }
    }

    void stunningStrike(Player p) {
        LivingEntity t = meleeTarget(p);
        if (t == null) { msg(p, Component.text("No target in reach", NamedTextColor.GRAY)); return; }
        if (!cd(p, "stunning_strike", 30)) return;
        stun(t, 60);
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
        LivingEntity t = meleeTarget(p);
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
    public void onStunInteract(PlayerInteractEvent e) {
        if (!isStunned(e.getPlayer())) return;
        ItemStack it = e.getItem();
        if (it != null) {
            Material m = it.getType();       // golden apples and potions still work while frozen or stunned
            if (m == Material.GOLDEN_APPLE || m == Material.ENCHANTED_GOLDEN_APPLE || m == Material.POTION
                    || m == Material.SPLASH_POTION || m == Material.LINGERING_POTION) return;
        }
        e.setCancelled(true);
    }

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
                en -> en != p && en instanceof LivingEntity && !(en instanceof ArmorStand) && !en.isDead() && !ally(p, en));
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
        if (t instanceof Player kp) noKick(kp, 4000);
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

    Material netherOf(Material m, Random rnd) {
        String n = m.name();
        if (n.contains("NETHER") || n.contains("CRIMSON") || n.contains("WARPED") || n.contains("BLACKSTONE") || n.contains("BASALT")
                || n.equals("MAGMA_BLOCK") || n.equals("SOUL_SAND") || n.equals("SOUL_SOIL") || n.equals("GLOWSTONE")) return null;
        switch (m) {
            case WATER, BUBBLE_COLUMN, KELP, KELP_PLANT, SEAGRASS, TALL_SEAGRASS, SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN,
                 DEAD_BUSH, SNOW, VINE, SUGAR_CANE -> { return Material.AIR; }
            case GRASS_BLOCK, PODZOL, MYCELIUM -> { return rnd.nextInt(4) == 0 ? Material.WARPED_NYLIUM : Material.CRIMSON_NYLIUM; }
            case DIRT, COARSE_DIRT, ROOTED_DIRT, FARMLAND, DIRT_PATH, MUD, CLAY, MOSS_BLOCK, SNOW_BLOCK -> { return Material.NETHERRACK; }
            case SAND, RED_SAND, SUSPICIOUS_SAND -> { return Material.SOUL_SAND; }
            case GRAVEL, SUSPICIOUS_GRAVEL -> { return Material.SOUL_SOIL; }
            case ICE, PACKED_ICE, BLUE_ICE, FROSTED_ICE -> { return Material.MAGMA_BLOCK; }
            case DEEPSLATE, COBBLED_DEEPSLATE, TUFF, CALCITE -> { return Material.BLACKSTONE; }
            case STONE, COBBLESTONE, MOSSY_COBBLESTONE, ANDESITE, DIORITE, GRANITE, SANDSTONE, RED_SANDSTONE, SMOOTH_SANDSTONE,
                 DRIPSTONE_BLOCK, STONE_BRICKS, MOSSY_STONE_BRICKS -> {
                int r = rnd.nextInt(100);
                return r < 6 ? Material.MAGMA_BLOCK : (r < 8 ? Material.GLOWSTONE : Material.NETHERRACK);
            }
            default -> { }
        }
        if (n.endsWith("_ORE")) return Material.NETHER_QUARTZ_ORE;
        if (n.endsWith("_LOG") || n.endsWith("_WOOD")) return rnd.nextBoolean() ? Material.CRIMSON_STEM : Material.WARPED_STEM;
        if (n.endsWith("_PLANKS")) return Material.CRIMSON_PLANKS;
        if (n.endsWith("_LEAVES")) return rnd.nextBoolean() ? Material.NETHER_WART_BLOCK : Material.WARPED_WART_BLOCK;
        return null;
    }

    void netherize(Location c, int R, World w, List<Block> changed) {
        int cx = c.getBlockX(), cy = c.getBlockY(), cz = c.getBlockZ();
        new BukkitRunnable() {
            int x = -R, sample = 0;
            final Random rnd = new Random();
            @Override public void run() {
                for (int n = 0; n < 4 && x <= R; n++, x++) {
                    for (int dz = -R; dz <= R; dz++) for (int dy = -R; dy <= R; dy++) {
                        if (x * x + dy * dy + dz * dz > R * R) continue;
                        int bx = cx + x, by = cy + dy, bz = cz + dz;
                        if (by < w.getMinHeight() || by >= w.getMaxHeight() || !w.isChunkLoaded(bx >> 4, bz >> 4)) continue;
                        Block b = w.getBlockAt(bx, by, bz);
                        Material t = b.getType();
                        if (t.isAir() || dome.containsKey(b) || incinOrig.containsKey(b)) continue;
                        if (t != Material.WATER && b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged()) {
                            incinOrig.put(b, b.getBlockData()); incinSet.put(b, t); changed.add(b);
                            wl.setWaterlogged(false); b.setBlockData(wl, false);
                            continue;
                        }
                        Material to = netherOf(t, rnd);
                        if (to == null) continue;
                        incinOrig.put(b, b.getBlockData()); incinSet.put(b, to); changed.add(b);
                        b.setType(to, false);
                        if (sample++ % 7 == 0) {
                            sp(w, Particle.FLAME, b.getLocation().add(0.5, 1, 0.5), 2, .3, .2, .3, 0.02);
                            if (sample % 21 == 0) sp(w, Particle.ASH, b.getLocation().add(0.5, 1, 0.5), 6, .5, .5, .5, 0);
                        }
                    }
                }
                if (x > R) cancel();
            }
        }.runTaskTimer(this, 0, 1);
    }

    void revertNether(List<Block> changed) {
        new BukkitRunnable() {
            int i = changed.size() - 1;
            @Override public void run() {
                for (int n = 0; n < 500 && i >= 0; n++, i--) {
                    Block b = changed.get(i);
                    BlockData od = incinOrig.remove(b);
                    Material st = incinSet.remove(b);
                    if (od == null || st == null) continue;
                    if (b.getType() == st) b.setBlockData(od, false);       // only if nobody changed it meanwhile
                }
                if (i < 0) cancel();
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
        List<Block> changed = new ArrayList<>();
        netherize(c, R, w, changed);              // the whole area turns into Nether blocks...
        Bukkit.getScheduler().runTaskLater(this, () -> {      // ...and changes back when the 20 seconds are over
            w.playSound(c, Sound.BLOCK_FIRE_EXTINGUISH, 1.5f, 0.7f);
            expandRing(c, R, 16, l -> sp(w, Particle.CLOUD, l, 2, .3, .3, .3, 0.03));
            revertNether(changed);
        }, 400L);
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
        if (e.getBlock().getType() == Material.WATER && (inIncin(e.getBlock().getLocation()) || inIncin(e.getToBlock().getLocation())
                || inDomain(e.getBlock().getLocation()) || inDomain(e.getToBlock().getLocation()))) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (e.getBucket() == Material.WATER_BUCKET && (inIncin(e.getBlock().getLocation()) || domainBlocks(e.getPlayer()))) e.setCancelled(true);
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
        if (ally(p, victim)) return;            // trusted players: no weapon effects on them

        // Inferno - Incineration: players in the area take 1.25x damage from the wielder
        Incin in = incin.get(p.getUniqueId());
        if (in != null && in.end() > System.currentTimeMillis() && victim instanceof Player
                && p.getWorld().equals(victim.getWorld())
                && victim.getLocation().distanceSquared(in.center()) <= (double) in.radius() * in.radius())
            e.setDamage(e.getDamage() * 1.25);

        Domain dmn = domains.get(p.getUniqueId());
        if (dmn != null && dmn.end() > System.currentTimeMillis() && p.getWorld().equals(victim.getWorld())
                && victim.getLocation().distanceSquared(dmn.center()) <= (double) dmn.radius() * dmn.radius())
            e.setDamage(e.getDamage() * 1.25);                       // Dragon's Domain: 1.25x damage to enemies inside

        Mark mk = marks.get(victim.getUniqueId());
        if (mk != null && mk.holder().equals(p.getUniqueId()) && mk.end() > System.currentTimeMillis())
            e.setDamage(e.getDamage() * (1 + mk.bonus()));

        if (!"divine_judgement".equals(id) && !"hammer_of_the_void".equals(id)) {     // attribute swapping: a mace held just now still counts
            String lm = recentMace(p);
            if (lm != null) maceHit(e, p, victim, lm);
        }

        if (id == null) return;
        final Player pf = p;
        hitFx(victim, id);
        int n = hitCounters.merge(p.getUniqueId() + id, 1, Integer::sum);
        switch (id) {
            case "bloody_eclipse" -> {
                Long end = bloodbath.get(p.getUniqueId());
                if (end != null && end > System.currentTimeMillis()) {
                    boolean vanillaCrit = isVanillaCrit(p);
                    if (!vanillaCrit) e.setDamage(e.getDamage() * 1.5);       // vanilla crit multiplier (a real falling crit already has it)
                    World cw = victim.getWorld(); Location cl = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
                    cw.playSound(cl, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.9f);
                    sp(cw, Particle.CRIT, cl, 30, .35, .45, .35, 0.5);
                    sp(cw, Particle.DAMAGE_INDICATOR, cl, 6, .3, .4, .3, 0.1);
                    dustAt(cl, 200, 10, 20, 1.6f, 14, 0.45);
                    Vector side = p.getLocation().getDirection().clone().crossProduct(new Vector(0, 1, 0));
                    if (side.lengthSquared() < 1e-4) side = new Vector(1, 0, 0);
                    side.normalize();
                    beam(cl.clone().add(side.clone().multiply(0.9)).add(0, 0.7, 0), cl.clone().add(side.clone().multiply(-0.9)).add(0, -0.7, 0),
                            0.15, l -> dustAt(l, 255, 40, 50, 1.3f, 1, 0.01));
                }
                int bc = bleedHits.merge(p.getUniqueId(), 1, Integer::sum);          // every 5 hits, then a 10 second cooldown
                if (bc >= 5 && cd(p, "bleed", 10, true)) { bleed(victim, p); bleedHits.remove(p.getUniqueId()); }
            }
            case "divine_judgement", "hammer_of_the_void" -> { recordMace(p, id); maceHit(e, p, victim, id); }
            case "stormcaller" -> {
                boolean thrownHit = e.getDamager() instanceof Trident;
                boolean strongHit = !thrownHit && p.getAttackCooldown() >= 0.9f;          // not a weak (spam) hit
                boolean sweepHit = e.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK;
                boolean qualifies = sweepHit || (strongHit && (isVanillaCrit(p) || p.isSprinting()));   // crit, sweep or sprint hits only
                if (qualifies && hitCounters.merge(p.getUniqueId() + "storm", 1, Integer::sum) % 5 == 0) Bukkit.getScheduler().runTask(this, () -> {
                    if (!victim.isValid() || victim.isDead()) return;
                    victim.getWorld().strikeLightningEffect(victim.getLocation());
                    trueDamage(victim, pf, 2.0);
                });
            }
            default -> {}
        }
    }
}
