package com.example.elementalswords;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Wind Sword: knockback hits, Gale Force, Wind Leap, Cyclone and Tornado. */
final class WindSword extends ElementSword {

    // ---- Tweak these (20 damage = 10 hearts) ----
    private static final double HIT_PUSH = 1.3;

    // Gale Force (right-click air): a cone of wind throws enemies back
    private static final long GALE_COOLDOWN_MS = 3000;
    private static final double GALE_RANGE = 12.0;
    private static final double GALE_HALF_ANGLE_DEGREES = 35;
    private static final double GALE_DAMAGE = 8.0;            // 4 hearts
    private static final double GALE_PUSH = 2.2;

    // Wind Leap (right-click ground): launches YOU up and forward
    private static final long LEAP_COOLDOWN_MS = 5000;
    private static final double LEAP_FORWARD = 0.9;
    private static final double LEAP_UP = 1.2;

    // Cyclone (sneak + right-click)
    private static final int CYCLONE_TICKS = 200;            // 10 seconds
    private static final long CYCLONE_COOLDOWN_MS = 20000;
    private static final double CYCLONE_RADIUS = 4.0;
    private static final double CYCLONE_DAMAGE = 8.0;         // 4 hearts, twice a second
    private static final int CYCLONE_DAMAGE_INTERVAL_TICKS = 10;

    // Tornado (sneak + hit)
    private static final long TORNADO_COOLDOWN_MS = 90000;    // 1 minute 30 seconds
    private static final double TORNADO_RANGE = 25.0;
    private static final int TORNADO_TICKS = 100;            // 5 seconds
    private static final double TORNADO_RADIUS = 6.0;
    private static final double TORNADO_DAMAGE = 3.0;         // every half second -> 15 hearts over its life
    private static final int TORNADO_DAMAGE_INTERVAL_TICKS = 10;

    /** Players who take no fall damage until the given time (after a leap, or while a Cyclone is up). */
    private final Map<UUID, Long> noFallUntil = new HashMap<>();

    WindSword(ElementalSwordsPlugin plugin) {
        super(plugin, SwordType.WIND);
    }

    @Override
    List<Component> lore() {
        return List.of(
                line("Hits blast enemies backwards", NamedTextColor.WHITE),
                line("Right-click air: Gale Force", NamedTextColor.YELLOW),
                line("Right-click ground: Wind Leap", NamedTextColor.YELLOW),
                line("Sneak + right-click: Cyclone", NamedTextColor.AQUA),
                line("Sneak + hit: Tornado (1m 30s cooldown)", NamedTextColor.DARK_AQUA));
    }

    @EventHandler
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(event.getEntity() instanceof Player player)) return;
        Long until = noFallUntil.get(player.getUniqueId());
        if (until != null && System.currentTimeMillis() < until) {
            event.setCancelled(true);
        }
    }

    private void protectFromFall(Player player, long ms) {
        noFallUntil.put(player.getUniqueId(), System.currentTimeMillis() + ms);
    }

    @Override
    void onHit(Player player, LivingEntity victim) {
        Vector push = Util.flat(victim.getLocation().toVector().subtract(player.getLocation().toVector()))
                .multiply(HIT_PUSH).setY(0.45);
        // Next tick, so it isn't overwritten by the normal hit knockback
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!victim.isDead()) victim.setVelocity(push);
        });
        World world = victim.getWorld();
        world.spawnParticle(Particle.CLOUD, victim.getLocation().add(0, 1, 0), 20, 0.3, 0.4, 0.3, 0.05);
        world.playSound(victim.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1f, 1.5f);
    }

    // ---------------------------------------------------------------- gale force

    @Override
    void primary(Player player) {
        if (!plugin.cooldowns().tryUse(player, "wind:gale", GALE_COOLDOWN_MS, "Gale Force")) return;

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World world = player.getWorld();
        double cos = Math.cos(Math.toRadians(GALE_HALF_ANGLE_DEGREES));

        world.playSound(eye, Sound.ENTITY_ENDER_DRAGON_FLAP, 2f, 0.8f);
        world.playSound(eye, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.5f, 0.6f);

        // A widening cone of wind
        double spread = Math.tan(Math.toRadians(GALE_HALF_ANGLE_DEGREES));
        for (double d = 1.0; d <= GALE_RANGE; d += 1.0) {
            Location p = eye.clone().add(dir.clone().multiply(d));
            double w = d * spread * 0.6;
            world.spawnParticle(Particle.CLOUD, p, 4, w, w, w, 0.05);
            if (d % 3 == 1) {
                world.spawnParticle(Particle.SWEEP_ATTACK, p, 1, w * 0.5, w * 0.5, w * 0.5, 0);
            }
        }

        for (LivingEntity target : Util.inRadius(eye, GALE_RANGE, player)) {
            Vector to = target.getBoundingBox().getCenter().subtract(eye.toVector());
            double length = to.length();
            if (length < 0.01) continue;
            if (to.dot(dir) / length < cos) continue;   // outside the cone

            plugin.abilityDamage(target, GALE_DAMAGE, player);
            target.setVelocity(dir.clone().multiply(GALE_PUSH).setY(0.6));
        }
    }

    // ---------------------------------------------------------------- wind leap

    @Override
    void ground(Player player, Block clicked, BlockFace face) {
        if (!plugin.cooldowns().tryUse(player, "wind:leap", LEAP_COOLDOWN_MS, "Wind Leap")) return;

        Vector launch = Util.flat(player.getLocation().getDirection()).multiply(LEAP_FORWARD).setY(LEAP_UP);
        player.setVelocity(launch);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 120, 0, false, false));
        protectFromFall(player, 8000);

        World world = player.getWorld();
        world.spawnParticle(Particle.CLOUD, player.getLocation(), 40, 0.5, 0.1, 0.5, 0.1);
        world.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 2f, 1.2f);
    }

    // ---------------------------------------------------------------- cyclone

    @Override
    String shieldName() {
        return "Cyclone";
    }

    @Override
    int shieldTicks() {
        return CYCLONE_TICKS;
    }

    @Override
    long shieldCooldownMs() {
        return CYCLONE_COOLDOWN_MS;
    }

    @Override
    void shieldStart(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, CYCLONE_TICKS + 10, 1, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, CYCLONE_TICKS + 10, 0, false, false));
        protectFromFall(player, (CYCLONE_TICKS + 10) * 50L + 3000);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1.5f, 0.7f);
    }

    @Override
    void shieldEnd(Player player) {
        player.removePotionEffect(PotionEffectType.SPEED);
        player.removePotionEffect(PotionEffectType.SLOW_FALLING);
    }

    @Override
    void shieldTick(Player player, int tick) {
        Location center = player.getLocation();
        World world = player.getWorld();
        double t = tick * 0.35;

        // Three arms of wind spiralling up around you
        for (int arm = 0; arm < 3; arm++) {
            for (int h = 0; h < 6; h++) {
                double a = t * 1.5 + arm * (Math.PI * 2 / 3) + h * 0.5;
                double r = 0.8 + h * 0.45;
                world.spawnParticle(Particle.CLOUD,
                        center.getX() + Math.cos(a) * r, center.getY() + h * 0.5, center.getZ() + Math.sin(a) * r,
                        1, 0, 0, 0, 0);
            }
        }

        // Shove everything nearby away from you, and cut them every half second
        if (tick % 5 == 0) {
            boolean damageNow = tick % CYCLONE_DAMAGE_INTERVAL_TICKS == 0;
            for (LivingEntity target : Util.inRadius(center, CYCLONE_RADIUS, player)) {
                if (damageNow) plugin.abilityDamage(target, CYCLONE_DAMAGE, player);
                Vector away = Util.flat(target.getLocation().toVector().subtract(center.toVector()));
                target.setVelocity(away.multiply(0.9).setY(0.3));
            }
        }
    }

    // ---------------------------------------------------------------- tornado

    @Override
    void ultimate(Player player) {
        if (!plugin.cooldowns().tryUse(player, "wind:tornado", TORNADO_COOLDOWN_MS, "Tornado")) return;

        Location center = Util.groundTarget(player, TORNADO_RANGE);
        World world = center.getWorld();
        world.playSound(center, Sound.ENTITY_ENDER_DRAGON_FLAP, 3f, 0.5f);

        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (tick >= TORNADO_TICKS) {
                    cancel();
                    return;
                }

                // The funnel
                for (double h = 0; h <= 8; h += 0.5) {
                    double r = 0.6 + h * 0.45;
                    double a = tick * 0.5 + h * 0.9;
                    for (int k = 0; k < 2; k++) {
                        double ang = a + k * Math.PI;
                        world.spawnParticle(Particle.CLOUD,
                                center.getX() + Math.cos(ang) * r, center.getY() + h, center.getZ() + Math.sin(ang) * r,
                                1, 0, 0, 0, 0);
                    }
                }
                if (tick % 20 == 0) {
                    world.playSound(center, Sound.ENTITY_ENDER_DRAGON_FLAP, 2f, 0.6f);
                }

                // Pull enemies in, spin them round and lift them
                boolean damageNow = tick % TORNADO_DAMAGE_INTERVAL_TICKS == 0;
                for (LivingEntity target : Util.inRadius(center.clone().add(0, 3, 0), TORNADO_RADIUS, player)) {
                    Vector toCenter = center.toVector().subtract(target.getLocation().toVector());
                    Vector pull = Util.flat(toCenter).multiply(0.3);
                    Vector swirl = new Vector(-pull.getZ(), 0, pull.getX()).multiply(1.3);
                    target.setVelocity(pull.add(swirl).setY(0.5));
                    if (damageNow) plugin.abilityDamage(target, TORNADO_DAMAGE, player);
                }
                tick++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }
}
