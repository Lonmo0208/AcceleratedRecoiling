package com.wiyuka.acceleratedrecoiling.engine;

import com.wiyuka.acceleratedrecoiling.api.ICustomData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Java mirror of {@code eco::CollisionBody} (eco-native/src/motion/collision_body.h):
 * 80-byte rows; slot i == TempID frame index i. The segment is passed by pointer to
 * {@code executePushRun}, which mutates vx/vy/vz in place and bumps {@code velocityVersion}
 * per applied impulse.
 *
 * <p>Dirty-tracking trick: {@code VELOCITY_VERSION} doubles as the entity's last-synced
 * {@code ecoVelVersion}. On every row write the field is set to the entity version, so a row
 * touched by the native engine diverges and is rewritten before the next run. A rewrite
 * re-anchors the row to the entity's current velocity, making the post-run read-back an exact
 * "current velocity + impulses" that can be applied via {@link Entity#setDeltaMovement}.
 */
public final class EcoBodies {

    public static final long STRIDE = 104;
    public static final long X = 0;
    public static final long Z = 8;
    public static final long VX = 16;
    public static final long VY = 24;
    public static final long VZ = 32;
    public static final long VELOCITY_VERSION = 40;
    public static final long STATE = 48;
    public static final long ROOT = 52;
    public static final long SYNC = 56;
    /** (teamId<<2)|rule 打包，供全量推 executeFullPushRun 的 team 过滤使用。 */
    public static final long RESERVED = 60;
    public static final long Y = 64;
    public static final long POSITION_VERSION = 72;
    /** 包围盒半尺寸（x/z/y），全量推用 AABB 相交拒绝。 */
    public static final long HALF_W = 80;
    public static final long HALF_D = 88;
    public static final long HALF_H = 96;

    public static final int PUSHABLE = 1;
    public static final int VEHICLE = 2;
    public static final int PASSENGER = 4;
    public static final int SLEEPING = 8;
    public static final int NO_PHYSICS = 16;

    private static long ecoVersion(Entity entity) {
        return ((ICustomData) entity).getEcoVelVersion();
    }

    private Arena arena;
    private MemorySegment memory = MemorySegment.NULL;
    private int capacity;

    public int capacity() {
        return capacity;
    }

    public MemorySegment memory() {
        return memory;
    }

    public MemorySegment row(int slot) {
        return memory.asSlice((long) slot * STRIDE, STRIDE);
    }

    /** Grows the backing allocation, preserving live rows. */
    public void ensureCapacity(int required) {
        if (required <= capacity) {
            return;
        }
        int grown = Math.max(required, capacity == 0 ? 256 : capacity + (capacity >> 1) + 128);
        Arena nextArena = Arena.ofShared();
        MemorySegment next = nextArena.allocate((long) grown * STRIDE, Double.BYTES);
        if (arena != null) {
            MemorySegment.copy(memory, 0, next, 0, memory.byteSize());
            arena.close();
        }
        arena = nextArena;
        memory = next;
        capacity = grown;
    }

    /** Fresh-frame write: position, velocity, state, root; sync cleared. */
    public void writeRow(int slot, Entity entity, int state, int root) {
        long o = (long) slot * STRIDE;
        memory.set(JAVA_DOUBLE, o + X, entity.getX());
        memory.set(JAVA_DOUBLE, o + Z, entity.getZ());
        memory.set(JAVA_DOUBLE, o + Y, entity.getY());
        Vec3 v = entity.getDeltaMovement();
        memory.set(JAVA_DOUBLE, o + VX, v.x);
        memory.set(JAVA_DOUBLE, o + VY, v.y);
        memory.set(JAVA_DOUBLE, o + VZ, v.z);
        memory.set(JAVA_INT, o + STATE, state);
        memory.set(JAVA_INT, o + ROOT, root);
        memory.set(JAVA_INT, o + SYNC, 0);
        memory.set(JAVA_LONG, o + VELOCITY_VERSION, ecoVersion(entity));
        memory.set(JAVA_LONG, o + POSITION_VERSION, 0L);
    }

    /** Rewrites the velocity fields only when the row is stale relative to the entity. */
    public void refreshVelocity(int slot, Entity entity) {
        long o = (long) slot * STRIDE;
        long version = ecoVersion(entity);
        if (memory.get(JAVA_LONG, o + VELOCITY_VERSION) == version) {
            return;
        }
        Vec3 v = entity.getDeltaMovement();
        memory.set(JAVA_DOUBLE, o + VX, v.x);
        memory.set(JAVA_DOUBLE, o + VY, v.y);
        memory.set(JAVA_DOUBLE, o + VZ, v.z);
        memory.set(JAVA_LONG, o + VELOCITY_VERSION, version);
    }

    /** Writes the reserved pack (teamId<<2)|rule; the full push run reads it for team filtering. */
    public void writeReserved(int slot, int teamId, int rule) {
        memory.set(JAVA_INT, (long) slot * STRIDE + RESERVED, (teamId << 2) | rule);
    }

    /** Writes the bounding-box half extents (x/z/y); the full push run rejects non-overlapping pairs. */
    public void writeExtents(int slot, double halfW, double halfD, double halfH) {
        long o = (long) slot * STRIDE;
        memory.set(JAVA_DOUBLE, o + HALF_W, halfW);
        memory.set(JAVA_DOUBLE, o + HALF_D, halfD);
        memory.set(JAVA_DOUBLE, o + HALF_H, halfH);
    }

    /** Post-run read-back; returns the row's current velocity for the given slot. */
    public Vec3 velocity(int slot) {
        long o = (long) slot * STRIDE;
        return new Vec3(
                memory.get(JAVA_DOUBLE, o + VX),
                memory.get(JAVA_DOUBLE, o + VY),
                memory.get(JAVA_DOUBLE, o + VZ)
        );
    }

    public double positionX(int slot) {
        return memory.get(JAVA_DOUBLE, (long) slot * STRIDE + X);
    }

    public double positionZ(int slot) {
        return memory.get(JAVA_DOUBLE, (long) slot * STRIDE + Z);
    }

    /** Row x/z velocity (no Vec3 allocation); used by the selective full-push write-back. */
    public double velocityX(int slot) {
        return memory.get(JAVA_DOUBLE, (long) slot * STRIDE + VX);
    }

    public double velocityZ(int slot) {
        return memory.get(JAVA_DOUBLE, (long) slot * STRIDE + VZ);
    }

    public void close() {
        if (arena != null) {
            arena.close();
            arena = null;
        }
        memory = MemorySegment.NULL;
        capacity = 0;
    }
}