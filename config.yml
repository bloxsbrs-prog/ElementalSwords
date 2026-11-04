package com.example.elementalswords;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-player, per-ability cooldowns. */
final class Cooldowns {

    private final Map<UUID, Map<String, Long>> ready = new HashMap<>();

    /** Returns true and starts the cooldown if the ability is ready; otherwise tells the player how long is left. */
    boolean tryUse(Player player, String key, long cooldownMs, String label) {
        long now = System.currentTimeMillis();
        Map<String, Long> map = ready.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>());
        long readyAt = map.getOrDefault(key, 0L);
        if (now < readyAt) {
            player.sendActionBar(Component.text(label + " cooldown: " + formatTime(readyAt - now), NamedTextColor.RED));
            return false;
        }
        map.put(key, now + cooldownMs);
        return true;
    }

    static String formatTime(long ms) {
        long total = (ms + 999) / 1000;
        long minutes = total / 60;
        long seconds = total % 60;
        return minutes > 0 ? minutes + "m " + seconds + "s" : seconds + "s";
    }
}
