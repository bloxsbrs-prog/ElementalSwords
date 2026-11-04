package com.example.elementalswords;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Thunder Sword: shocking hits, Chain Lightning, Storm Call, Thunder Aura and Judgement. */
final class LightningSword extends ElementSword {

    // ---- Tweak these (20 damage = 10 hearts) ----
    private static final double HIT_SHOCK_DAMAGE = 6.0;       // extra 3 hearts on every hit

    // Chain Lightning (right-click air)
    private static final long CHAIN_COOLDOWN_MS = 3000;
    private static final double CHAIN_RANGE = 40.0;
    private static final double CHAIN_DAMAGE = 14.0;          // 7 hearts on the first target
    private static final double CHAIN_HOP_DAMAGE = 10.0;      // 5 hearts on each jump
    private static final int CHAIN_HOPS = 3;
    private static final double CHAIN_HOP_RANGE = 7.0;

    // Storm Call (right-click ground): lightning ring
    private static final long STORM_COOLDOWN_MS = 8000;
    private static final int STORM_STRIKES = 6;
    private static final double STORM_RING_RADIUS = 4.0;
    private static final double STORM_HIT_RADIUS = 3.0;
    private static final double STORM_DAMAGE = 12.0;          // 6 hearts per strike

    // Thunder Aura (sneak + right-click)
    private static final int AURA_TICKS = 200;               // 10 seconds
    private static final long AURA_COOLDOWN_MS = 20000;
    private static final double AURA_RADIUS = 6.0;
    private static final double AURA_DAMAGE = 12.0;           // a bolt on a random enemy twice a second
    private static final int AURA_INTERVAL_TICKS = 10;

    // Judgement (sneak + hit)
    private static final long JUDGE_COOLDOWN_MS = 90000;      // 1 minute 30 seconds
    private static final double JUDGE_RANGE = 40.0;
    private static final int JUDGE_STRIKES = 10;
    private static final double JUDGE_RADIUS = 7.0;
    private static final double JUDGE_HIT_RADIUS = 3.0;
    private static final double JUDGE_DAMAGE = 10.0;          // 5 hearts per strike

    LightningSword(ElementalSwordsPlugin plugin) {
        super(plugin, SwordType.LIGHTNING);
    }

    @Override
    List<Component> lore() {
        return List.of(
                line("Every hit shocks the enemy", NamedTextColor.YELLOW),
                line("Right-click air: Chain Lightning", NamedTextColor.YELLOW),
                line("Right-click ground: Storm Call", NamedTextColor.YELLOW),
                line("Sneak + right-click: Thunder Aura", NamedTextColor.GOLD),
                line("Sneak + hit: Judgement (1m 30s cooldown)", NamedTextColor.DARK_PURPLE));
    }

    /** One lightning bolt: flashes, thunders and hurts everything near where it lands. No fire, no block damage. */
    private void strike(Location loc, double hitRadius, double damage, Player caster) {
        World world = loc.getWorld();
        world.strikeLightningEffect(loc);
        world.spawnParticle(Particle.ELECTRIC_SPARK, loc.clone().add(0, 1, 0), 40, 0.6, 1.0, 0.6, 0.3);
        for (LivingEntity target : Util.inRadius(loc, hitRadius, caster)) {
            plugin.abilityDamage(target, damage, caster);
        }
    }

    @Override
    void onHit(Player player, LivingEntity victim) {
        Location at = victim.getLocation();
        at.getWorld().strikeLightningEffect(at);
        at.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, at.clone().add(0, 1, 0), 25, 0.3, 0.5, 0.3, 0.2);
        // Next tick, so the shock doesn't get tangled up with the hit that is still being processed
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!victim.isDead()) plugin.abilityDamage(victim, HIT_SHOCK_DAMAGE, player);
        });
    }

    // ---------------------------------------------------------------- chain lightning

    @Override
    void primary(Player player) {
        if (!plugin.cooldowns().tryUse(player, "lightning:chain", CHAIN_COOLDOWN_MS, "Chain Lightning")) return;

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World world = player.getWorld();

        RayTraceResult hit = world.rayTrace(eye, dir, CHAIN_RANGE, FluidCollisionMode.NEVER, true, 0.6,
                e -> Util.isTarget(e, player));

        Location point;
        LivingEntity first = null;
        if (hit != null && hit.getHitEntity() instanceof LivingEntity living) {
            first = living;
            point = living.getBoundingBox().getCenter().toLocation(world);
        } else if (hit != null) {
            point = hit.getHitPosition().toLocation(world);
        } else {
            point = eye.clone().add(dir.clone().multiply(CHAIN_RANGE));
        }

        world.playSound(player.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.5f, 1.6f);
        Util.line(world, eye.clone().add(dir.clone().multiply(0.8)), point, Particle.ELECTRIC_SPARK, 0.4);
        world.strikeLightningEffect(point);

        // First target (or everything near where the bolt landed)
        Set<UUID> struck = new HashSet<>();
        List<LivingEntity> firstTargets = first != null ? List.of(first) : Util.inRadius(point, 3.0, player);
        for (LivingEntity target : firstTargets) {
            plugin.abilityDamage(target, CHAIN_DAMAGE, player);
            struck.add(target.getUniqueId());
        }

        // Then it jumps from enemy to enemy
        Location from = point;
        for (int hop = 0; hop < CHAIN_HOPS; hop++) {
            LivingEntity next = null;
            double best = Double.MAX_VALUE;
            for (LivingEntity candidate : Util.inRadius(from, CHAIN_HOP_RANGE, player)) {
                if (struck.contains(candidate.getUniqueId())) continue;
                double d = candidate.getBoundingBox().getCenter().distance(from.toVector());
                if (d < best) {
                    best = d;
                    next = candidate;
                }
            }
            if (next == null) break;

            Location to = next.getBoundingBox().getCenter().toLocation(world);
            Util.line(world, from, to, Particle.ELECTRIC_SPARK, 0.3);
            world.strikeLightningEffect(next.getLocation());
            plugin.abilityDamage(next, CHAIN_HOP_DAMAGE, player);
            struck.add(next.getUniqueId());
            from = to;
        }
    }

    // ---------------------------------------------------------------- storm call

    @Override
    void ground(Player player, Block clicked, BlockFace face) {
        if (clicked == null) return;
        if (!plugin.cooldowns().tryUse(player, "lightning:storm", STORM_COOLDOWN_MS, "Storm Call")) return;

        Location center = clicked.getRelative(face).getLocation().add(0.5, 0, 0.5);

        new BukkitRunnable() {
            int n = 0;

            @Override
            public void run() {
                if (n >= STORM_STRIKES) {
                    cancel();
                    return;
                }
                Location at;
                if (n == STORM_STRIKES - 1) {
                    at = center.clone();   // the last bolt hits the middle
                } else {
                    double a = n * (Math.PI * 2 / (STORM_STRIKES - 1));
                    at = center.clone().add(Math.cos(a) * STORM_RING_RADIUS, 0, Math.sin(a) * STORM_RING_RADIUS);
                }
                strike(at, STORM_HIT_RADIUS, STORM_DAMAGE, player);
                n++;
            }
        }.runTaskTimer(plugin, 0L, 4L);
    }

    // ---------------------------------------------------------------- thunder aura

    @Override
    String shieldName() {
        return "Thunder Aura";
    }

    @Override
    int shieldTicks() {
        return AURA_TICKS;
    }

    @Override
    long shieldCooldownMs() {
        return AURA_COOLDOWN_MS;
    }

    @Override
    void shieldStart(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, AURA_TICKS + 10, 1, false, false));
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1f, 1.4f);
    }

    @Override
    void shieldEnd(Player player) {
        player.removePotionEffect(PotionEffectType.SPEED);
    }

    @Override
    void shieldTick(Player player, int tick) {
        Location center = player.getLocation();
        World world = player.getWorld();
        double angle = tick * 0.3;

        // Crackling ring around you
        for (int i = 0; i < 12; i++) {
            double a = angle + i * (Math.PI * 2 / 12);
            world.spawnParticle(Particle.ELECTRIC_SPARK,
                    center.getX() + Math.cos(a) * 2.0,
                    center.getY() + 0.1 + ThreadLocalRandom.current().nextDouble() * 1.8,
                    center.getZ() + Math.sin(a) * 2.0,
                    1, 0, 0, 0, 0);
        }

        // A bolt hits a random enemy twice a second
        if (tick % AURA_INTERVAL_TICKS == 0) {
            List<LivingEntity> targets = Util.inRadius(center, AURA_RADIUS, player);
            if (!targets.isEmpty()) {
                LivingEntity target = targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
                strike(target.getLocation(), 2.0, AURA_DAMAGE, player);
            }
        }
    }

    // ---------------------------------------------------------------- judgement

    @Override
    void ultimate(Player player) {
        if (!plugin.cooldowns().tryUse(player, "lightning:judgement", JUDGE_COOLDOWN_MS, "Judgement")) return;

        Location target = Util.groundTarget(player, JUDGE_RANGE);
        World world = target.getWorld();
        world.playSound(player.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2f, 0.7f);

        // Warning ring on the ground where the storm will fall
        for (int i = 0; i < 48; i++) {
            double a = i * (Math.PI * 2 / 48);
            world.spawnParticle(Particle.ELECTRIC_SPARK,
                    target.getX() + Math.cos(a) * JUDGE_RADIUS, target.getY() + 0.2,
                    target.getZ() + Math.sin(a) * JUDGE_RADIUS, 1, 0, 0, 0, 0);
        }

        new BukkitRunnable() {
            int n = 0;

            @Override
            public void run() {
                if (n >= JUDGE_STRIKES) {
                    cancel();
                    return;
                }
                Location at = target.clone();
                if (n > 0) {   // the first bolt hits the middle, the rest fall at random
                    ThreadLocalRandom rnd = ThreadLocalRandom.current();
                    double a = rnd.nextDouble() * Math.PI * 2;
                    double r = Math.sqrt(rnd.nextDouble()) * JUDGE_RADIUS;
                    at.add(Math.cos(a) * r, 0, Math.sin(a) * r);
                }
                strike(at, JUDGE_HIT_RADIUS, JUDGE_DAMAGE, player);
                n++;
            }
        }.runTaskTimer(plugin, 10L, 4L);
    }
}
