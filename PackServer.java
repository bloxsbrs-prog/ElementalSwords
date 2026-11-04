package com.example.elementalswords;

import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Serves the resource pack that is bundled inside the plugin jar and sends it to players who join. */
final class PackServer {

    private static final String PACK_FILE = "ElementalSwordsPack.zip";

    private final ElementalSwordsPlugin plugin;
    private HttpServer server;
    private ExecutorService executor;
    private byte[] packBytes;
    private byte[] packSha1;
    private boolean warnedNoHost = false;

    PackServer(ElementalSwordsPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        if (!plugin.getConfig().getBoolean("resource-pack.enabled", true)) return;
        if (!plugin.getConfig().getString("resource-pack.external-url", "").isBlank()) return;

        try (InputStream in = plugin.getResource(PACK_FILE)) {
            if (in == null) {
                plugin.getLogger().warning(PACK_FILE + " is missing from the plugin jar; no resource pack will be sent.");
                return;
            }
            packBytes = in.readAllBytes();
            packSha1 = MessageDigest.getInstance("SHA-1").digest(packBytes);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read the bundled resource pack: " + e.getMessage());
            return;
        }

        int port = plugin.getConfig().getInt("resource-pack.port", 8163);
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/" + PACK_FILE, exchange -> {
                try (exchange) {
                    exchange.getResponseHeaders().add("Content-Type", "application/zip");
                    exchange.sendResponseHeaders(200, packBytes.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(packBytes);
                    }
                }
            });
            executor = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "ElementalSwords-PackServer");
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(executor);
            server.start();
            plugin.getLogger().info("Resource pack is being served on port " + port
                    + ". Make sure that port is open/allocated to your server.");
        } catch (IOException | LinkageError e) {
            plugin.getLogger().warning("Could not start the resource pack server on port " + port + " (" + e
                    + "). Change resource-pack.port in config.yml to a free port.");
            server = null;
        }
    }

    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("resource-pack.enabled", true)) return;

        final String url;
        byte[] hash = null;

        String external = plugin.getConfig().getString("resource-pack.external-url", "").trim();
        if (!external.isEmpty()) {
            url = external;   // pack hosted somewhere else
            String sha1 = plugin.getConfig().getString("resource-pack.external-sha1", "").trim();
            if (!sha1.isEmpty()) {
                try {
                    hash = HexFormat.of().parseHex(sha1);
                    if (hash.length != 20) throw new IllegalArgumentException("wrong length");
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("resource-pack.external-sha1 must be 40 hex characters; sending the pack without it.");
                    hash = null;
                }
            }
        } else {
            if (server == null) return;
            String host = plugin.getConfig().getString("resource-pack.host", "").trim();
            if (host.isEmpty()) {
                // Use the same address the player typed to join the server
                InetSocketAddress joined = event.getPlayer().getVirtualHost();
                if (joined != null) host = joined.getHostString();
            }
            if (host == null || host.isEmpty()) {
                if (!warnedNoHost) {
                    warnedNoHost = true;
                    plugin.getLogger().warning("Can't tell which address to send the resource pack from. Set resource-pack.host in config.yml to your server's IP or domain.");
                }
                return;
            }
            if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";   // IPv6
            url = "http://" + host + ":" + plugin.getConfig().getInt("resource-pack.port", 8163) + "/" + PACK_FILE;
            hash = packSha1;
        }

        boolean force = plugin.getConfig().getBoolean("resource-pack.force", false);
        Component prompt = Component.text(
                plugin.getConfig().getString("resource-pack.prompt", "Elemental Swords resource pack"), NamedTextColor.GOLD);

        final byte[] finalHash = hash;
        Player player = event.getPlayer();
        // Wait one second so the player is fully loaded in before the download prompt appears
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                player.setResourcePack(url, finalHash, prompt, force);
            }
        }, 20L);
    }
}
