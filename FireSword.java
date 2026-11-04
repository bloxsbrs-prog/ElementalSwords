package com.example.elementalswords;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.List;

/** Fire Sword: explosive fireballs, ground fire, Fire Shield and Fire Boom. */
final class FireSword extends ElementSword {

    // ---- Tweak these (20 damage = 10 hearts) ----
    private static final int BURN_TICKS = 100;                // 5 seconds of burning

    // Fireballs (right-click air): 3 fireballs that explode on impact
    private static final long FIREBALL_COOLDOWN_MS = 3000;
    private static final int FIREBALL_COUNT = 3;
    private static final double FIREBALL_SPREAD_DEGREES = 8;
    private static final double FIREBALL_DAMAGE = 10.0;       // 5 hearts per fireball
    private static final double FIREBALL_BLAST_RADIUS = 4.5;
    private static final float FIREBALL_BLOCK_POWER = 4.0f;   // how much ground it blows up (4 = TNT)
    private static final double FIREBALL_SPACING = 1.4;

    // Fire Shield (sneak + right-click)
    private static final int SHIELD_TICKS = 200;              // lasts 10 seconds
    private static final long SHIELD_COOLDOWN_MS = 20000;
    private static final double SHIELD_RADIUS = 3.0;
    private static final double SHIELD_DAMAGE = 40.0;         // 20 hearts per hit
    private static final int SHIELD_INTERVAL_TICKS = 10;      // twice a second

    // Fire Boom (sneak + hit)
    private static final long BOOM_COOLDOWN_MS = 90000;       // 1 minute 30 seconds
    private static final double BOOM_DAMAGE = 30.0;           // 15 hearts
    private static final double BOOM_RANGE = 30.0;
    private static final double BOOM_WIDTH = 1.75;

    private final NamespacedKey fireballKey;
    private boolean inFireballBlast = false;   // true only while our own explosion is being created

    FireSword(ElementalSwordsPlugin plugin) {
        super(plugin, SwordType.FIRE);
        this.fireballKey = new NamespacedKey(plugin, "fire_sword_fireball");
    }

    @Override
    List<Component> lore() {
        return List.of(
                line("Ignites enemies on hit", NamedTextColor.RED),
                line("Right-click air: 3 super explosive fireballs", NamedTextColor.YELLOW),
                line("Right-click ground: set it ablaze", NamedTextColor.YELLOW),
                line("Sneak + right-click: Fire Shield", NamedTextColor.GOLD),
                line("Sneak + hit: Fire Boom (1m 30s cooldown)", NamedTextColor.DARK_RED));
    }

    @Override
    void onHit(Player player, LivingEntity victim) {
        victim.setFireTicks(BURN_TICKS);
        victim.getWorld().spawnParticle(Particle.FLAME, victim.getLocation().add(0, 1, 0), 25, 0.3, 0.5, 0.3, 0.02);
        victim.getWorld().playSound(victim.getLocation(), Sound.ITEM_FIRECHARGE_USE, 1f, 1f);
    }

    // ---------------------------------------------------------------- fireballs

    @Override
    void primary(Player player) {
        if (!plugin.cooldowns().tryUse(player, "fire:fireball", FIREBALL_COOLDOWN_MS, "Fireball")) return;

        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();

        // Sideways direction, so the fireballs start side by side instead of stacked on one spot
        Vector side = new Vector(-direction.getZ(), 0, direction.getX());
        if (side.lengthSquared() < 1.0E-6) side = new Vector(1, 0, 0);   // looking straight up/down
        side.normalize();

        for (int i = 0; i < FIREBALL_COUNT; i++) {
            double slot = i - (FIREBALL_COUNT - 1) / 2.0;                 // -1, 0, +1 for 3 fireballs
            Vector aim = direction.clone().rotateAroundY(Math.toRadians(slot * FIREBALL_SPREAD_DEGREES));
            Location spawnAt = eye.clone()
                    .add(direction.clone().multiply(1.3))
                    .add(side.clone().multiply(slot * FIREBALL_SPACING));

            player.getWorld().spawn(spawnAt, Fireball.class, fireball -> {
                fireball.setShooter(player);
                fireball.setYield(0f);              // no vanilla explosion; we make our own on impact
                fireball.setIsIncendiary(false);
                fireball.setDirection(aim);
                fireball.setVelocity(aim.clone().multiply(1.2));
                fireball.getPersistentDataContainer().set(fireballKey, PersistentDataType.BYTE, (byte) 1);
            });
        }
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1f, 0.8f);
    }

    /** Each fireball explodes where it lands: blows up the ground and hurts everything in the radius. */
    @EventHandler
    public void onFireballHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Fireball fireball)) return;
        if (!fireball.getPersistentDataContainer().has(fireballKey, PersistentDataType.BYTE)) return;

        Player shooter = fireball.getShooter() instanceof Player p ? p : null;

        // Fireballs must never blow up on each other or on the player who fired them
        Entity hitEntity = event.getHitEntity();
        if (hitEntity != null) {
            boolean otherFireball = hitEntity instanceof Fireball other
                    && other.getPersistentDataContainer().has(fireballKey, PersistentDataType.BYTE);
            boolean theShooter = shooter != null && hitEntity.getUniqueId().equals(shooter.getUniqueId());
            if (otherFireball || theShooter) {
                event.setCancelled(true);   // fly straight through
                return;
            }
        }

        event.setCancelled(true);   // skip the vanilla impact so the damage is exactly what we set

        Location loc = fireball.getLocation();
        World world = loc.getWorld();
        fireball.remove();

        // Real explosion that blows holes in the ground. Its own damage is switched off below
        // so the only damage is our exact 5 hearts per fireball.
        inFireballBlast = true;
        try {
            Entity source = shooter;
            world.createExplosion(source, loc, FIREBALL_BLOCK_POWER, false, true);
        } finally {
            inFireballBlast = false;
        }

        world.spawnParticle(Particle.FLAME, loc, 200, 2.0, 1.5, 2.0, 0.12);
        world.spawnParticle(Particle.LAVA, loc, 40, 2.0, 1.0, 2.0, 0);
        world.playSound(loc, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 2f, 1f);

        for (LivingEntity target : Util.inRadius(loc, FIREBALL_BLAST_RADIUS, shooter)) {
            target.setFireTicks(BURN_TICKS);
            plugin.abilityDamage(target, FIREBALL_DAMAGE, shooter, true);   // full 5 hearts, armor doesn't reduce it
        }
    }

    /** While a fireball's explosion is being created, ignore the vanilla explosion damage (we deal our own). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onExplosionDamage(EntityDamageEvent event) {
        if (!inFireballBlast) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- ground fire

    @Override
    void ground(Player player, Block clicked, BlockFace face) {
        if (clicked == null) return;

        Block spot = clicked.getRelative(face);
        tryIgnite(player, spot);

        // Clicking the top of a block lights a small plus-shaped patch
        if (face == BlockFace.UP) {
            tryIgnite(player, spot.getRelative(BlockFace.NORTH));
            tryIgnite(player, spot.getRelative(BlockFace.SOUTH));
            tryIgnite(player, spot.getRelative(BlockFace.EAST));
            tryIgnite(player, spot.getRelative(BlockFace.WEST));
        }
        player.getWorld().playSound(spot.getLocation(), Sound.ITEM_FLINTANDSTEEL_USE, 1f, 1f);
    }

    private void tryIgnite(Player player, Block block) {
        if (!block.getType().isAir()) return;
        if (!block.getRelative(BlockFace.DOWN).getType().isSolid()) return;

        // Fire the vanilla event first so protection plugins can say no
        BlockIgniteEvent ignite = new BlockIgniteEvent(block, BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL, player);
        plugin.getServer().getPluginManager().callEvent(ignite);
        if (ignite.isCancelled()) return;

        block.setType(Material.FIRE);
        block.getWorld().spawnParticle(Particle.FLAME, block.getLocation().add(0.5, 0.3, 0.5), 8, 0.2, 0.1, 0.2, 0.01);
    }

    // ---------------------------------------------------------------- fire boom

    @Override
    void ultimate(Player player) {
        if (!plugin.cooldowns().tryUse(player, "fire:boom", BOOM_COOLDOWN_MS, "Fire Boom")) return;

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World world = player.getWorld();

        double range = Util.beamRange(player, BOOM_RANGE);   // the beam stops at the first solid wall

        world.playSound(eye, Sound.ENTITY_WARDEN_SONIC_BOOM, 3f, 1f);
        world.playSound(eye, Sound.ENTITY_BLAZE_SHOOT, 2f, 0.5f);

        // Beam visuals: a line of flames with sonic-boom rings along it
        int step = 0;
        for (double d = 1.0; d <= range; d += 0.5, step++) {
            Location p = eye.clone().add(dir.clone().multiply(d));
            world.spawnParticle(Particle.FLAME, p, 6, 0.15, 0.15, 0.15, 0.02);
            if (step % 6 == 0) {
                world.spawnParticle(Particle.SONIC_BOOM, p, 1);
            }
        }
        Location end = eye.clone().add(dir.clone().multiply(range));
        world.spawnParticle(Particle.EXPLOSION, end, 1);
        world.playSound(end, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);

        // Hit everything along the beam (it pierces)
        for (LivingEntity target : Util.inBeam(player, range, BOOM_WIDTH)) {
            target.setFireTicks(BURN_TICKS * 2);
            plugin.abilityDamage(target, BOOM_DAMAGE, player);
            target.setVelocity(dir.clone().multiply(2.0).setY(0.6));   // blast them back
        }
    }

    // ---------------------------------------------------------------- fire shield

    @Override
    String shieldName() {
        return "Fire Shield";
    }

    @Override
    int shieldTicks() {
        return SHIELD_TICKS;
    }

    @Override
    long shieldCooldownMs() {
        return SHIELD_COOLDOWN_MS;
    }

    @Override
    void shieldStart(Player player) {
        // Fire immunity so your own shield (and lava, fire, etc.) can't hurt you while it's up
        player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, SHIELD_TICKS + 10, 0, false, false));
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_FIRECHARGE_USE, 1f, 0.6f);
    }

    @Override
    void shieldEnd(Player player) {
        player.removePotionEffect(PotionEffectType.FIRE_RESISTANCE);
    }

    @Override
    void shieldTick(Player player, int tick) {
        Location center = player.getLocation();
        World world = player.getWorld();
        double angle = tick * 0.3;

        // Ring of flame on the ground around you
        for (int i = 0; i < 16; i++) {
            double a = angle + i * (Math.PI * 2 / 16);
            world.spawnParticle(Particle.FLAME,
                    center.getX() + Math.cos(a) * SHIELD_RADIUS,
                    center.getY() + 0.1,
                    center.getZ() + Math.sin(a) * SHIELD_RADIUS,
                    1, 0, 0, 0, 0);
        }

        // Three tall flames orbiting you
        for (int i = 0; i < 3; i++) {
            double a = -angle * 1.5 + i * (Math.PI * 2 / 3);
            double height = 0.5 + 0.5 * (1 + Math.sin(angle * 2 + i));
            world.spawnParticle(Particle.FLAME,
                    center.getX() + Math.cos(a) * (SHIELD_RADIUS - 0.5),
                    center.getY() + height,
                    center.getZ() + Math.sin(a) * (SHIELD_RADIUS - 0.5),
                    3, 0.1, 0.1, 0.1, 0.01);
        }

        // Burn and hit everything inside the ring, twice a second
        if (tick % SHIELD_INTERVAL_TICKS == 0) {
            for (LivingEntity target : Util.inRadius(center, SHIELD_RADIUS, player)) {
                target.setFireTicks(BURN_TICKS);
                plugin.abilityDamage(target, SHIELD_DAMAGE, player);
            }
        }
    }
}
