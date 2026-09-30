package com.wiyuka.acceleratedrecoiling.listeners;

import com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling;
import com.wiyuka.acceleratedrecoiling.engine.CollidableEntities;
import com.wiyuka.acceleratedrecoiling.engine.EcoFrame;
import com.wiyuka.acceleratedrecoiling.engine.TickStats;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.minecraft.server.level.ServerLevel;

/**
 * 维度卸载时把该维度的资源放掉。
 *
 * <p>为什么必须有这个监听器：模组按维度存了三张静态表——{@link EcoFrame} 的帧注册表、
 * {@link TickStats} 的各窗口、{@link CollidableEntities} 的存在标志。它们都是
 * {@code IdentityHashMap<ServerLevel, ...>}，也就是**强引用维度对象**。原版只加载三个维度且
 * 永不卸载，所以平时看不出来；但一旦有动态增删维度的插件/模组，卸载后的维度对象就再也无法回收，
 * 而每个残留的帧还占着一个 {@code Arena.ofShared()} 与十几段按实体数定尺的原生内存
 * （2000 实体量级约 1 MB）。{@code EcoFrame.remove} 早就写好了，只是从来没人调用——
 * 这个类就是那个调用点。
 *
 * <p>一律容错：卸载路径上不能因为清理失败而打断世界保存。
 */
@EventBusSubscriber(modid = AcceleratedRecoiling.MODID)
public final class LevelUnload {

    private LevelUnload() {
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        // EcoFrame.remove 会关掉该帧的 Arena 并释放原生段。
        try {
            EcoFrame.remove(level);
        } catch (Throwable ignored) {
        }
        try {
            TickStats.removeLevel(level);
        } catch (Throwable ignored) {
        }
        try {
            CollidableEntities.remove(level);
        } catch (Throwable ignored) {
        }
    }

    /** 服务器停机时统一收尾：把所有维度留下的帧与表清空（停机路径同样不许抛）。 */
    @SubscribeEvent
    public static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        try {
            EcoFrame.removeAll();
        } catch (Throwable ignored) {
        }
        try {
            TickStats.removeAllLevels();
        } catch (Throwable ignored) {
        }
        try {
            CollidableEntities.removeAll();
        } catch (Throwable ignored) {
        }
    }
}
