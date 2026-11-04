package com.example.elementalswords;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ElementalSwordsPlugin extends JavaPlugin implements Listener {

    // ---- Stats shared by every sword ----
    static final double SWORD_ATTACK_DAMAGE = 12.0;   // attack damage on the tooltip (before Sharpness)
    static final double SWORD_ATTACK_SPEED = 1.6;     // same swing speed as a normal sword
    static final int SWORD_SHARPNESS = 7;             // Sharpness level (adds +4 damage at level 7)
    static final double ARMOR_PASS_THROUGH = 0.75;    // armor only stops 25% of ability damage

    private final Map<SwordType, ElementSword> swords = new EnumMap<>(SwordType.class);
    private final Cooldowns cooldowns = new Cooldowns();
    private PackServer packServer;
    private NamespacedKey swordKey;
    private boolean inAbilityDamage = false;

    Cooldowns cooldowns() {
        return cooldowns;
    }

    @Override
    public void onEnable() {
        swordKey = new NamespacedKey(this, "sword_type");
        saveDefaultConfig();

        swords.put(SwordType.FIRE, new FireSword(this));
        swords.put(SwordType.ICE, new IceSword(this));
        swords.put(SwordType.LIGHTNING, new LightningSword(this));
        swords.put(SwordType.EARTH, new EarthSword(this));
        swords.put(SwordType.WIND, new WindSword(this));

        getServer().getPluginManager().registerEvents(this, this);
        for (ElementSword sword : swords.values()) {
            getServer().getPluginManager().registerEvents(sword, this);
        }

        packServer = new PackServer(this);
        packServer.start();
        getLogger().info("ElementalSwords enabled with " + swords.size() + " swords.");
    }

    @Override
    public void onDisable() {
        if (packServer != null) packServer.stop();
        for (ElementSword sword : swords.values()) {
            sword.cleanupAll();
        }
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        SwordType type = null;
        boolean all = false;
        int playerIndex;

        if (label.equalsIgnoreCase("firesword")) {
            type = SwordType.FIRE;   // old /firesword command still works
            playerIndex = 0;
        } else {
            if (args.length == 0) {
                sender.sendMessage(Component.text("Usage: /elementalsword <fire|ice|lightning|earth|wind|all> [player]", NamedTextColor.RED));
                return true;
            }
            if (args[0].equalsIgnoreCase("all")) {
                all = true;
            } else {
                type = SwordType.byId(args[0]);
                if (type == null) {
                    sender.sendMessage(Component.text("Unknown sword. Choose fire, ice, lightning, earth, wind or all.", NamedTextColor.RED));
                    return true;
                }
            }
            playerIndex = 1;
        }

        Player target;
        if (args.length > playerIndex) {
            target = getServer().getPlayerExact(args[playerIndex]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(Component.text("Console must specify a player.", NamedTextColor.RED));
            return true;
        }

        if (all) {
            for (SwordType t : SwordType.values()) {
                target.getInventory().addItem(createSword(t));
            }
            target.sendMessage(Component.text("You received all the elemental swords!", NamedTextColor.GOLD));
        } else {
            target.getInventory().addItem(createSword(type));
            target.sendMessage(Component.text("You received the " + type.displayName() + "!", type.color()));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        boolean oldAlias = alias.equalsIgnoreCase("firesword");
        int playerIndex = oldAlias ? 0 : 1;

        if (!oldAlias && args.length == 1) {
            for (SwordType t : SwordType.values()) options.add(t.id());
            options.add("all");
        } else if (args.length == playerIndex + 1) {
            for (Player p : getServer().getOnlinePlayers()) options.add(p.getName());
        }

        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(typed));
        return options;
    }

    // ---------------------------------------------------------------- item

    ItemStack createSword(SwordType type) {
        ElementSword sword = swords.get(type);
        ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text(type.displayName(), type.color(), TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(sword.lore());
        meta.addEnchant(Enchantment.SHARPNESS, SWORD_SHARPNESS, true);

        // Attack damage 12 (player base is 1, so the modifier adds 11) and normal sword swing speed (base 4 - 2.4 = 1.6)
        meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(
                new NamespacedKey(this, type.id() + "_sword_damage"), SWORD_ATTACK_DAMAGE - 1.0,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        meta.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(
                new NamespacedKey(this, type.id() + "_sword_speed"), SWORD_ATTACK_SPEED - 4.0,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));

        meta.setEnchantmentGlintOverride(true);
        meta.setUnbreakable(true);
        meta.setCustomModelData(type.modelId());   // resource pack swaps in the 3D model
        meta.getPersistentDataContainer().set(swordKey, PersistentDataType.STRING, type.id());

        item.setItemMeta(meta);
        return item;
    }

    /** Which elemental sword (if any) the player is holding. */
    private ElementSword swordIn(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(swordKey, PersistentDataType.STRING);
        if (id == null) return null;
        SwordType type = SwordType.byId(id);
        return type == null ? null : swords.get(type);
    }

    // ---------------------------------------------------------------- damage helper

    /**
     * Ability damage. It uses magic-type damage, which ignores armor, and then takes 25% off
     * only if the target is wearing armor. So armor never blocks more than 25%.
     */
    void abilityDamage(LivingEntity target, double amount, Player attacker) {
        abilityDamage(target, amount, attacker, false);
    }

    /** Same as above, but if fullDamage is true armor doesn't reduce it at all. */
    void abilityDamage(LivingEntity target, double amount, Player attacker, boolean fullDamage) {
        double dealt = (!fullDamage && Util.hasArmor(target)) ? amount * ARMOR_PASS_THROUGH : amount;

        DamageSource.Builder builder = DamageSource.builder(DamageType.MAGIC);
        if (attacker != null) {
            builder.withCausingEntity(attacker).withDirectEntity(attacker);
        }
        target.setNoDamageTicks(0);   // every hit lands in full
        boolean previous = inAbilityDamage;
        inAbilityDamage = true;       // so our own ability damage doesn't trigger the on-hit effects again
        try {
            target.damage(dealt, builder.build());
        } finally {
            inAbilityDamage = previous;
        }
    }

    // ---------------------------------------------------------------- events

    @EventHandler
    public void onHit(EntityDamageByEntityEvent event) {
        if (inAbilityDamage) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        if (!player.hasPermission("elementalswords.use")) return;

        ElementSword sword = swordIn(player);
        if (sword == null) return;
        sword.onHit(player, victim);
    }

    @EventHandler
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (!player.hasPermission("elementalswords.use")) return;
        ElementSword sword = swordIn(player);
        if (sword == null) return;

        event.setCancelled(true);

        if (player.isSneaking()) {
            sword.toggleShield(player);
        } else if (action == Action.RIGHT_CLICK_BLOCK) {
            sword.ground(player, event.getClickedBlock(), event.getBlockFace());
        } else {
            sword.primary(player);
        }
    }

    /** Sneak + swing (hitting air, a block or a mob) fires the sword's ultimate. */
    @EventHandler
    public void onSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;

        Player player = event.getPlayer();
        if (!player.isSneaking()) return;
        if (!player.hasPermission("elementalswords.use")) return;
        ElementSword sword = swordIn(player);
        if (sword == null) return;

        sword.ultimate(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        for (ElementSword sword : swords.values()) {
            sword.cleanup(event.getPlayer());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (packServer != null) packServer.onJoin(event);
    }
}
