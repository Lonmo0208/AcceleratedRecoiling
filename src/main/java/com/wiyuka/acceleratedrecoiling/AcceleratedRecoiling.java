package com.wiyuka.acceleratedrecoiling;

import com.mojang.logging.LogUtils;
import com.wiyuka.acceleratedrecoiling.config.FoldConfig;
import com.wiyuka.acceleratedrecoiling.listeners.ServerStop;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;

public class AcceleratedRecoiling implements ModInitializer {
    public static final String MODID = "acceleratedrecoiling";
    public static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        FoldConfig.loadConfig();
        ServerStop.register();
    }
}
