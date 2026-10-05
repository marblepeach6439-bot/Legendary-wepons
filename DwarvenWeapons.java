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
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

public class DwarvenWeapons extends JavaPlugin implements Listener {

    static final List<String> IDS = List.of("bloody_eclipse", "stormcaller", "dwarven_pickaxe",
            "ten_ton_axe", "void_blade", "inferno", "skulk_battle_axe", "royal_spear", "kings_crown", "divine_judgement", "hammer_of_the_void");

    NamespacedKey KEY;
    boolean internal = false;   // guard for our own true-damage calls
    boolean mining = false;     // guard for 3x3 mining recursion

    final Map<String, Long> cooldowns = new HashMap<>();
    final Map<UUID, Long> bloodbath = new HashMap<>();
    final Map<UUID, Long> eclipsed = new HashMap<>();   // victims of Blinding Eclipse (no wind charges/cobwebs)
    final Map<UUID, Long> stunned = new HashMap<>();
    final Map<String, Integer> hitCounters = new HashMap<>();
    final Map<Block, Material> dome = new HashMap<>();  // dome block -> original material (always air)
    final Map<UUID, Incin> incin = new HashMap<>();
    final Map<Material, Material> smelt = new HashMap<>();
    final Map<UUID, Long> lungeCd = new HashMap<>();
    final Map<UUID, Integer> airHits = new HashMap<>();   // mace hits since last touching the ground
    final Map<UUID, Thrown> thrown = new HashMap<>();
    static class Thrown { ItemDisplay disp; Location land; boolean landed; }

    record Incin(Location center, int radius, long end) {}

    // ------------------------------------------------------------------ lifecycle
    @Override
    public void onEnable() {
        saveDefaultConfig();
        KEY = new NamespacedKey(this, "weapon");
        getServer().getPluginManager().registerEvents(this, this);
        buildSmeltMap();
        new BukkitRunnable() {
            @Override public void run() { for (Player p : Bukkit.getOnlinePlayers()) passives(p); }
        }.runTaskTimer(this, 20, 20);
    }

    @Override
    public void onDisable() {
        for (Map.Entry<Block, Material> en : dome.entrySet()) {
            Material t = en.getKey().getType();
            if (t == Material.DIRT || t == Material.STONE) en.getKey().setType(en.getValue(), false);
        }
        dome.clear();
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
        return i.getItemMeta().getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
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
                en.put(Enchantment.RIPTIDE, 3); en.put(Enchantment.UNBREAKING, 3); en.put(Enchantment.MENDING, 1);
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
            case "skulk_battle_axe" -> {
                mat = Material.NETHERITE_AXE; name = "Skulk Battle Axe"; col = TextColor.color(0x28C8DC);
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
            default -> { return null; }
        }
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, col).decoration(TextDecoration.ITALIC, false).decorate(TextDecoration.BOLD));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(l);
        for (Map.Entry<Enchantment, Integer> e : en.entrySet()) meta.addEnchant(e.getKey(), e.getValue(), true);
        if (id.equals("kings_crown")) {
            meta.setUnbreakable(true);
            EquippableComponent eq = meta.getEquippable();      // worn model: assets/dwarven/equipment/kings_crown.json
            eq.setSlot(EquipmentSlot.HEAD);
            eq.setModel(new NamespacedKey("dwarven", "kings_crown"));
            meta.setEquippable(eq);
        }
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, id);
        meta.setItemModel(new NamespacedKey("dwarven", id));   // resource pack: assets/dwarven/items/<id>.json
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
            if (!quiet) p.sendActionBar(Component.text(key.replace('_', ' ') + " on cooldown: " + ((until - now + 999) / 1000) + "s", NamedTextColor.RED));
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
        for (double x = 0; x < len; x += 0.6) a.getWorld().spawnParticle(particle, a.clone().add(d.clone().multiply(x)), 1, 0, 0, 0, 0);
    }

    // ---- Skulk Battle Axe
    void sculkBeams(Player p) {
        LivingEntity t = lookTarget(p, 30);
        if (t == null) { p.sendActionBar(Component.text("No target in sight", NamedTextColor.GRAY)); return; }
        if (!cd(p, "sculk_beams", 60)) return;
        int drain = getConfig().getInt("sculk-beam-armor-durability", 50);
        for (int i = 0; i < 3; i++) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!t.isValid() || t.isDead() || !p.isOnline()) return;
                Location from = p.getEyeLocation().add(0, -0.2, 0), to = t.getLocation().add(0, t.getHeight() / 2, 0);
                beamParticles(from, to, Particle.SONIC_BOOM);
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 1f, 1f);
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
        Warden w = p.getWorld().spawn(l, Warden.class);
        LivingEntity t = lookTarget(p, 30);
        if (t != null) w.setAnger(t, 150);
        p.getWorld().playSound(l, Sound.ENTITY_WARDEN_EMERGE, 1f, 1f);
        Bukkit.getScheduler().runTaskLater(this, () -> { if (w.isValid()) w.remove(); },
                getConfig().getInt("warden-lifetime-seconds", 60) * 20L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onWardenTarget(EntityTargetLivingEntityEvent e) {
        if (e.getEntity() instanceof Warden && e.getTarget() instanceof Player p && hasWeapon(p, "skulk_battle_axe")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWardenHurt(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Warden && e.getEntity() instanceof Player p && hasWeapon(p, "skulk_battle_axe")) e.setCancelled(true);
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
                d.getWorld().spawnParticle(Particle.END_ROD, nl, 2, 0.05, 0.05, 0.05, 0);
            }
        }.runTaskTimer(this, 0, 1);
    }

    void smite(Player p) {
        LivingEntity t = lookTarget(p, 40);
        if (t == null) { p.sendActionBar(Component.text("No target in sight", NamedTextColor.GRAY)); return; }
        if (!cd(p, "smite", 30)) return;
        ItemStack it = p.getInventory().getItemInMainHand().clone();
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_TRIDENT_THROW, 1f, 0.6f);
        flyItem(p, t, it, () -> {
            if (!t.isValid() || t.isDead()) return;
            trueDamage(t, p, 6.0);
            t.getWorld().strikeLightningEffect(t.getLocation());
            new BukkitRunnable() {
                int n = 0;
                @Override public void run() {
                    if (!t.isValid() || t.isDead() || !p.isOnline() || n++ > 15) { cancel(); return; }
                    Vector v = p.getLocation().toVector().subtract(t.getLocation().toVector());
                    if (v.length() < 2.5) { cancel(); return; }
                    t.setVelocity(v.normalize().multiply(1.1).setY(0.25));   // pull toward the wielder
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
                l.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, l, 1);
                l.getWorld().playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 1f);
                for (Entity en : v.getNearbyEntities(3, 3, 3))
                    if (en instanceof LivingEntity le && en != p && !(en instanceof ArmorStand)) drainAllArmor(le, 60);
                drainAllArmor(v, 60);
                p.sendActionBar(Component.text("FINAL VERDICT", NamedTextColor.GOLD));
                airHits.remove(u);
                return;
            }
        } else if (c >= 6) {   // 5 hits, then the NEXT hit is the void strike
            e.setDamage(e.getDamage() * 2);
            v.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 400, 1));
            v.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 400, 1));
            v.getWorld().spawnParticle(Particle.REVERSE_PORTAL, v.getLocation().add(0, 1, 0), 60, .4, .8, .4);
            p.sendActionBar(Component.text("VOID STRIKE", NamedTextColor.DARK_PURPLE));
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
                if (n++ > 24) { th.land = pos.clone(); th.landed = true; p.sendActionBar(Component.text("Right-click to teleport to the mace", NamedTextColor.LIGHT_PURPLE)); cancel(); return; }
                RayTraceResult r = pos.getWorld().rayTrace(pos, dir, 1.7, FluidCollisionMode.NEVER, true, 0.4,
                        en -> en != p && en instanceof LivingEntity && !(en instanceof ArmorStand));
                if (r != null) {
                    double dist = Math.max(0, pos.toVector().distance(r.getHitPosition()) - 0.8);
                    th.land = pos.clone().add(dir.clone().multiply(dist)); th.landed = true;
                    d.teleport(th.land);
                    p.sendActionBar(Component.text("Right-click to teleport to the mace", NamedTextColor.LIGHT_PURPLE));
                    cancel(); return;
                }
                pos.add(dir.clone().multiply(1.7));
                d.teleport(pos);
                d.getWorld().spawnParticle(Particle.PORTAL, pos, 6, .1, .1, .1, .2);
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
            p.sendActionBar(Component.text("Wind Burst " + next, NamedTextColor.AQUA));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 0.8f + next * 0.2f);
            return;
        }
        if (id.equals("hammer_of_the_void")) {
            Thrown th = thrown.get(p.getUniqueId());
            if (th == null) return;
            e.setCancelled(true);
            if (!th.landed) { p.sendActionBar(Component.text("The mace is still flying", NamedTextColor.GRAY)); return; }
            Location dest = null;
            for (double dy = -1.6; dy <= 0.01; dy += 0.2) {
                Location c = th.land.clone().add(0, dy, 0);
                if (!c.getBlock().getType().isSolid() && !c.clone().add(0, 1, 0).getBlock().getType().isSolid()) { dest = c; break; }
            }
            thrown.remove(p.getUniqueId());
            if (th.disp != null && th.disp.isValid()) th.disp.remove();
            if (dest == null) { p.sendActionBar(Component.text("No safe spot at the mace", NamedTextColor.RED)); return; }
            dest.setYaw(p.getLocation().getYaw()); dest.setPitch(p.getLocation().getPitch());
            p.getWorld().spawnParticle(Particle.PORTAL, p.getLocation().add(0, 1, 0), 60, .4, .8, .4);
            p.teleport(dest);
            p.getWorld().spawnParticle(Particle.REVERSE_PORTAL, dest.clone().add(0, 1, 0), 60, .4, .8, .4);
            p.getWorld().playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.8f);
        }
    }

    // ---- Royal Spear
    void royalJudgement(Player p) {
        LivingEntity t = lookTarget(p, 40);
        if (t == null) { p.sendActionBar(Component.text("No target in sight", NamedTextColor.GRAY)); return; }
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
                    t.getWorld().spawnParticle(Particle.CRIT, target, 30, .4, .5, .4);
                    return;
                }
                Location nl = d.getLocation().add(v.clone().normalize().multiply(1.8));
                nl.setDirection(v);
                d.teleport(nl);
                d.getWorld().spawnParticle(Particle.END_ROD, nl, 2, 0.05, 0.05, 0.05, 0);
            }
        }.runTaskTimer(this, 0, 1);
    }

    void piercingStrike(Player p) {
        if (!cd(p, "piercing_strike", 20)) return;
        Location eye = p.getEyeLocation(); Vector dir = eye.getDirection().normalize();
        Location end = eye.clone().add(dir.clone().multiply(30));
        beamParticles(eye.clone().add(0, -0.2, 0), end, Particle.END_ROD);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 0.8f, 1.6f);
        Location mid = eye.clone().add(dir.clone().multiply(15));
        for (Entity en : p.getWorld().getNearbyEntities(mid, 16, 16, 16)) {
            if (en == p || !(en instanceof LivingEntity le) || en instanceof ArmorStand) continue;
            BoundingBox box = en.getBoundingBox().expand(0.3);
            RayTraceResult r = box.rayTrace(eye.toVector(), dir, 30);
            if (r != null) trueDamage(le, p, 8.0);           // goes through blocks: no block check
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
            case "skulk_battle_axe" -> { effect(p, PotionEffectType.STRENGTH, 0); effect(p, PotionEffectType.SPEED, 1);
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
        if (id == null || id.equals("kings_crown")) return;
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
            case "skulk_battle_axe" -> { if (sh) summonWarden(p); else sculkBeams(p); }
            case "royal_spear" -> { if (sh) piercingStrike(p); else royalJudgement(p); }
            case "divine_judgement" -> { if (!sh) smite(p); }
            case "hammer_of_the_void" -> { if (!sh) voidThrow(p); }
            default -> {}
        }
    }

    // ---- Bloody Eclipse
    void bloodbath(Player p) {
        if (!cd(p, "bloodbath", 45)) return;
        bloodbath.put(p.getUniqueId(), System.currentTimeMillis() + 10_000);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.6f);
        p.sendActionBar(Component.text("BLOODBATH! Every hit crits for 10s", NamedTextColor.DARK_RED));
    }

    void blindingEclipse(Player p) {
        if (!cd(p, "blinding_eclipse", 60)) return;
        long end = System.currentTimeMillis() + 10_000;
        for (Entity en : p.getNearbyEntities(20, 20, 20)) {
            if (!(en instanceof LivingEntity le) || en instanceof ArmorStand) continue;
            if (en.getLocation().distanceSquared(p.getLocation()) > 400) continue;
            le.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 200, 0));
            if (en instanceof Player v) eclipsed.put(v.getUniqueId(), end);
        }
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f, 0.6f);
        p.sendActionBar(Component.text("BLINDING ECLIPSE", NamedTextColor.DARK_PURPLE));
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
            p.sendActionBar(Component.text("Wind charges are disabled by the eclipse!", NamedTextColor.RED));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCobweb(BlockPlaceEvent e) {
        if (e.getBlock().getType() == Material.COBWEB && isEclipsed(e.getPlayer())) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(Component.text("Cobwebs are disabled by the eclipse!", NamedTextColor.RED));
        }
    }

    void bleed(LivingEntity v, Player src) {
        new BukkitRunnable() {
            int n = 0;
            @Override public void run() {
                if (!v.isValid() || v.isDead() || n++ >= 5) { cancel(); return; }
                trueDamage(v, null, 1.0);
                v.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, v.getLocation().add(0, 1, 0), 6, .3, .4, .3);
            }
        }.runTaskTimer(this, 20, 20);
        if (src != null) src.sendActionBar(Component.text("Bleed applied", NamedTextColor.RED));
    }

    // ---- Stormcaller
    void lightningStorm(Player p) {
        LivingEntity t = lookTarget(p, 30);
        if (t == null) { p.sendActionBar(Component.text("No target in sight", NamedTextColor.GRAY)); return; }
        if (!cd(p, "lightning_storm", 30)) return;
        for (int i = 0; i < 3; i++) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!t.isValid() || t.isDead()) return;
                t.getWorld().strikeLightningEffect(t.getLocation());
                trueDamage(t, p, 4.0);
            }, i * 6L);
        }
    }

    void tidesCall(Player p) {
        if (!cd(p, "tides_call", 40)) return;
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_TRIDENT_THUNDER, 1f, 0.7f);
        new BukkitRunnable() {
            int n = 0;
            @Override public void run() {
                if (!p.isOnline() || n++ >= 50) { cancel(); return; }
                for (Entity en : p.getNearbyEntities(20, 20, 20)) {
                    if (!(en instanceof LivingEntity le) || en instanceof ArmorStand) continue;
                    if (en.getLocation().distanceSquared(p.getLocation()) > 400) continue;
                    Vector v = p.getLocation().toVector().subtract(en.getLocation().toVector());
                    if (v.lengthSquared() > 4) en.setVelocity(en.getVelocity().add(v.normalize().multiply(0.12).setY(0.02)));
                    if (!(en instanceof WaterMob) && !(en instanceof Drowned) && !le.hasPotionEffect(PotionEffectType.WATER_BREATHING))
                        le.setRemainingAir(Math.max(-20, le.getRemainingAir() - 20));
                    en.getWorld().spawnParticle(Particle.BUBBLE_POP, en.getLocation().add(0, 1, 0), 6, .3, .5, .3);
                }
            }
        }.runTaskTimer(this, 0, 4);
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
        w.playSound(c, Sound.BLOCK_ANVIL_LAND, 1f, 0.5f);
        p.sendActionBar(Component.text("CALL OF THE DEEP", NamedTextColor.GRAY));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (Block b : placed) {
                Material t = b.getType();
                if (dome.remove(b) != null && (t == Material.DIRT || t == Material.STONE)) b.setType(Material.AIR, false);
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
        if (t == null) { p.sendActionBar(Component.text("No target in reach", NamedTextColor.GRAY)); return; }
        if (!cd(p, "stunning_strike", 30)) return;
        stun(t, 100);
        trueDamage(t, p, 4.0);
        t.getWorld().playSound(t.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 1f, 0.6f);
        t.getWorld().spawnParticle(Particle.CRIT, t.getLocation().add(0, 1, 0), 30, .4, .5, .4);
    }

    void durabilityDrain(Player p) {
        LivingEntity t = lookTarget(p, 6);
        if (t == null) { p.sendActionBar(Component.text("No target in reach", NamedTextColor.GRAY)); return; }
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
        p.getWorld().spawnParticle(Particle.PORTAL, base.add(0, 1, 0), 60, .4, .8, .4);
        p.teleport(dest);
        p.getWorld().spawnParticle(Particle.REVERSE_PORTAL, dest.clone().add(0, 1, 0), 60, .4, .8, .4);
        p.getWorld().playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.8f);
    }

    void pullOfTheVoid(Player p) {
        Location eye = p.getEyeLocation();
        RayTraceResult r = p.getWorld().rayTraceEntities(eye, eye.getDirection(), 40, 0.6,
                en -> en != p && en instanceof LivingEntity && !(en instanceof ArmorStand) && !en.isDead());
        if (r == null || r.getHitEntity() == null) { p.sendActionBar(Component.text("No target in sight", NamedTextColor.GRAY)); return; }
        if (!cd(p, "pull_of_the_void", 30)) return;
        Entity t = r.getHitEntity();
        Vector flat = eye.getDirection().setY(0);
        if (flat.lengthSquared() < 1e-4) flat = new Vector(0, 0, 1);
        Location dest = p.getLocation().add(flat.normalize().multiply(2));
        if (dest.getBlock().getType().isSolid() || dest.clone().add(0, 1, 0).getBlock().getType().isSolid()) dest = p.getLocation();
        t.getWorld().spawnParticle(Particle.PORTAL, t.getLocation().add(0, 1, 0), 60, .4, .8, .4);
        dest.setYaw(t.getLocation().getYaw()); dest.setPitch(t.getLocation().getPitch());
        t.teleport(dest);
        t.getWorld().playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.6f);
    }

    // ---- Inferno
    void acidicBlaze(Player p) {
        LivingEntity t = lookTarget(p, 20);
        if (t == null) { p.sendActionBar(Component.text("No target in sight", NamedTextColor.GRAY)); return; }
        if (!cd(p, "acidic_blaze", 45)) return;
        new BukkitRunnable() {
            int n = 0;
            @Override public void run() {
                if (!t.isValid() || t.isDead() || n >= 100) { cancel(); return; }
                t.setFireTicks(30);                                  // re-ignites every 2 ticks: water cannot put it out
                if (n % 10 == 0 && n > 0 && t.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE))
                    trueDamage(t, p, 1.0);                           // bypasses fire resistance
                n++;
            }
        }.runTaskTimer(this, 0, 2);
        t.getWorld().playSound(t.getLocation(), Sound.ITEM_FIRECHARGE_USE, 1f, 0.7f);
    }

    boolean inIncin(Location l) {
        long now = System.currentTimeMillis();
        for (Incin i : incin.values()) {
            if (i.end() < now || !i.center().getWorld().equals(l.getWorld())) continue;
            if (i.center().distanceSquared(l) <= (double) i.radius() * i.radius()) return true;
        }
        return false;
    }

    void incinerate(Player p) {
        if (!cd(p, "incineration", 120)) return;
        int R = getConfig().getInt("incineration-radius", 20);
        Location c = p.getLocation().clone(); World w = c.getWorld();
        incin.put(p.getUniqueId(), new Incin(c, R, System.currentTimeMillis() + 20_000));
        w.playSound(c, Sound.ENTITY_BLAZE_SHOOT, 1.5f, 0.5f);
        new BukkitRunnable() {
            int x = -R;
            @Override public void run() {
                for (int n = 0; n < 4 && x <= R; n++, x++) {
                    for (int dz = -R; dz <= R; dz++) for (int dy = -R; dy <= R; dy++) {
                        if (x * x + dy * dy + dz * dz > R * R) continue;
                        int bx = c.getBlockX() + x, by = c.getBlockY() + dy, bz = c.getBlockZ() + dz;
                        if (by < w.getMinHeight() || by >= w.getMaxHeight() || !w.isChunkLoaded(bx >> 4, bz >> 4)) continue;
                        Block b = w.getBlockAt(bx, by, bz);
                        Material t = b.getType();
                        if (t == Material.WATER || t == Material.KELP || t == Material.KELP_PLANT || t == Material.SEAGRASS
                                || t == Material.TALL_SEAGRASS || t == Material.BUBBLE_COLUMN) b.setType(Material.AIR, false);
                        else if (b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged()) {
                            wl.setWaterlogged(false); b.setBlockData(wl, false);
                        }
                    }
                }
                if (x > R) cancel();
            }
        }.runTaskTimer(this, 0, 1);
        // visual flames while active
        new BukkitRunnable() {
            int n = 0;
            @Override public void run() {
                if (!p.isOnline() || n++ > 40) { cancel(); return; }
                p.getWorld().spawnParticle(Particle.FLAME, p.getLocation().add(0, 1, 0), 40, 3, 1.5, 3, 0.02);
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

        if (id == null) return;
        final Player pf = p;
        int n = hitCounters.merge(p.getUniqueId() + id, 1, Integer::sum);
        switch (id) {
            case "bloody_eclipse" -> {
                Long end = bloodbath.get(p.getUniqueId());
                if (end != null && end > System.currentTimeMillis()) {
                    e.setDamage(e.getDamage() * 1.5);
                    victim.getWorld().spawnParticle(Particle.CRIT, victim.getLocation().add(0, 1, 0), 20, .3, .5, .3);
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
