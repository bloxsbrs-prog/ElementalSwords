package com.example.elementalswords;

import net.kyori.adventure.text.format.NamedTextColor;

/** The five elemental swords. modelId must match the resource pack. */
public enum SwordType {
    FIRE("fire", "Fire Sword", NamedTextColor.GOLD, 1),
    ICE("ice", "Frost Sword", NamedTextColor.AQUA, 2),
    LIGHTNING("lightning", "Thunder Sword", NamedTextColor.YELLOW, 3),
    EARTH("earth", "Earth Sword", NamedTextColor.GREEN, 4),
    WIND("wind", "Wind Sword", NamedTextColor.WHITE, 5);

    private final String id;
    private final String displayName;
    private final NamedTextColor color;
    private final int modelId;

    SwordType(String id, String displayName, NamedTextColor color, int modelId) {
        this.id = id;
        this.displayName = displayName;
        this.color = color;
        this.modelId = modelId;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public NamedTextColor color() { return color; }
    public int modelId() { return modelId; }

    public static SwordType byId(String id) {
        for (SwordType type : values()) {
            if (type.id.equalsIgnoreCase(id)) return type;
        }
        return null;
    }
}
