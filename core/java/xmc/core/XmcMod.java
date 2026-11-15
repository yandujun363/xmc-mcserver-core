package xmc.core;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xmc.config.XmcConfig;
import xmc.config.XmcConfigLoader;
import xmc.core.session.XmcRuntime;
import xmc.lib.XmcLoginHooks;

import java.nio.file.Path;

/**
 * Mod entrypoint: loads the configuration on server start and installs the
 * landing handler that the mixins call into.
 *
 * <p>Login, encryption, authentication and the player list stay vanilla; this
 * mod only turns a connection into an XMC tunnel once the connection itself
 * starts the XMC padding schedule instead of the configuration phase.
 */
public final class XmcMod implements ModInitializer {
    public static final String MOD_ID = "xmc-mcserver-core";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile XmcRuntime runtime;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(XmcMod::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
        LOGGER.info("XMC MC server core initialized");
    }

    private static void start(MinecraftServer server) {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(XmcConfigLoader.FILE_NAME);
        try {
            XmcConfig config = XmcConfigLoader.loadOrCreate(configPath);
            if (!config.enabled()) {
                XmcLoginHooks.uninstall();
                runtime = null;
                LOGGER.info("XMC is disabled (config: {})", configPath);
                return;
            }

            warnAboutServerSettings(server);

            XmcRuntime created = XmcRuntime.create(config);
            XmcLoginHooks.install(created);
            runtime = created;

            LOGGER.info("XMC enabled: channel={}, tunnel={}:{}, probeTimeout={}ms",
                    config.channel(),
                    config.tunnel().host(),
                    config.tunnel().port(),
                    config.probeTimeoutMillis());
        } catch (Exception failure) {
            XmcLoginHooks.uninstall();
            runtime = null;
            LOGGER.error("XMC configuration failed; XMC stays disabled (config: {})", configPath, failure);
        }
    }

    /**
     * The XMC client performs a real Minecraft login, so it needs the vanilla
     * encryption request and it cannot negotiate login compression. Both are
     * plain server settings; the mod never forces them.
     */
    private static void warnAboutServerSettings(MinecraftServer server) {
        if (!server.usesAuthentication()) {
            LOGGER.warn("XMC: online-mode is disabled. The XMC client needs the vanilla encryption request, "
                    + "so enable online-mode or XMC clients cannot log in.");
        }
        int threshold = server.getCompressionThreshold();
        if (threshold >= 0) {
            LOGGER.warn("XMC: network-compression-threshold is {}. The XMC client cannot read the login "
                    + "compression packet; set network-compression-threshold=-1.", threshold);
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
