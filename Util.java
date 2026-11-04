package com.example.elementalswords;

import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/** Small helpers shared by all the swords. */
final class Util {

    private Util() {}

    /** True if the target is wearing at least one piece of armor. */
    static boolean hasArmor(LivingEntity target) {
        EntityEquipment equipment = target.getEquipment();
        if (equipment == null) return false;
        for (ItemStack piece : equipment.getArmorContents()) {
            if (piece != null && !piece.getType().isAir()) return true;
        }
        return false;
    }

    /** Something an ability is allowed to hurt: a living thing that isn't the caster, an armor stand or a spectator. */
    static boolean isTarget(Entity e, Player caster) {
        if (!(e instanceof LivingEntity)) return false;
        if (e instanceof ArmorStand) return false;
        if (e.isDead()) return false;
        if (caster != null && e.getUniqueId().equals(caster.getUniqueId())) return false;
        if (e instanceof Player p && p.getGameMode() == GameMode.SPECTATOR) return false;
        return true;
    }

    /** Everything an ability may hurt within r blocks of a point (measured to the middle of the body). */
    static List<LivingEntity> inRadius(Location center, double r, Player caster) {
        List<LivingEntity> out = new ArrayList<>();
        World world = center.getWorld();
        for (Entity e : world.getNearbyEntities(center, r, r, r)) {
            if (!isTarget(e, caster)) continue;
            LivingEntity target = (LivingEntity) e;
            if (target.getBoundingBox().getCenter().distance(center.toVector()) > r) continue;
            out.add(target);
        }
        return out;
    }

    /** How far a beam from the player's eyes goes before it hits a solid wall. */
    static double beamRange(Player player, double max) {
        Location eye = player.getEyeLocation();
        RayTraceResult wall = player.getWorld().rayTraceBlocks(eye, eye.getDirection(), max, FluidCollisionMode.NEVER, true);
        return wall != null ? wall.getHitPosition().distance(eye.toVector()) : max;
    }

    /** Everything inside a straight beam from the player's eyes (it pierces). */
    static List<LivingEntity> inBeam(Player player, double range, double width) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Location mid = eye.clone().add(dir.clone().multiply(range / 2));
        double reach = range / 2 + 2;
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : player.getWorld().getNearbyEntities(mid, reach, reach, reach)) {
            if (!isTarget(e, player)) continue;
            LivingEntity target = (LivingEntity) e;
            Vector toTarget = target.getBoundingBox().getCenter().subtract(eye.toVector());
            double along = toTarget.dot(dir);
            if (along < 0 || along > range) continue;
            double off = toTarget.clone().subtract(dir.clone().multiply(along)).length();
            if (off > width) continue;
            out.add(target);
        }
        return out;
    }

    /** The spot on the ground the player is looking at (stops at mobs and walls, drops to the floor if aimed at the sky). */
    static Location groundTarget(Player player, double range) {
        Location eye = player.getEyeLocation();
        World world = player.getWorld();
        RayTraceResult hit = world.rayTrace(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true, 0.5,
                e -> isTarget(e, player));
        if (hit != null) {
            return hit.getHitPosition().toLocation(world);
        }
        Location end = eye.clone().add(eye.getDirection().multiply(range));
        RayTraceResult ground = world.rayTraceBlocks(end, new Vector(0, -1, 0), 64, FluidCollisionMode.NEVER, true);
        return ground != null ? ground.getHitPosition().toLocation(world) : end;
    }

    /** A flat (no up/down) direction of length 1; never returns a zero vector. */
    static Vector flat(Vector v) {
        Vector f = new Vector(v.getX(), 0, v.getZ());
        if (f.lengthSquared() < 1.0E-6) return new Vector(0, 0, 1);
        return f.normalize();
    }

    /** A line of particles between two points. */
    static void line(World world, Location from, Location to, Particle particle, double spacing) {
        Vector v = to.toVector().subtract(from.toVector());
        double length = v.length();
        if (length < 0.01) return;
        v.normalize();
        for (double d = 0; d <= length; d += spacing) {
            Location p = from.clone().add(v.clone().multiply(d));
            world.spawnParticle(particle, p, 1, 0, 0, 0, 0);
        }
    }
}
