package com.wiyuka.acceleratedrecoiling.kernel;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.function.Predicate;

/**
 * Stateless entry point for the PARITY kernel of the merged optimizer.
 *
 * The PARITY kernel reproduces the vanilla push candidate set and order on a
 * per-server-level basis by reading the authoritative vanilla section storage
 * (see {@link ParityContext#collectPushCandidates}). It provides the behaviour
 * parity guarantee of Entity Collision Optimizer without requiring the native
 * library, and is used automatically when the native AVX2 backend is not
 * available in AUTO mode.
 */
public final class ParityQuery {

    private ParityQuery() {
    }

    /**
     * Returns push candidates for {@code source} inside {@code area} in the same set and order
     * vanilla {@code Level#getEntities} would produce for {@code LivingEntity.pushEntities}.
     */
    public static List<Entity> pushable(Entity source, AABB area, Predicate<? super Entity> predicate) {
        if (!(source.level() instanceof ServerLevel serverLevel)) {
            return List.of();
        }
        return ParityContext.get(serverLevel).collectPushCandidates(source, area, predicate);
    }
}