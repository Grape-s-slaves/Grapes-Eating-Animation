package net.grapes.gea;

import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod("gea")
public class GrapesEatingAnimation {
    public static final String MODID = "gea";
    public static final Logger LOGGER = LogManager.getLogger();

    public GrapesEatingAnimation() {
        LOGGER.info("GEA: Initializing Grape's Eating Animation mod");
        var modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        modEventBus.addListener(this::clientSetup);
        modEventBus.addListener(this::registerClientReloadListeners);

        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, EatingAnimationConfig.SPEC);
        LOGGER.info("GEA: Mod initialization complete");
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        LOGGER.info("GEA: Setting up client-side components");
        MinecraftForge.EVENT_BUS.register(new EatingAnimationHandler());
        LOGGER.info("GEA: Client setup complete, registered EatingAnimationHandler");
    }

    private void registerClientReloadListeners(RegisterClientReloadListenersEvent event) {
        LOGGER.info("GEA: Registering reload listeners");
        event.registerReloadListener((preparationBarrier, resourceManager, profilerFiller, profilerFiller2, executor, executor2) -> {
            return preparationBarrier.wait(null).thenRunAsync(() -> {
                LOGGER.info("GEA: Resource pack reloaded, reloading animation config");
                EatingAnimationConfig.reloadConfig();
            }, executor2);
        });
    }
}