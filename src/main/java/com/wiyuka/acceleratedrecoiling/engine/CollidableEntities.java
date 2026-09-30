package com.wiyuka.acceleratedrecoiling.engine;

import net.minecraft.server.level.ServerLevel;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 每 tick 扫描结果：该维度是否存在「可被碰撞」的实体。
 *
 * <p>为什么需要它：{@code Entity.collide} 里那句
 * {@code level.getEntityCollisions(self, box)} 的谓词是
 * {@code other.canBeCollidedWith() && !self.isPassengerOfSameVehicle(other)}，
 * 而 {@code Entity.canBeCollidedWith()} 默认返回 <b>false</b>，全原版只有 {@code Boat} 与
 * {@code Shulker} 覆写为 true。**所以只要全维度没有这类实体，这次查询的结果可证明为空。**
 *
 * <p>而「可证明为空」并不等于「便宜」：该查询内部会走
 * {@code Level.getEntities} → 遍历查询盒所覆盖区段里的**整张实体表**再全部筛掉。
 * 实测 2600 实体场景单次 19.4 us，每 tick 两千多次就是 40 ms——而返回 0 个。
 * 密集场景下这是纯浪费，所以扫一次全维度把这种情况直接跳过。
 *
 * <p>一次 O(实体数) 的扫描（复用 tick HEAD 已有的实体遍历）换掉每 tick 上千次区段遍历。
 * 没扫描过（例如引擎未接管）时返回 false，即照常走原版查询，绝不擅自跳过。
 */
public final class CollidableEntities {

    private static final Map<ServerLevel, Boolean> PRESENT = new IdentityHashMap<>();

    private CollidableEntities() {
    }

    /** tick HEAD 扫描时调用。 */
    public static void update(ServerLevel level, boolean present) {
        PRESENT.put(level, present);
    }

    /**
     * @return true 表示本 tick 已扫描过、且确认该维度没有任何可被碰撞的实体——
     * 此时 {@code getEntityCollisions} 的结果可证明为空，可以安全跳过整个查询
     */
    public static boolean definitelyAbsent(ServerLevel level) {
        return Boolean.FALSE.equals(PRESENT.get(level));
    }

    /** 维度卸载时清理。 */
    public static void remove(ServerLevel level) {
        PRESENT.remove(level);
    }

    /** 服务器停机时清理全部（这张表强引用维度对象，不清就留着）。 */
    public static void removeAll() {
        PRESENT.clear();
    }
}
