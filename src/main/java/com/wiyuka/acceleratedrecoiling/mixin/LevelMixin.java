package com.wiyuka.acceleratedrecoiling.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling;
import com.wiyuka.acceleratedrecoiling.config.FoldConfig;
import com.wiyuka.acceleratedrecoiling.engine.EntityQueryTakeover;
import com.wiyuka.acceleratedrecoiling.engine.TickStats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 只测量、不改语义：累计 {@code Level.getEntities(Entity, AABB, Predicate)} 的调用次数、返回个数与耗时。
 *
 * <p>这条路径是 AABB 实体查询的唯一入口，也是 {@code Entity.getEntityCollisions} 与
 * {@code pushEntities} 当初共用的那一层。它的总账决定了「实体查询到底贵不贵」，
 * 不该靠推测：同一个调用在碰撞解算里只被记到 collide 的耗时里，如果别处也在用，
 * 只盯 collide 就会看漏。
 *
 * <p>谓词与选择器一律原样透传，不改变任何行为。
 */
@Mixin(Level.class)
public abstract class LevelMixin {

    /** HEAD/RETURN 成对计时用；只有正在 tick 的那个线程会读写。 */
    @Unique
    private static long ecoTypeQueryStart;

    /** 影子对拍：HEAD 里算好的原生结果，等原版跑完后在 RETURN 里逐项比对。 */
    @Unique
    private static List ecoParityShadow;
    @Unique
    private static long ecoParityNativeNanos;
    @Unique
    private static long ecoParityVanillaStart;
    /** 不一致的样例只打前几次，避免刷屏。 */
    @Unique
    private static int ecoParityLogged;

    @WrapMethod(
            method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"
    )
    private List<Entity> ecoMeasureAabbQuery(Entity entity, AABB boundingBox,
                                              Predicate<? super Entity> predicate,
                                              Operation<List<Entity>> original) {
        if (!TickStats.isActive()) {
            return original.call(entity, boundingBox, predicate);
        }
        long start = System.nanoTime();
        List<Entity> result = original.call(entity, boundingBox, predicate);
        TickStats.aabbQuery(System.nanoTime() - start, result.size());
        return result;
    }

    /**
     * 接管传感器／目标选择器那条实体查询（{@code getEntities(EntityTypeTest, AABB, Predicate, List, int)}，
     * 传感器、目标选择器、{@code getEntitiesOfClass} 最终都汇到这里）：命中原生空间索引时
     * 直接填好输出并取消原版方法体，同时记账调用次数与耗时。
     *
     * <p>用 HEAD + {@code cancellable} 而不是 {@code @WrapMethod}：参数与目标描述符都取擦除后的
     * 类型（{@code EntityTypeTest} / {@code AABB} / {@code Predicate} / {@code List} / {@code int}），
     * 不使用泛型通配符，注入匹配最稳——这条形状已经在预检里验证过。
     * 输出内容、谓词、{@code maxResults} 的语义全部由 {@link EntityQueryTakeover} 逐条对齐原版。
     *
     * <p>教训（曾经让游戏起不来）：目标若有返回值，处理器必须用 {@code CallbackInfoReturnable}。
     * 这类注入一律选 void 目标、只声明 {@code CallbackInfo}。
     *
     * <p>记账用 HEAD/RETURN 配对取耗时，起始值放 {@code @Unique} 静态字段。用
     * {@code TickStats.isActive()} 卡住归属线程，因此只有正在 tick 的线程读写，不需要同步；
     * 接管路径在 HEAD 内直接记账并把起始值清零，收尾那边不会重复计。
     */
    @Inject(
            method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;Ljava/util/List;I)V",
            at = @At("HEAD"),
            cancellable = true
    )
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void ecoTypeQueryTakeover(EntityTypeTest test, AABB bounds, Predicate predicate, List output,
                                      int maxResults, CallbackInfo ci) {
        boolean active = TickStats.isActive();
        long start = active ? System.nanoTime() : 0L;
        ecoParityShadow = null;

        if (FoldConfig.debugQueryParity) {
            // 影子对拍：原生结果放进另一份列表，原版照常跑，RETURN 里比对。
            // 这个模式不改变任何行为（输出仍由原版填），只是让「是否等价」有硬证据。
            ecoTypeQueryStart = start;
            if (!active) {
                ecoParityNativeNanos = 0L;
                return;
            }
            List shadow = new ArrayList(output.size() + 8);
            long nativeStart = System.nanoTime();
            boolean served = EntityQueryTakeover.fill((Level) (Object) this, test, bounds, predicate, shadow, maxResults);
            ecoParityNativeNanos = System.nanoTime() - nativeStart;
            if (served) {
                ecoParityShadow = shadow;
                ecoParityVanillaStart = System.nanoTime();
            }
            return;
        }

        if (EntityQueryTakeover.fill((Level) (Object) this, test, bounds, predicate, output, maxResults)) {
            if (start != 0L) {
                TickStats.typeQuery(System.nanoTime() - start);
            }
            // 已取消，RETURN 注入不会再跑；把起始值清零让收尾那边不再重复记账
            ecoTypeQueryStart = 0L;
            ci.cancel();
        } else if (start != 0L) {
            // 走原版，交给 RETURN 收尾
            ecoTypeQueryStart = start;
        }
    }

    @Inject(
            method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;Ljava/util/List;I)V",
            at = @At("RETURN")
    )
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void ecoTypeQueryTail(EntityTypeTest test, AABB bounds, Predicate predicate, List output,
                                  int maxResults, CallbackInfo ci) {
        if (!TickStats.isActive()) {
            ecoTypeQueryStart = 0L;
            ecoParityShadow = null;
            return;
        }
        if (ecoParityShadow != null) {
            List shadow = ecoParityShadow;
            ecoParityShadow = null;
            long vanillaNanos = System.nanoTime() - ecoParityVanillaStart;
            boolean mismatch = shadow.size() != output.size();
            if (!mismatch) {
                for (int i = 0, n = shadow.size(); i < n; i++) {
                    if (shadow.get(i) != output.get(i)) {
                        mismatch = true;
                        break;
                    }
                }
            }
            TickStats.queryParity(ecoParityNativeNanos, vanillaNanos, mismatch);
            if (mismatch && ecoParityLogged < 5) {
                ecoParityLogged++;
                AcceleratedRecoiling.LOGGER.warn(
                        "ECO query parity mismatch: native={} vanilla={} maxResults={} box={}",
                        shadow.size(), output.size(), maxResults, bounds);
            }
        }
        // 起始值为 0 说明这次是接管路径（HEAD 已记账），或者根本不在测量窗口内
        if (ecoTypeQueryStart != 0L) {
            TickStats.typeQuery(System.nanoTime() - ecoTypeQueryStart);
            ecoTypeQueryStart = 0L;
        }
    }
}
