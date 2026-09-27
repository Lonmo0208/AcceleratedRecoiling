package com.wiyuka.acceleratedrecoiling.listeners;

import com.wiyuka.acceleratedrecoiling.natives.realtime.BatchedCollisions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class ServerStop {
    private ServerStop() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> BatchedCollisions.clear());
    }
}
