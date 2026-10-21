package com.minethunder;

import com.mojang.logging.LogUtils;
import com.minethunder.network.Network;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(MineThunder.MODID)
public final class MineThunder {
    public static final String MODID = "minethunder";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MineThunder(ModContainer modContainer) {
        IEventBus modEventBus = modContainer.getEventBus();
        Registration.register(modEventBus);
        modEventBus.addListener(Network::register);
        LOGGER.info("MineThunder loaded");
    }
}
