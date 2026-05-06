package com.example.randomclass;

public enum PlayerClass {
    WARRIOR("전사"),
    MAGE("마법사"),
    ADVENTURER("모험가"),
    NONE("백수");

    private final String displayName;

    PlayerClass(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
