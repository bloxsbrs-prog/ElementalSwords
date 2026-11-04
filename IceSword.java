package com.example.elementalswords;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.List;

/** Frost Sword: freezing hits, Frost Volley, Frost Nova, Blizzard Shield and Absolute Zero. */
final class IceSword extends ElementSword {

    // ---- Tweak these (20 damage = 10 hearts) ----
    private static final int HIT_SLOW_TICKS = 60;             // Slowness II for 3 seconds
    private static final int HIT_FREEZE_TICKS = 140;

    // Frost Volley (right-click air): 5 ice shards
    private static final long VOLLEY_COOLDOWN_MS = 3000;
    private static final int VOLLEY_COUNT = 5;
    private static final double VOLLEY_SPREAD_DEGREES = 7;
    private static final double VOLLEY_DAMAGE = 8.0;          // 4 hearts per shard

    // Frost Nova (right-click ground)
    private static final long NOVA_COOLDOWN_MS = 8000;
    private static final double NOVA_RADIUS = 5.0;
    private static final double NOVA_DAMAGE = 10.0;           // 5 hearts

    // Blizzard Shield (sneak + right-click)
    private static final int SHIELD_TICKS = 200;              // 10 seconds
    private static final long SHIELD_COOLDOWN_MS = 20000;
    private static final double SHIELD_RADIUS = 4.0;
    private static final double SHIELD_DAMAGE = 10.0;         // 5 hearts, twice a second
    private static final int SHIELD_INTERVAL_TICKS = 10;

    // Absolute Zero (sneak + hit)
    private static final long ZERO_COOLDOWN_MS = 90000;       // 1 minute 30 seconds
    private static final double ZERO_DAMAGE = 30.0;           // 15 hearts
    private static final double ZERO_RANGE = 30.0;
    private static final double ZERO_WIDTH = 1.75;

    private final NamespacedKey shardKey;

    IceSword(ElementalSwordsPlugin plugin) {
        super(plugin, SwordType.ICE);
        this.shardKey = new NamespacedKey(plugin, "frost_shard");
    }

    @Override
    List<Component> lore() {
        return List.of(
                line("Hits slow and freeze enemies", NamedTextColor.AQUA),
                line("Right-click air: Frost Volley (5 ice shards)", NamedTextColor.YELLOW),
                line("Right-click ground: Frost Nova", NamedTextColor.YELLOW),
                line("Sneak + right-click: Blizzard Shield", NamedTextColor.AQUA),
                line("Sneak + hit: Absolute Zero (1m 30s cooldown)", NamedTextColor.BLUE));
    }

    private void chill(LivingEntity target, int slowTicks, int slowLevel, int freezeTicks) {
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slowTicks, slowLevel));
        target.setFreezeTicks(Math.max(target.getFreezeTicks(), freezeTicks));
    }

    @Override
    void onHit(Player player, LivingEntity victim) {
        chill(victim, HIT_SLOW_TICKS, 1, HIT_FREEZE_TICKS);
        World world = victim.getWorld();
        world.spawnParticle(Particle.SNOWFLAKE, victim.getLocation().add(0, 1, 0), 25, 0.3, 0.5, 0.3, 0.02);
        world.playSound(victim.getLocation(), Sound.BLOCK_GLASS_BREAK, 0.8f, 1.5f);
    }

    // ---------------------------------------------------------------- frost volley

    @Override
    void primary(Player player) {
        if (!plugin.cooldowns().tryUse(player, "ice:volley", VOLLEY_COOLDOWN_MS, "Frost Volley")) return;

        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();

        for (int i = 0; i < VOLLEY_COUNT; i++) {
            double slot = i - (VOLLEY_COUNT - 1) / 2.0;
            Vector aim = direction.clone().rotateAroundY(Math.toRadians(slot * VOLLEY_SPREAD_DEGREES));
            Location spawnAt = eye.clone().add(direction.clone().multiply(1.2));

            player.getWorld().spawn(spawnAt, Snowball.class, shard -> {
                shard.setShooter(player);
                shard.setItem(new ItemStack(Material.BLUE_ICE));   // looks like a flying ice chunk
                shard.setVelocity(aim.clone().multiply(1.9));
                shard.getPersistentDataContainer().set(shardKey, PersistentDataType.BYTE, (byte) 1);
            });
        }
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_SNOW_GOLEM_SHOOT, 1.2f, 0.7f);
    }

    @EventHandler
    public void onShardHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball shard)) return;
        if (!shard.getPersistentDataContainer().has(shardKey, PersistentDataType.BYTE)) return;

        Player shooter = shard.getShooter() instanceof Player p ? p : null;

        Entity hit = event.getHitEntity();
        if (hit != null && !Util.isTarget(hit, shooter)) {
            event.setCancelled(true);   // fly through the shooter, armor stands, etc.
            return;
        }

        Location loc = shard.getLocation();
        World world = loc.getWorld();
        shard.remove();

        world.spawnParticle(Particle.SNOWFLAKE, loc, 25, 0.3, 0.3, 0.3, 0.05);
        world.spawnParticle(Particle.BLOCK, loc, 20, 0.3, 0.3, 0.3, Material.ICE.createBlockData());
        world.playSound(loc, Sound.BLOCK_GLASS_BREAK, 1f, 1.4f);

        if (hit instanceof LivingEntity target) {
            chill(target, 60, 1, 140);
            plugin.abilityDamage(target, VOLLEY_DAMAGE, shooter);
        }
    }

    // ---------------------------------------------------------------- frost nova

    @Override
    void ground(Player player, Block clicked, BlockFace face) {
        if (clicked == null) return;
        if (!plugin.cooldowns().tryUse(player, "ice:nova", NOVA_COOLDOWN_MS, "Frost Nova")) return;

        Location center = clicked.getRelative(face).getLocation().add(0.5, 0.2, 0.5);
        World world = center.getWorld();

        // Expanding ring of snow
        new BukkitRunnable() {
            int step = 0;

            @Override
            public void run() {
                double r = (step + 1) * NOVA_RADIUS / 8.0;
                for (int i = 0; i < 36; i++) {
                    double a = i * (Math.PI * 2 / 36);
                    world.spawnParticle(Particle.SNOWFLAKE,
                            center.getX() + Math.cos(a) * r, center.getY(), center.getZ() + Math.sin(a) * r,
                            1, 0.05, 0.05, 0.05, 0.02);
                }
                step++;
                if (step >= 8) cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);

        world.playSound(center, Sound.BLOCK_GLASS_BREAK, 2f, 0.7f);
        world.playSound(center, Sound.ENTITY_PLAYER_HURT_FREEZE, 2f, 0.8f);

        for (LivingEntity target : Util.inRadius(center, NOVA_RADIUS, player)) {
            chill(target, 80, 2, 200);
            plugin.abilityDamage(target, NOVA_DAMAGE, player);
        }
        freezeWater(center, (int) NOVA_RADIUS);
    }

    /** Turns still water near the nova into frosted ice (it melts back on its own). */
    private void freezeWater(Location center, int radius) {
        World world = center.getWorld();
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                for (int dy = -2; dy <= 1; dy++) {
                    Block block = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (block.getType() != Material.WATER) continue;
                    if (!(block.getBlockData() instanceof Levelled level) || level.getLevel() != 0) continue;

                    BlockState newState = block.getState();
                    newState.setType(Material.FROSTED_ICE);
                    BlockFormEvent form = new BlockFormEvent(block, newState);
                    plugin.getServer().getPluginManager().callEvent(form);   // protection plugins can say no
                    if (!form.isCancelled()) newState.update(true, false);
                }
            }
        }
    }

    // ---------------------------------------------------------------- blizzard shield

    @Override
    String shieldName() {
        return "Blizzard Shield";
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
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, SHIELD_TICKS + 10, 0, false, false));
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_HURT_FREEZE, 1.5f, 0.7f);
    }

    @Override
    void shieldEnd(Player player) {
        player.removePotionEffect(PotionEffectType.RESISTANCE);
    }

    @Override
    void shieldTick(Player player, int tick) {
        Location center = player.getLocation();
        World world = player.getWorld();
        double angle = tick * 0.3;

        // Ring of snow on the ground around you
        for (int i = 0; i < 16; i++) {
            double a = angle + i * (Math.PI * 2 / 16);
            world.spawnParticle(Particle.SNOWFLAKE,
                    center.getX() + Math.cos(a) * SHIELD_RADIUS,
                    center.getY() + 0.1,
                    center.getZ() + Math.sin(a) * SHIELD_RADIUS,
                    1, 0, 0, 0, 0);
        }

        // Three ice crystals orbiting you
        for (int i = 0; i < 3; i++) {
            double a = -angle * 1.5 + i * (Math.PI * 2 / 3);
            double height = 0.5 + 0.5 * (1 + Math.sin(angle * 2 + i));
            world.spawnParticle(Particle.END_ROD,
                    center.getX() + Math.cos(a) * (SHIELD_RADIUS - 0.5),
                    center.getY() + height,
                    center.getZ() + Math.sin(a) * (SHIELD_RADIUS - 0.5),
                    2, 0.1, 0.1, 0.1, 0.01);
        }
        world.spawnParticle(Particle.SNOWFLAKE, center.clone().add(0, 1.5, 0), 6,
                SHIELD_RADIUS / 2, 1.0, SHIELD_RADIUS / 2, 0.01);

        // Freeze and hit everything inside, twice a second
        if (tick % SHIELD_INTERVAL_TICKS == 0) {
            for (LivingEntity target : Util.inRadius(center, SHIELD_RADIUS, player)) {
                chill(target, 60, 3, 200);
                plugin.abilityDamage(target, SHIELD_DAMAGE, player);
            }
        }
    }

    // ---------------------------------------------------------------- absolute zero

    @Override
    void ultimate(Player player) {
        if (!plugin.cooldowns().tryUse(player, "ice:zero", ZERO_COOLDOWN_MS, "Absolute Zero")) return;

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World world = player.getWorld();

        double range = Util.beamRange(player, ZERO_RANGE);

        world.playSound(eye, Sound.ENTITY_WARDEN_SONIC_BOOM, 3f, 1.6f);
        world.playSound(eye, Sound.BLOCK_GLASS_BREAK, 2f, 0.5f);

        int step = 0;
        for (double d = 1.0; d <= range; d += 0.5, step++) {
            Location p = eye.clone().add(dir.clone().multiply(d));
            world.spawnParticle(Particle.SNOWFLAKE, p, 6, 0.15, 0.15, 0.15, 0.02);
            if (step % 3 == 0) {
                world.spawnParticle(Particle.END_ROD, p, 1, 0.1, 0.1, 0.1, 0.01);
            }
        }
        Location end = eye.clone().add(dir.clone().multiply(range));
        world.spawnParticle(Particle.SNOWFLAKE, end, 60, 1.0, 1.0, 1.0, 0.1);
        world.playSound(end, Sound.ENTITY_PLAYER_HURT_FREEZE, 2f, 0.6f);

        // Everything along the beam is frozen solid for a few seconds
        for (LivingEntity target : Util.inBeam(player, range, ZERO_WIDTH)) {
            chill(target, 100, 6, 300);
            plugin.abilityDamage(target, ZERO_DAMAGE, player);
        }
    }
}
