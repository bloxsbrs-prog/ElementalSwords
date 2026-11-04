package com.example.elementalswords;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Earth Sword: staggering hits, Rock Spikes, Quake, Stone Skin and Earthquake. */
final class EarthSword extends ElementSword {

    // ---- Tweak these (20 damage = 10 hearts) ----
    private static final int HIT_SLOW_TICKS = 40;

    // Rock Spikes (right-click air): a line of rock erupts in front of you
    private static final long SPIKES_COOLDOWN_MS = 3000;
    private static final int SPIKES_LENGTH = 16;              // blocks
    private static final double SPIKES_DAMAGE = 12.0;         // 6 hearts

    // Quake (right-click ground)
    private static final long QUAKE_COOLDOWN_MS = 6000;
    private static final double QUAKE_RADIUS = 6.0;
    private static final double QUAKE_DAMAGE = 10.0;          // 5 hearts

    // Stone Skin (sneak + right-click)
    private static final int SKIN_TICKS = 200;               // 10 seconds
    private static final long SKIN_COOLDOWN_MS = 20000;
    private static final double SKIN_PULSE_RADIUS = 3.5;
    private static final double SKIN_PULSE_DAMAGE = 8.0;      // 4 hearts, once a second
    private static final int SKIN_PULSE_INTERVAL_TICKS = 20;

    // Earthquake (sneak + hit)
    private static final long QUAKE_ULT_COOLDOWN_MS = 90000;  // 1 minute 30 seconds
    private static final double EQ_RADIUS = 10.0;
    private static final int EQ_PULSES = 3;
    private static final double EQ_DAMAGE = 10.0;             // 5 hearts per pulse

    EarthSword(ElementalSwordsPlugin plugin) {
        super(plugin, SwordType.EARTH);
    }

    @Override
    List<Component> lore() {
        return List.of(
                line("Hits stagger enemies", NamedTextColor.GREEN),
                line("Right-click air: Rock Spikes", NamedTextColor.YELLOW),
                line("Right-click ground: Quake", NamedTextColor.YELLOW),
                line("Sneak + right-click: Stone Skin", NamedTextColor.DARK_GREEN),
                line("Sneak + hit: Earthquake (1m 30s cooldown)", NamedTextColor.GOLD));
    }

    private BlockData dataOf(Block block) {
        return block.getType().isSolid() ? block.getBlockData() : Material.DIRT.createBlockData();
    }

    @Override
    void onHit(Player player, LivingEntity victim) {
        victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, HIT_SLOW_TICKS, 1));
        World world = victim.getWorld();
        world.spawnParticle(Particle.BLOCK, victim.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3,
                Material.DIRT.createBlockData());
        world.playSound(victim.getLocation(), Sound.BLOCK_STONE_BREAK, 1f, 0.8f);
    }

    // ---------------------------------------------------------------- rock spikes

    /** Finds the solid block with open air above it near the given spot. */
    private Block findGround(Location spot) {
        World world = spot.getWorld();
        int x = spot.getBlockX(), y = spot.getBlockY(), z = spot.getBlockZ();
        for (int dy = 2; dy >= -4; dy--) {
            Block b = world.getBlockAt(x, y + dy, z);
            if (b.getType().isSolid() && !b.getRelative(BlockFace.UP).getType().isSolid()) return b;
        }
        return world.getBlockAt(x, y - 1, z);
    }

    @Override
    void primary(Player player) {
        if (!plugin.cooldowns().tryUse(player, "earth:spikes", SPIKES_COOLDOWN_MS, "Rock Spikes")) return;

        Vector forward = Util.flat(player.getEyeLocation().getDirection());
        Location start = player.getLocation();
        World world = player.getWorld();
        Set<UUID> hit = new HashSet<>();

        new BukkitRunnable() {
            int step = 1;

            @Override
            public void run() {
                if (step > SPIKES_LENGTH || !player.isOnline()) {
                    cancel();
                    return;
                }
                Location flat = start.clone().add(forward.clone().multiply(step));
                Block ground = findGround(flat);
                Location at = ground.getLocation().add(0.5, 1.0, 0.5);

                world.spawnParticle(Particle.BLOCK, at, 25, 0.35, 0.3, 0.35, dataOf(ground));
                world.playSound(at, Sound.BLOCK_STONE_BREAK, 1f, 0.7f);

                for (LivingEntity target : Util.inRadius(at.clone().add(0, 0.5, 0), 1.7, player)) {
                    if (!hit.add(target.getUniqueId())) continue;   // each enemy only once per cast
                    plugin.abilityDamage(target, SPIKES_DAMAGE, player);
                    target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20, 1));
                    target.setVelocity(new Vector(0, 0.9, 0));
                }
                step++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ---------------------------------------------------------------- quake

    @Override
    void ground(Player player, Block clicked, BlockFace face) {
        if (clicked == null) return;
        if (!plugin.cooldowns().tryUse(player, "earth:quake", QUAKE_COOLDOWN_MS, "Quake")) return;

        Location center = clicked.getLocation().add(0.5, 1.0, 0.5);
        World world = center.getWorld();
        BlockData data = dataOf(clicked);

        // Shockwave ring
        new BukkitRunnable() {
            int step = 0;

            @Override
            public void run() {
                double r = (step + 1) * QUAKE_RADIUS / 6.0;
                for (int i = 0; i < 28; i++) {
                    double a = i * (Math.PI * 2 / 28);
                    world.spawnParticle(Particle.BLOCK,
                            center.getX() + Math.cos(a) * r, center.getY() + 0.2, center.getZ() + Math.sin(a) * r,
                            3, 0.2, 0.2, 0.2, data);
                }
                step++;
                if (step >= 6) cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);

        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.5f);
        world.playSound(center, Sound.BLOCK_STONE_BREAK, 2f, 0.5f);

        for (LivingEntity target : Util.inRadius(center, QUAKE_RADIUS, player)) {
            plugin.abilityDamage(target, QUAKE_DAMAGE, player);
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1));
            target.setVelocity(new Vector(0, 0.9, 0));
        }
    }

    // ---------------------------------------------------------------- stone skin

    @Override
    String shieldName() {
        return "Stone Skin";
    }

    @Override
    int shieldTicks() {
        return SKIN_TICKS;
    }

    @Override
    long shieldCooldownMs() {
        return SKIN_COOLDOWN_MS;
    }

    @Override
    void shieldStart(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, SKIN_TICKS + 10, 2, false, false));   // Resistance III
        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, SKIN_TICKS + 10, 1, false, false));  // 4 extra hearts
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_STONE_PLACE, 2f, 0.6f);
    }

    @Override
    void shieldEnd(Player player) {
        player.removePotionEffect(PotionEffectType.RESISTANCE);
        player.removePotionEffect(PotionEffectType.ABSORPTION);
    }

    @Override
    void shieldTick(Player player, int tick) {
        Location center = player.getLocation();
        World world = player.getWorld();
        BlockData stone = Material.STONE.createBlockData();

        // Four rocks orbiting you
        for (int i = 0; i < 4; i++) {
            double a = tick * 0.25 + i * (Math.PI / 2);
            world.spawnParticle(Particle.BLOCK,
                    center.getX() + Math.cos(a) * 1.3,
                    center.getY() + 0.4 + i * 0.4,
                    center.getZ() + Math.sin(a) * 1.3,
                    3, 0.08, 0.08, 0.08, stone);
        }

        // Once a second a shockwave shoves enemies away
        if (tick % SKIN_PULSE_INTERVAL_TICKS == 0) {
            world.playSound(center, Sound.BLOCK_STONE_BREAK, 1f, 0.5f);
            for (int i = 0; i < 24; i++) {
                double a = i * (Math.PI * 2 / 24);
                world.spawnParticle(Particle.BLOCK,
                        center.getX() + Math.cos(a) * SKIN_PULSE_RADIUS, center.getY() + 0.2,
                        center.getZ() + Math.sin(a) * SKIN_PULSE_RADIUS, 2, 0.1, 0.1, 0.1, stone);
            }
            for (LivingEntity target : Util.inRadius(center, SKIN_PULSE_RADIUS, player)) {
                plugin.abilityDamage(target, SKIN_PULSE_DAMAGE, player);
                Vector away = Util.flat(target.getLocation().toVector().subtract(center.toVector()));
                target.setVelocity(away.multiply(1.0).setY(0.4));
            }
        }
    }

    // ---------------------------------------------------------------- earthquake

    @Override
    void ultimate(Player player) {
        if (!plugin.cooldowns().tryUse(player, "earth:earthquake", QUAKE_ULT_COOLDOWN_MS, "Earthquake")) return;

        new BukkitRunnable() {
            int pulse = 0;

            @Override
            public void run() {
                if (pulse >= EQ_PULSES || !player.isOnline() || player.isDead()) {
                    cancel();
                    return;
                }
                Location center = player.getLocation();
                World world = center.getWorld();
                BlockData data = dataOf(center.clone().subtract(0, 1, 0).getBlock());

                world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.5f);
                world.playSound(center, Sound.BLOCK_ANVIL_LAND, 1.5f, 0.5f);
                for (int ring = 1; ring <= 5; ring++) {
                    double r = ring * EQ_RADIUS / 5.0;
                    for (int i = 0; i < 30; i++) {
                        double a = i * (Math.PI * 2 / 30);
                        world.spawnParticle(Particle.BLOCK,
                                center.getX() + Math.cos(a) * r, center.getY() + 0.2, center.getZ() + Math.sin(a) * r,
                                2, 0.2, 0.3, 0.2, data);
                    }
                }

                for (LivingEntity target : Util.inRadius(center, EQ_RADIUS, player)) {
                    plugin.abilityDamage(target, EQ_DAMAGE, player);
                    target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 2));
                    target.setVelocity(new Vector(0, 0.9, 0));
                }
                pulse++;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }
}
