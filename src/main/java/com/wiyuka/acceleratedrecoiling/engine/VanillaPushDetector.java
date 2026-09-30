package com.wiyuka.acceleratedrecoiling.engine;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Reflection-based detection of vanilla overrides, mirroring ECO's
 * {@code VanillaMethodDetector}. The native engine may only batch-push an
 * entity whose push/velocity methods are the vanilla implementations,
 * otherwise it must fall back to {@code LivingEntity#doPush} per candidate.
 */
public final class VanillaPushDetector {

    private VanillaPushDetector() {
    }

    /** Entity's default canBeCollidedWith is always false; boats/shulkers override it. */
    public static boolean usesVanillaCanBeCollidedWith(Entity entity) {
        return USE_VANILLA_CAN_BE_COLLIDED_WITH.get(entity.getClass());
    }

    /** Entity.canCollideWith only keeps hard targets; boats also keep pushable entities. */
    public static boolean usesVanillaCanCollideWith(Entity entity) {
        return USE_VANILLA_CAN_COLLIDE_WITH.get(entity.getClass());
    }

    /** doPush declared on LivingEntity == vanilla (no custom per-mob push). */
    public static boolean usesVanillaDoPush(LivingEntity entity) {
        return USE_VANILLA_DO_PUSH.get(entity.getClass());
    }

    /** push(Entity) declared on Entity or LivingEntity (LivingEntity adds a sleeping guard only). */
    public static boolean usesVanillaEntityPush(Entity entity) {
        return USE_VANILLA_ENTITY_PUSH.get(entity.getClass());
    }

    /** push(double,double,double) and the velocity accessors must all be vanilla. */
    public static boolean usesVanillaVectorPush(Entity entity) {
        Class<?> type = entity.getClass();
        return USE_VANILLA_VECTOR_PUSH.get(type)
                && USE_VANILLA_VELOCITY_GETTER.get(type)
                && USE_VANILLA_VELOCITY_SETTER.get(type);
    }

    private static final ClassValue<Boolean> USE_VANILLA_CAN_BE_COLLIDED_WITH = declaringClass(
            "canBeCollidedWith", Entity.class);
    private static final ClassValue<Boolean> USE_VANILLA_CAN_COLLIDE_WITH = declaringClass(
            "canCollideWith", Entity.class, Entity.class);
    private static final ClassValue<Boolean> USE_VANILLA_VECTOR_PUSH = declaringClass(
            "push", Entity.class, double.class, double.class, double.class);
    private static final ClassValue<Boolean> USE_VANILLA_VELOCITY_GETTER = declaringClass(
            "getDeltaMovement", Entity.class);
    private static final ClassValue<Boolean> USE_VANILLA_VELOCITY_SETTER = declaringClass(
            "setDeltaMovement", Entity.class, Vec3.class);

    private static final ClassValue<Boolean> USE_VANILLA_DO_PUSH = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            Class<?> current = type;
            while (current != null) {
                try {
                    return current.getDeclaredMethod("doPush", Entity.class).getDeclaringClass()
                            == LivingEntity.class;
                } catch (NoSuchMethodException ignored) {
                    current = current.getSuperclass();
                }
            }
            return false;
        }
    };

    private static final ClassValue<Boolean> USE_VANILLA_ENTITY_PUSH = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                try {
                    Class<?> owner = current.getDeclaredMethod("push", Entity.class).getDeclaringClass();
                    return owner == Entity.class || owner == LivingEntity.class;
                } catch (NoSuchMethodException ignored) {
                }
            }
            return false;
        }
    };

    private static ClassValue<Boolean> declaringClass(
            String methodName,
            Class<?> expectedOwner,
            Class<?>... parameterTypes
    ) {
        return new ClassValue<>() {
            @Override
            protected Boolean computeValue(Class<?> type) {
                Class<?> current = type;
                while (current != null) {
                    try {
                        return current.getDeclaredMethod(methodName, parameterTypes).getDeclaringClass()
                                == expectedOwner;
                    } catch (NoSuchMethodException ignored) {
                        current = current.getSuperclass();
                    }
                }
                return false;
            }
        };
    }
}