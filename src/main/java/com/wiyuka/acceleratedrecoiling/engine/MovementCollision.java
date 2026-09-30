package com.wiyuka.acceleratedrecoiling.engine;

import com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling;
import com.wiyuka.acceleratedrecoiling.config.FoldConfig;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@code Entity.collide} 的等价实现：结构照抄原版，只把**方块侧的裁剪与台阶**换成原生求解。
 *
 * <p>走过的弯路（都写在这里，避免重犯）：
 * <ul>
 *   <li>曾把「邻近实体包围盒包装成 VoxelShape」当成热点去做闭式裁剪——实测那是 0.04 ms/tick 的空表，
 *       收益为零；</li>
 *   <li>曾绕开原版入口直接调 {@code Level.getEntities} 拿实体——那条路径每次 24 us，
 *       2204 次就是 52 ms，直接把 tick 从 50 推到 98。原版入口 {@code getEntityCollisions}
 *       有它自己的短路，实测 18 ns/次。<b>要拿实体就走原版入口，不要自己去查。</b></li>
 * </ul>
 *
 * <p>现在只保留真正有收益的那一半：方块形状仍由原版 {@code getBlockCollisions} 收集，
 * 但逐轴裁剪 + 台阶候选交给原生 {@code solveMovement}（离线 42 万例逐位对拍、
 * 游戏内 2131 例对拍均为 0 不一致；实测 0.61 ms 对 Java 的 6.15 ms）。
 */
public final class MovementCollision {

    /** {@code (double) (float) -1.0E-5F}：未下落时台阶扫描盒向下多探的那一点。 */
    private static final double STEP_SINK = -9.999999747378752E-6;

    private static final double EPS = 1.0E-7;

    private static int parityLogged;

    /** 每次调用复用同一组缓冲。包内可见以便离线对照测试。 */
    static final class Scratch {
        final List<VoxelShape> entityShapes = new ArrayList<>();
        final List<VoxelShape> blocks = new ArrayList<>();
        final List<VoxelShape> all = new ArrayList<>();
        final List<VoxelShape> stepShapes = new ArrayList<>();
        /** 原生台阶阶段的形状（与 Java 路径的 stepShapes 分开，避免互相清空）。 */
        final List<VoxelShape> nativeStepShapes = new ArrayList<>();
        final StepSource stepSource = new StepSource();
        float[] heights = new float[64];
    }

    /** 原生台阶阶段按扫描盒重新收集形状；复用同一实例，避免每次调用产生闭包对象。 */
    static final class StepSource implements NativeMovement.StepShapeSource {
        Entity self;
        Level level;
        Scratch scratch;

        @Override
        public List<VoxelShape> shapesFor(AABB box) {
            scratch.nativeStepShapes.clear();
            scratch.nativeStepShapes.addAll(scratch.entityShapes);
            // 原版这里会对 aabb2 重新取一遍世界边界与方块碰撞形状
            collectBlockShapes(self, level, box, scratch.nativeStepShapes);
            return scratch.nativeStepShapes;
        }
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private MovementCollision() {
    }

    /**
     * @return 与 {@code Entity.collide} 相同的结果；无法保证等价时返回 null
     */
    public static Vec3 collide(Entity self, Vec3 movement) {
        // 只接管服务端：客户端保持原版没有表现差异，也避免两遍开销与共享缓冲的线程问题
        if (!(self.level() instanceof ServerLevel server)) {
            return null;
        }
        Scratch scratch = SCRATCH.get();
        Level level = self.level();
        AABB bounds = self.getBoundingBox();
        AABB entityQuery = bounds.expandTowards(movement);
        boolean timing = TickStats.isActive();
        long mark = timing ? System.nanoTime() : 0L;

        // 实体形状：全维度没有可被碰撞的实体（Boat/Shulker 那类）时结果可证明为空，直接跳过。
        // 该查询内部会遍历查询盒覆盖的整张区段实体表再全部筛掉——实测密集场景单次 19.4 us、
        // 每 tick 两千多次约 40 ms，而返回 0 个。没扫描过时（例如引擎未接管）照常走原版查询。
        scratch.entityShapes.clear();
        if (!CollidableEntities.definitelyAbsent(server)) {
            scratch.entityShapes.addAll(level.getEntityCollisions(self, entityQuery));
        }
        if (timing) {
            long now = System.nanoTime();
            TickStats.collideGather(now - mark, scratch.entityShapes.size(), scratch.entityShapes.size());
            mark = now;
        }

        // 请求位移为零时原版直接返回请求向量，不碰方块形状，也不进台阶分支
        if (movement.lengthSqr() == 0.0) {
            return movement;
        }

        scratch.blocks.clear();
        collectBlockShapes(self, level, entityQuery, scratch.blocks);
        if (timing) {
            long now = System.nanoTime();
            TickStats.collideBlock(now - mark);
            mark = now;
        }

        float maxUpStep = self.maxUpStep();
        boolean onGround = self.onGround();

        scratch.all.clear();
        scratch.all.addAll(scratch.entityShapes);
        scratch.all.addAll(scratch.blocks);
        scratch.stepSource.self = self;
        scratch.stepSource.level = level;
        scratch.stepSource.scratch = scratch;

        // 原生求解：实体盒也能表示（单格长方体），所以与方块形状一起交给它一遍算完
        if (FoldConfig.enableMovementTakeover && !FoldConfig.debugMovementParity) {
            Vec3 solved = NativeMovement.solve(bounds, movement, scratch.all, maxUpStep, onGround,
                    scratch.stepSource);
            if (solved != null) {
                if (timing) {
                    TickStats.collideClip(System.nanoTime() - mark);
                }
                return solved;
            }
        }

        Vec3 result = collideWithShapes(movement, bounds, scratch, scratch.all, maxUpStep, onGround, self, level);

        if (FoldConfig.enableMovementTakeover && FoldConfig.debugMovementParity && EcoEngine.isAvailable()) {
            long nativeStart = System.nanoTime();
            Vec3 solved = NativeMovement.solve(bounds, movement, scratch.all, maxUpStep, onGround,
                    scratch.stepSource);
            long nativeNanos = System.nanoTime() - nativeStart;
            if (solved != null) {
                boolean mismatch = !NativeMovement.sameBits(solved, result);
                TickStats.movementParity(true, nativeNanos, mismatch);
                if (mismatch && parityLogged < 5) {
                    parityLogged++;
                    AcceleratedRecoiling.LOGGER.warn(
                            "ECO movement parity mismatch: java={} native={} request={} box={} shapes={}",
                            result, solved, movement, bounds, scratch.all.size());
                }
            } else {
                TickStats.movementParity(false, nativeNanos, false);
            }
        }

        if (timing) {
            TickStats.collideClip(System.nanoTime() - mark);
        }
        return result;
    }

    /**
     * 与 {@code collectColliders} 同序：先世界边界，再方块碰撞形状。
     * 实体形状由调用方先行放入列表。
     */
    private static void collectBlockShapes(Entity self, Level level, AABB box, List<VoxelShape> out) {
        WorldBorder border = level.getWorldBorder();
        if (border.isInsideCloseToBorder(self, box)) {
            out.add(border.getCollisionShape());
        }
        for (VoxelShape shape : level.getBlockCollisions(self, box)) {
            out.add(shape);
        }
    }

    /**
     * {@code Entity.collide} 的方块那一半：{@code collideWithShapes} 加台阶循环，逐行对齐原版。
     * 形状表用原版 {@code VoxelShape.collide} 裁剪。
     */
    private static Vec3 collideWithShapes(Vec3 movement, AABB bounds, Scratch scratch,
                                          List<VoxelShape> shapes, float maxUpStep, boolean onGround,
                                          Entity self, Level level) {
        Vec3 result = clipPlain(movement, bounds, shapes);
        boolean hitX = movement.x != result.x;
        boolean hitZ = movement.z != result.z;
        boolean landed = movement.y != result.y && movement.y < 0.0;
        if (maxUpStep > 0.0F && (landed || onGround) && (hitX || hitZ)) {
            AABB stepBase = landed ? bounds.move(0.0, result.y, 0.0) : bounds;
            AABB scan = stepBase.expandTowards(movement.x, maxUpStep, movement.z);
            if (!landed) {
                scan = scan.expandTowards(0.0, STEP_SINK, 0.0);
            }
            scratch.stepShapes.clear();
            scratch.stepShapes.addAll(scratch.entityShapes);
            collectBlockShapes(self, level, scan, scratch.stepShapes);
            int candidates = collectStepHeights(stepBase, scratch.stepShapes, maxUpStep, (float) result.y);
            for (int i = 0; i < candidates; i++) {
                Vec3 lifted = clipPlain(new Vec3(movement.x, scratch.heights[i], movement.z),
                        stepBase, scratch.stepShapes);
                if (lifted.horizontalDistanceSqr() > result.horizontalDistanceSqr()) {
                    // 原版的台阶结果要把抬升前的高度差补回去
                    return lifted.add(0.0, -(bounds.minY - stepBase.minY), 0.0);
                }
            }
        }
        return result;
    }

    /**
     * {@code collideWithShapes} 的逐行转写：轴上依次裁剪，轴间按已裁出的位移推移包围盒。
     * 轴序与原版一致——先 Y，再看 |x| 与 |z| 谁小决定 X/Z 的先后。
     */
    private static Vec3 clipPlain(Vec3 movement, AABB bounds, List<VoxelShape> shapes) {
        if (shapes.isEmpty()) {
            return movement;
        }
        double x = movement.x;
        double y = movement.y;
        double z = movement.z;
        AABB box = bounds;
        if (y != 0.0) {
            y = clip(Direction.Axis.Y, box, shapes, y);
            if (y != 0.0) {
                box = box.move(0.0, y, 0.0);
            }
        }
        boolean zBeforeX = Math.abs(x) < Math.abs(z);
        if (zBeforeX && z != 0.0) {
            z = clip(Direction.Axis.Z, box, shapes, z);
            if (z != 0.0) {
                box = box.move(0.0, 0.0, z);
            }
        }
        if (x != 0.0) {
            x = clip(Direction.Axis.X, box, shapes, x);
            if (!zBeforeX && x != 0.0) {
                box = box.move(x, 0.0, 0.0);
            }
        }
        if (!zBeforeX && z != 0.0) {
            z = clip(Direction.Axis.Z, box, shapes, z);
        }
        return new Vec3(x, y, z);
    }

    /** 与 {@code Shapes.collide} 一致：每个形状之前先做一次 1e-7 提前返回。 */
    private static double clip(Direction.Axis axis, AABB box, List<VoxelShape> shapes, double distance) {
        double value = distance;
        for (int i = 0, size = shapes.size(); i < size; i++) {
            if (Math.abs(value) < EPS) {
                return 0.0;
            }
            value = shapes.get(i).collide(axis, box, value);
        }
        return value;
    }

    /**
     * {@code collectCandidateStepUpHeights} 的等价物：把落在 [0, 最大台阶高度] 且不等于当前下落量的
     * 高度收集起来，去重后升序返回。原版用 FloatSet 去重再排序，这里先排序再去重，集合内容相同。
     *
     * @return 候选个数，结果写在 {@code scratch.heights}
     */
    static int collectStepHeights(AABB base, List<VoxelShape> shapes, float maxUpStep, float clippedY) {
        Scratch scratch = SCRATCH.get();
        float[] heights = scratch.heights;
        int count = 0;
        for (int i = 0, size = shapes.size(); i < size; i++) {
            for (double coordinate : shapes.get(i).getCoords(Direction.Axis.Y)) {
                float height = (float) (coordinate - base.minY);
                if (!(height < 0.0F) && height != clippedY) {
                    if (height > maxUpStep) {
                        break;
                    }
                    if (count == heights.length) {
                        heights = scratch.heights = Arrays.copyOf(heights, count * 2);
                    }
                    heights[count++] = height;
                }
            }
        }
        if (count == 0) {
            return 0;
        }
        Arrays.sort(heights, 0, count);
        int unique = 1;
        for (int i = 1; i < count; i++) {
            if (heights[i] != heights[unique - 1]) {
                heights[unique++] = heights[i];
            }
        }
        return unique;
    }
}
