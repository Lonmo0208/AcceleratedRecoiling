package com.wiyuka.acceleratedrecoiling.engine;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 离线差分测试：把 {@link BoxClip} 与 {@link MovementCollision} 的裁剪结果，逐位对拍原版
 * {@code Shapes.create(AABB).collide(...)} / {@code Shapes.collide(...)} 以及
 * {@code Entity.collideWithShapes} 的逐行转写。
 *
 * <p>只在开发机上跑，不进 jar。任何一处不等都会打印出输入与两个结果。
 */
public final class MovementCollisionParityTest {

    private static int checks = 0;
    private static int failures = 0;
    private static int clipped = 0;

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 20260917L;
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 200000;
        Random random = new Random(seed);

        singleShapeClip(random, rounds);
        classifyRules();
        signedZero();
        clipSequence(random, rounds / 4);
        clipPlainAgainstVanilla(random, rounds / 4);
        stepHeights(random, rounds / 8);

        System.out.println("checks=" + checks + " failures=" + failures + " clippedCases=" + clipped);
        if (failures != 0 || clipped == 0) {
            System.exit(1);
        }
    }

    /** 空形状表时原版原样返回请求向量，连负零的符号都要保住。 */
    private static void signedZero() {
        Vec3[] movements = {
                new Vec3(-0.0, -0.0, -0.0),
                new Vec3(0.0, 0.0, 0.0),
                new Vec3(-0.0, 1.0, -0.0),
                new Vec3(1.0E-9, -0.0, 2.0),
        };
        AABB bounds = new AABB(10.0, 20.0, 30.0, 10.9, 20.9, 30.9);
        for (Vec3 movement : movements) {
            MovementCollision.Scratch scratch = new MovementCollision.Scratch();
            checkVec("signedZero", movement, MovementCollision.clipPlain(movement, bounds, scratch, List.of()),
                    "movement=" + movement);
        }
    }

    // ---------------------------------------------------------------- 单项裁剪

    private static void singleShapeClip(Random random, int rounds) {
        double[] raw = new double[6];
        double[] values = {0.0, -0.0, 1.0E-7, -1.0E-7, 9.999999747378752E-6, 0.5, 0.6, 1.0, -1.0};
        for (int i = 0; i < rounds; i++) {
            AABB shape = randomEntityBox(random);
            double distance = pickDistance(random, values, shape);
            AABB moving = randomMovingBox(random, shape);

            for (Direction.Axis axis : Direction.Axis.values()) {
                raw[0] = shape.minX; raw[1] = shape.minY; raw[2] = shape.minZ;
                raw[3] = shape.maxX; raw[4] = shape.maxY; raw[5] = shape.maxZ;
                double expected = Shapes.create(shape).collide(axis, moving, distance);
                double actual = BoxClip.clip(axis, moving, raw, 0, distance);
                check("clip", axis, distance, expected, actual, shape + " | " + moving);
            }
        }
    }

    private static void classifyRules() {
        // 世界坐标：一定走出界分支
        expectClassify(new AABB(100.25, 64.0, -30.5, 101.15, 64.9, -29.6), BoxClip.FAST);
        // 完全落在 [0,1] 内：形状未必是单满格子，必须交回原版
        expectClassify(new AABB(0.25, 0.25, 0.25, 0.75, 0.75, 0.75), BoxClip.UNSUPPORTED);
        // 退化：原版 Shapes.create 得到空形状
        expectClassify(new AABB(5.0, 5.0, 5.0, 5.0, 6.0, 6.0), BoxClip.EMPTY);
        expectClassify(new AABB(5.0, 5.0, 5.0, 6.0, 5.0, 6.0), BoxClip.EMPTY);
        // 出界与退化同时存在时以退化为准
        expectClassify(new AABB(100.0, 5.0, 5.0, 100.0, 6.0, 6.0), BoxClip.EMPTY);
        // 一根轴刚出界即视为单满格子
        expectClassify(new AABB(-1.0E-6, 0.2, 0.2, 0.8, 0.8, 0.8), BoxClip.FAST);
    }

    private static void expectClassify(AABB box, int expected) {
        double[] raw = {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ};
        int actual = BoxClip.classify(raw, 0);
        if (actual != expected) {
            failures++;
            System.out.println("classify mismatch: " + box + " expected=" + expected + " actual=" + actual);
        }
        checks++;
    }

    // ------------------------------------------------------ 序列裁剪与提前返回

    private static void clipSequence(Random random, int rounds) {
        for (int i = 0; i < rounds; i++) {
            int count = random.nextInt(5);
            List<AABB> boxes = new ArrayList<>();
            List<VoxelShape> shapes = new ArrayList<>();
            for (int j = 0; j < count; j++) {
                AABB box = random.nextBoolean() ? randomEntityBox(random) : randomDegenerateBox(random);
                boxes.add(box);
                shapes.add(Shapes.create(box));
            }
            AABB moving = randomMovingBox(random, randomEntityBox(random));
            double distance = random.nextBoolean()
                    ? (random.nextDouble() - 0.5) * 2.0
                    : (random.nextDouble() - 0.5) * 1.0E-5;

            for (Direction.Axis axis : Direction.Axis.values()) {
                MovementCollision.Scratch scratch = new MovementCollision.Scratch();
                for (AABB box : boxes) {
                    int offset = scratch.boxCount * 6;
                    if (offset + 6 > scratch.boxes.length) {
                        scratch.boxes = Arrays.copyOf(scratch.boxes, scratch.boxes.length * 2);
                    }
                    scratch.boxes[offset] = box.minX;
                    scratch.boxes[offset + 1] = box.minY;
                    scratch.boxes[offset + 2] = box.minZ;
                    scratch.boxes[offset + 3] = box.maxX;
                    scratch.boxes[offset + 4] = box.maxY;
                    scratch.boxes[offset + 5] = box.maxZ;
                    int kind = BoxClip.classify(scratch.boxes, offset);
                    if (kind == BoxClip.FAST) {
                        scratch.boxCount++;
                    } else if (kind == BoxClip.EMPTY) {
                        scratch.degenerate.add(Shapes.empty());
                    } else {
                        throw new IllegalStateException("测试生成器不该产出无法判定的盒子");
                    }
                }
                double expected = Shapes.collide(axis, moving, shapes, distance);
                double actual = MovementCollision.clip(axis, moving, scratch, List.of(), distance);
                check("clipSequence", axis, distance, expected, actual, boxes + " | " + moving);
            }
        }
    }

    // ---------------------------------------------- 整体裁剪对拍 collideWithShapes

    private static void clipPlainAgainstVanilla(Random random, int rounds) {
        for (int i = 0; i < rounds; i++) {
            int entityCount = random.nextInt(4);
            MovementCollision.Scratch scratch = new MovementCollision.Scratch();
            List<VoxelShape> shapes = new ArrayList<>();
            for (int j = 0; j < entityCount; j++) {
                AABB box = random.nextBoolean() ? randomEntityBox(random) : randomDegenerateBox(random);
                appendEntity(scratch, shapes, box);
            }
            List<VoxelShape> blocks = new ArrayList<>();
            int blockCount = random.nextInt(4);
            for (int j = 0; j < blockCount; j++) {
                VoxelShape shape = randomBlockShape(random, null);
                blocks.add(shape);
                shapes.add(shape);
            }
            AABB bounds = randomEntityBox(random);
            Vec3 movement = randomMovement(random);

            Vec3 expected = vanillaCollideWithShapes(movement, bounds, shapes);
            Vec3 actual = MovementCollision.clipPlain(movement, bounds, scratch, blocks);
            checkVec("clipPlain", expected, actual,
                    "movement=" + movement + " bounds=" + bounds + " entities=" + entityCount + " blocks=" + blockCount);

            // 台阶扫描盒只影响方块那一半，这里把整体入口也压一遍：实体个数为 0 且方块为空时
            // 原版会原样返回请求向量，连负零都要保住
            if (entityCount == 0 && blockCount == 0) {
                MovementCollision.Scratch empty = new MovementCollision.Scratch();
                checkVec("clipPlainEmpty", movement, MovementCollision.clipPlain(movement, bounds, empty, List.of()),
                        "movement=" + movement);
            }
        }
    }

    private static void appendEntity(MovementCollision.Scratch scratch, List<VoxelShape> shapes, AABB box) {
        int offset = scratch.boxCount * 6;
        if (offset + 6 > scratch.boxes.length) {
            scratch.boxes = Arrays.copyOf(scratch.boxes, scratch.boxes.length * 2);
        }
        scratch.boxes[offset] = box.minX;
        scratch.boxes[offset + 1] = box.minY;
        scratch.boxes[offset + 2] = box.minZ;
        scratch.boxes[offset + 3] = box.maxX;
        scratch.boxes[offset + 4] = box.maxY;
        scratch.boxes[offset + 5] = box.maxZ;
        int kind = BoxClip.classify(scratch.boxes, offset);
        if (kind == BoxClip.FAST) {
            scratch.boxCount++;
        } else if (kind == BoxClip.EMPTY) {
            scratch.degenerate.add(Shapes.empty());
        } else {
            throw new IllegalStateException("测试生成器不该产出无法判定的盒子");
        }
        shapes.add(Shapes.create(box));
    }

    // ------------------------------------------------------------------ 台阶高度

    private static void stepHeights(Random random, int rounds) {
        for (int i = 0; i < rounds; i++) {
            MovementCollision.Scratch scratch = new MovementCollision.Scratch();
            List<VoxelShape> shapes = new ArrayList<>();
            int entityCount = random.nextInt(4);
            for (int j = 0; j < entityCount; j++) {
                appendEntity(scratch, shapes, randomEntityBox(random));
            }
            List<VoxelShape> blocks = new ArrayList<>();
            for (int j = 0, n = random.nextInt(4); j < n; j++) {
                VoxelShape shape = randomBlockShape(random, null);
                blocks.add(shape);
                shapes.add(shape);
            }
            AABB base = randomEntityBox(random);
            float maxUpStep = (float) (random.nextBoolean() ? 0.6 : 1.0);
            float clippedY = (float) ((random.nextDouble() - 0.5) * 2.0);

            float[] expected = vanillaStepHeights(base, shapes, maxUpStep, clippedY);
            int count = MovementCollision.collectStepHeights(base, scratch, blocks, maxUpStep, clippedY);
            float[] actual = Arrays.copyOf(scratch.heights, count);
            if (!Arrays.equals(expected, actual)) {
                failures++;
                System.out.println("stepHeights mismatch: expected=" + Arrays.toString(expected)
                        + " actual=" + Arrays.toString(actual) + " | base=" + base
                        + " maxUpStep=" + maxUpStep + " clippedY=" + clippedY);
            }
            checks++;
        }
    }

    // ------------------------------------------------------------ 原版逐行转写

    /** 原版 {@code Entity.collideWithShapes} 的逐行转写，作为对拍真值。 */
    private static Vec3 vanillaCollideWithShapes(Vec3 requested, AABB box, List<VoxelShape> shapes) {
        if (shapes.isEmpty()) {
            return requested;
        }
        double d0 = requested.x;
        double d1 = requested.y;
        double d2 = requested.z;
        if (d1 != 0.0) {
            d1 = Shapes.collide(Direction.Axis.Y, box, shapes, d1);
            if (d1 != 0.0) {
                box = box.move(0.0, d1, 0.0);
            }
        }
        boolean flag = Math.abs(d0) < Math.abs(d2);
        if (flag && d2 != 0.0) {
            d2 = Shapes.collide(Direction.Axis.Z, box, shapes, d2);
            if (d2 != 0.0) {
                box = box.move(0.0, 0.0, d2);
            }
        }
        if (d0 != 0.0) {
            d0 = Shapes.collide(Direction.Axis.X, box, shapes, d0);
            if (!flag && d0 != 0.0) {
                box = box.move(d0, 0.0, 0.0);
            }
        }
        if (!flag && d2 != 0.0) {
            d2 = Shapes.collide(Direction.Axis.Z, box, shapes, d2);
        }
        return new Vec3(d0, d1, d2);
    }

    /**
     * 原版 {@code Entity.collectCandidateStepUpHeights} 的转写。原版用 FloatSet 去重再排序，
     * 这里排序后按 {@code ==} 去重，得到同一串互不相同的升序高度。
     */
    private static float[] vanillaStepHeights(AABB box, List<VoxelShape> shapes, float maxUpStep, float clippedY) {
        List<Float> raw = new ArrayList<>();
        for (VoxelShape shape : shapes) {
            for (double coordinate : shape.getCoords(Direction.Axis.Y)) {
                float height = (float) (coordinate - box.minY);
                if (!(height < 0.0F) && height != clippedY) {
                    if (height > maxUpStep) {
                        break;
                    }
                    raw.add(height);
                }
            }
        }
        if (raw.isEmpty()) {
            return new float[0];
        }
        float[] sorted = new float[raw.size()];
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = raw.get(i);
        }
        Arrays.sort(sorted);
        int unique = 1;
        for (int i = 1; i < sorted.length; i++) {
            if (sorted[i] != sorted[unique - 1]) {
                sorted[unique++] = sorted[i];
            }
        }
        return Arrays.copyOf(sorted, unique);
    }

    // ------------------------------------------------------------------ 数据生成

    private static AABB randomEntityBox(Random random) {
        double x = (random.nextDouble() - 0.5) * 4000.0;
        double y = random.nextDouble() * 300.0;
        double z = (random.nextDouble() - 0.5) * 4000.0;
        double w = 0.2 + random.nextDouble() * 1.6;
        double h = 0.2 + random.nextDouble() * 2.4;
        double d = 0.2 + random.nextDouble() * 1.6;
        return new AABB(x, y, z, x + w, y + h, z + d);
    }

    private static AABB randomDegenerateBox(Random random) {
        AABB base = randomEntityBox(random);
        return switch (random.nextInt(3)) {
            case 0 -> new AABB(base.minX, base.minY, base.minZ, base.minX, base.maxY, base.maxZ);
            case 1 -> new AABB(base.minX, base.minY, base.minZ, base.maxX, base.minY, base.maxZ);
            default -> new AABB(base.minX, base.minY, base.minZ, base.maxX, base.maxY, base.minZ);
        };
    }

    /** 让移动盒骑在形状的两侧，正负两个方向都压到。 */
    private static AABB randomMovingBox(Random random, AABB shape) {
        double jitter = switch (random.nextInt(4)) {
            case 0 -> 0.0;
            case 1 -> 1.0E-7;
            case 2 -> -1.0E-7;
            default -> (random.nextDouble() - 0.5) * 0.2;
        };
        double x = shape.minX + (random.nextDouble() - 0.5) * 2.5 + jitter;
        double y = shape.minY + (random.nextDouble() - 0.5) * 2.5 + jitter;
        double z = shape.minZ + (random.nextDouble() - 0.5) * 2.5 + jitter;
        return new AABB(x, y, z, x + 0.6 + random.nextDouble() * 0.6,
                y + 0.9 + random.nextDouble() * 1.0, z + 0.6 + random.nextDouble() * 0.6);
    }

    private static double pickDistance(Random random, double[] values, AABB shape) {
        int roll = random.nextInt(6);
        if (roll == 0) {
            return values[random.nextInt(values.length)];
        }
        if (roll == 1) {
            // 正好落在贴合位置附近，专门压 1e-7 容差
            return (random.nextBoolean() ? 1.0 : -1.0)
                    * (shape.maxX + (random.nextDouble() - 0.5) * 1.0E-6);
        }
        return (random.nextDouble() - 0.5) * 2.0;
    }

    private static Vec3 randomMovement(Random random) {
        double scale = random.nextBoolean() ? 1.0 : 1.0E-6;
        return new Vec3((random.nextDouble() - 0.5) * 2.0 * scale,
                (random.nextDouble() - 0.5) * 2.0 * scale,
                (random.nextDouble() - 0.5) * 2.0 * scale);
    }

    private static VoxelShape randomBlockShape(Random random, AABB near) {
        if (near != null && random.nextInt(4) == 0) {
            return Shapes.empty();
        }
        double x = random.nextDouble() * 2.0 - 0.5;
        double y = random.nextDouble() * 2.0 - 0.5;
        double z = random.nextDouble() * 2.0 - 0.5;
        return Shapes.box(x, y, z, x + random.nextDouble(), y + random.nextDouble(), z + random.nextDouble());
    }

    // -------------------------------------------------------------------- 判定

    private static void check(String tag, Direction.Axis axis, double distance,
                              double expected, double actual, String context) {
        checks++;
        if (expected != distance) {
            clipped++;
        }
        if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(actual)) {
            failures++;
            if (failures <= 20) {
                System.out.println(tag + " mismatch axis=" + axis + " distance=" + distance
                        + " expected=" + expected + " actual=" + actual + " | " + context);
            }
        }
    }

    private static void checkVec(String tag, Vec3 expected, Vec3 actual, String context) {
        checks++;
        if (Double.doubleToRawLongBits(expected.x) != Double.doubleToRawLongBits(actual.x)
                || Double.doubleToRawLongBits(expected.y) != Double.doubleToRawLongBits(actual.y)
                || Double.doubleToRawLongBits(expected.z) != Double.doubleToRawLongBits(actual.z)) {
            failures++;
            if (failures <= 20) {
                System.out.println(tag + " mismatch expected=" + expected + " actual=" + actual + " | " + context);
            }
        }
    }

    private MovementCollisionParityTest() {
    }
}
