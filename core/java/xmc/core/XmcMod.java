package xmc.core;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xmc.config.XmcConfig;
import xmc.config.XmcConfigLoader;
import xmc.core.session.XmcRuntime;
import xmc.lib.XmcLoginHooks;

import java.nio.file.Path;

/**
 * Mod entrypoint: loads the configuration on server start and installs the
 * login handler that the mixins call into.
 */
public final class XmcMod implements ModInitializer {
    public static final String MOD_ID = "xmc-mcserver-core";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile XmcRuntime runtime;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> start());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
        LOGGER.info("XMC MC server core initialized");
    }

    private static void start() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(XmcConfigLoader.FILE_NAME);
        try {
            XmcConfig config = XmcConfigLoader.loadOrCreate(configPath);
            if (!config.enabled()) {
                XmcLoginHooks.uninstall();
                runtime = null;
                LOGGER.info("XMC is disabled (config: {})", configPath);
                return;
            }

            long started = System.nanoTime();
            XmcRuntime created = XmcRuntime.create(config);
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
            XmcLoginHooks.install(created);
            runtime = created;

            LOGGER.info("XMC enabled: channel={}, profiles={}, tunnel={}:{}, key derivation took {} ms",
                    config.channel(),
                    config.profiles().size(),
                    config.tunnel().host(),
                    config.tunnel().port(),
                    elapsedMillis);
        } catch (Exception failure) {
            XmcLoginHooks.uninstall();
            runtime = null;
            LOGGER.error("XMC configuration failed; XMC stays disabled (config: {})", configPath, failure);
        }
    }

    private static void stop() {
        XmcRuntime current = runtime;
        runtime = null;
        if (current != null) {
            current.shutdown();
        } else {
            XmcLoginHooks.uninstall();
        }
    }
}
