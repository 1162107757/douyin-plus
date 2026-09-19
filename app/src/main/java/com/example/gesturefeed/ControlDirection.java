package com.example.gesturefeed;

/** Four directions shared by custom gesture/voice learning and accessibility input. */
public enum ControlDirection {
    UP("上", "下一个视频"),
    DOWN("下", "上一个视频"),
    LEFT("左", "左滑"),
    RIGHT("右", "右滑");

    private final String label;
    private final String action;

    ControlDirection(String label, String action) {
        this.label = label;
        this.action = action;
    }

    public String getLabel() {
        return label;
    }

    public String getAction() {
        return action;
    }

    public boolean isUp() {
        return this == UP;
    }

    public boolean isDown() {
        return this == DOWN;
    }

    public static ControlDirection fromStoredValue(String value) {
        if (value == null) return null;
        try {
            return valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
