package com.example.gesturefeed;

/** Available camera recognition strategies. */
public enum GestureMode {
    PALM_SWING("手掌挥动", "张开手掌，快速向上或向下挥动"),
    TWO_FINGER_DIRECTION("双指指向", "食指和中指同时指向上方或下方"),
    MIDDLE_FINGER_DIRECTION("中指指向", "仅伸出中指，指向上方或下方"),
    CUSTOM_GESTURE("自定义手势", "录入上、下、左、右四个动作"),
    CUSTOM_VOICE("自定义声音", "录入上、下、左、右四个口令");

    private final String label;
    private final String description;

    GestureMode(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }

    public static GestureMode fromStoredValue(String value) {
        if (value == null) return PALM_SWING;
        try {
            return valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return PALM_SWING;
        }
    }
}
