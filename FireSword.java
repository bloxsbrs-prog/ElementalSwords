package com.example.elementalswords;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One elemental sword. Every sword has the same five controls:
 *   hit               -> onHit
 *   right-click air   -> primary
 *   right-click block -> ground
 *   sneak + right-click -> shield (toggle)
 *   sneak + swing     -> ultimate
 */
abstract class ElementSword implements Listener {

    protected final ElementalSwordsPlugin plugin;
    protected final SwordType type;
    private final Map<UUID, BukkitRunnable> shields = new HashMap<>();

    protected ElementSword(ElementalSwordsPlugin plugin, SwordType type) {
        this.plugin = plugin;
        this.type = type;
    }

    abstract List<Component> lore();

    abstract void onHit(Player player, LivingEntity victim);

    abstract void primary(Player player);

    abstract void ground(Player player, Block clicked, BlockFace face);

    abstract void ultimate(Player player);

    // ---- shield: the base class runs the timer, each sword fills in the details ----

    abstract String shieldName();

    abstract int shieldTicks();

    abstract long shieldCooldownMs();

    abstract void shieldTick(Player player, int tick);

    void shieldStart(Player player) {}

    void shieldEnd(Player player) {}

    final void toggleShield(Player player) {
        UUID id = player.getUniqueId();
        if (shields.containsKey(id)) {
            stopShield(player);   // sneak + right-click again ends it early
            return;
        }
        if (!plugin.cooldowns().tryUse(player, type.id() + ":shield", shieldCooldownMs(), shieldName())) return;

        shieldStart(player);
        player.sendActionBar(Component.text(shieldName() + " active!", type.color()));

        BukkitRunnable task = new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead() || ticks >= shieldTicks()) {
                    stopShield(player);
                    return;
                }
                shieldTick(player, ticks);
                ticks++;
            }
        };
        task.runTaskTimer(plugin, 0L, 1L);
        shields.put(id, task);
    }

    final void stopShield(Player player) {
        BukkitRunnable task = shields.remove(player.getUniqueId());
        if (task == null) return;
        task.cancel();
        shieldEnd(player);
        if (player.isOnline()) {
            player.sendActionBar(Component.text(shieldName() + " ended", NamedTextColor.GRAY));
        }
    }

    final void cleanup(Player player) {
        stopShield(player);
    }

    final void cleanupAll() {
        for (BukkitRunnable task : shields.values()) {
            task.cancel();
        }
        shields.clear();
    }

    static Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
