package com.wiyuka.acceleratedrecoiling.engine;

import com.wiyuka.acceleratedrecoiling.config.FoldConfig;
import com.wiyuka.acceleratedrecoiling.natives.TempID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.function.Predicate;

/**
 * 把「整箱实体查询」接到原生空间索引上。
 *
 * <p>为什么是它：密集场景下每次 {@code getEntities(EntityTypeTest, AABB, Predicate)} 都要在 Java 里
 * 遍历整个区段的实体表——{@code tryCast} + {@code getBoundingBox} + {@code intersects} + 消费者调用，
 * 全是逐实体的虚调用。实测 2600 只猪的场景里这条路径每 tick 被调用近九千次，是「实体 tick 里
 * 位移之外」那二十几毫秒的主要来源之一。原生侧的同名查询在打包好的区段成员表上做 4 宽 SIMD
 * 包围盒相交，且按原版的 section + 插入序输出，正是这条路径需要的形态。
 *
 * <p>逐条对齐原版语义（{@code Level.getEntities(EntityTypeTest, AABB, Predicate, List, int)}）：
 * 每个候选先过 {@code tryCast}、再判包围盒相交、再走谓词，命中才进输出；
 * 输出长度达到 {@code maxResults} 立即停止遍历。
 *
 * <p>不接管的情形一律原样回退，绝不猜：
 * <ul>
 *   <li>开关关闭、非服务端、不在服务端 tick 内（保证暂存段单线程访问，客户端保持原版）；</li>
 *   <li>该维度没有已建立的帧（实体密度低于阈值时引擎根本不建帧）；</li>
 *   <li>该维度存在 PartEntity（原版在主遍历之后还有一轮零件实体，顺序不同，交回原版处理）；</li>
 *   <li>原生返回负值（容量不足或内部失败）。</li>
 * </ul>
 *
 * <p>已知差异：帧内的包围盒是 tick 开始时采集的，而本 tick 内实体移动后的位置不会反映进来，
 * 因此恰好落在查询盒边缘的实体判定可能与原版不同。这与引擎的推挤路径同源——帧本来就是
 * 每 tick 一份快照。传感器盒（十几格）相对每 tick 位移（不足一格）大得多，实际影响面很小，
 * 但这是事实上的行为差异，需要时可以关掉 {@code enableEntityGetterOptimization} 回到原版。
 */
public final class EntityQueryTakeover {

    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
    private static final int[] RETURNED = new int[1];

    private EntityQueryTakeover() {
    }

    /**
     * 尝试用原生索引完成这次查询。
     *
     * @return true 表示输出已按原版语义填好，调用方应跳过原版方法体
     */
    public static boolean fill(Level level, EntityTypeTest test, AABB bounds,
                               Predicate predicate, List output, int maxResults) {
        if (!FoldConfig.enableEntityGetterOptimization) {
            return false;
        }
        if (!(level instanceof ServerLevel server)) {
            return false;
        }
        // 只在服务端 tick 内接管：帧与暂存段都不是线程安全的，客户端保持原版也没有表现差异
        if (!TickStats.isActive()) {
            return false;
        }
        if (!server.getPartEntities().isEmpty()) {
            return false;
        }
        EcoFrame frame = EcoFrame.of(server);
        if (frame == null) {
            return false;
        }

        MemorySegment ids;
        try {
            ids = frame.queryBoxIds(bounds.minX, bounds.minY, bounds.minZ,
                    bounds.maxX, bounds.maxY, bounds.maxZ, RETURNED);
        } catch (Throwable t) {
            return false;
        }
        if (ids == null) {
            return false;
        }

        int returned = RETURNED[0];
        for (int i = 0; i < returned; i++) {
            Entity entity = TempID.getEntity(ids.get(INT, (long) i * Integer.BYTES));
            if (entity == null || entity.isRemoved() || entity.level() != level) {
                continue;
            }
            if (test.tryCast(entity) == null) {
                continue;
            }
            if (!entity.getBoundingBox().intersects(bounds)) {
                continue;
            }
            if (!predicate.test(entity)) {
                continue;
            }
            output.add(entity);
            if (output.size() >= maxResults) {
                break;
            }
        }
        return true;
    }
}
