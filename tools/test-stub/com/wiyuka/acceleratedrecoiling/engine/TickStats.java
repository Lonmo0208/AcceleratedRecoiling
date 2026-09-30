package com.wiyuka.acceleratedrecoiling.engine;

/**
 * 离线对照测试用的空实现，只为了让 {@link MovementCollision} 能单独编译。
 * 测试不经过 {@code collide} 的计时分支，这里的空实现不影响任何结论。
 */
public final class TickStats {

    public static boolean isActive() {
        return false;
    }

    public static void collideGather(long nanos, int returned, int boxes) {
    }

    public static void collideBlock(long nanos) {
    }

    public static void collideClip(long nanos) {
    }

    private TickStats() {
    }
}
