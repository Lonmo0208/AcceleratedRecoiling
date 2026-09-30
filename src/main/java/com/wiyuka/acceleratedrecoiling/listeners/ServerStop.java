package com.wiyuka.acceleratedrecoiling.listeners;

import com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling;
import com.wiyuka.acceleratedrecoiling.kernel.ParityContext;
import com.wiyuka.acceleratedrecoiling.natives.NativeInterface;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@EventBusSubscriber(modid = AcceleratedRecoiling.MODID)
public class ServerStop {
    @SubscribeEvent
    public static void onServerStop(ServerStoppingEvent event) {
        // 后端(含 GPU/jocl)初始化失败时，NativeInterface 类会进入失败状态，
        // 停止时再访问会抛 NoClassDefFoundError；这里必须容错，不能打断停机流程。
        try {
            NativeInterface.destroy();
        } catch (Throwable ignored) {
        }
        try {
            ParityContext.invalidateAll();
        } catch (Throwable ignored) {
        }
    }
}
