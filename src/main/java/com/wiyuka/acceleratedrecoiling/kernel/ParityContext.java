package com.wiyuka.acceleratedrecoiling.kernel;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.function.Predicate;

/**
 * Per-server-level context of the merged optimizer's PARITY kernel.
 *
 * <p>The PARITY kernel reproduces the vanilla push candidate set and order by delegating the
 * query to the exact vanilla structures {@code ServerLevel#getEntities()} (a
 * {@link LevelEntityGetter} backed by the vanilla {@code PersistentEntitySectionManager}) and the
 * NeoForge {@code getPartEntities()} walk — the same two sources {@code Level#getEntities(Entity,
 * AABB, Predicate)} uses inside {@code LivingEntity.pushEntities}. This is the guarantee the
 * vanilla-order semantics of Entity Collision Optimizer are built on: the same sections in the
 * same order, the same per-entity {@code getBoundingBox().intersects(bounds)} filter, the same
 * self-exclusion, and the same caller predicate.</p>
 */
public final class ParityContext {

    private final ServerLevel level;
    private final LevelEntityGetter<Entity> entityGetter;

    private ParityContext(ServerLevel level) {
        this.level = level;
        this.entityGetter = level.getEntities();
    }

    public static ParityContext get(ServerLevel level) {
        return Internal.INSTANCES.computeIfAbsent(level, ParityContext::new);
    }

    /** 服务端停止 / 维度卸载时释放全部缓存，防止 ServerLevel 引用泄漏。 */
    public static void invalidateAll() {
        Internal.INSTANCES.clear();
    }

    public LevelEntityGetter<Entity> entityGetter() {
        return entityGetter;
    }

    /**
     * Collects push candidates for {@code source} inside {@code area} in exactly the same set,
     * order and filters as vanilla <code>Level#getEntities(source, area, predicate)</code>.
     *
     * <p>The interesting placeholder: because all entities of a section are delivered in vanilla
     * section traversal order (x ascending, then section id order) and the per-section entity
     * iteration stays inside the vanilla store, the produced list is bit-for-bit equal to the list
     * vanilla {@code pushEntities} would iterate, so squeeze behaviour cannot diverge.</p>
     */
    public List<Entity> collectPushCandidates(Entity source, AABB area, Predicate<? super Entity> predicate) {
        List<Entity> result = new java.util.ArrayList<>();
        entityGetter.get(area, entity -> {
            if (entity != source && predicate.test(entity)) {
                result.add(entity);
            }
        });
        for (net.neoforged.neoforge.entity.PartEntity<?> part : level.getPartEntities()) {
            if (part != source && part.getBoundingBox().intersects(area) && predicate.test(part)) {
                result.add(part);
            }
        }
        return result;
    }

    private static final class Internal {
        private static final java.util.concurrent.ConcurrentHashMap<ServerLevel, ParityContext> INSTANCES =
                new java.util.concurrent.ConcurrentHashMap<>();
    }
}