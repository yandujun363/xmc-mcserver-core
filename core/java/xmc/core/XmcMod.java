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
import xmc.lib.net.XmcNetworking;

import java.nio.file.Path;

/**
 * Mod entrypoint.
 *
 * <p>The mod registers two configuration-state channels and nothing else: no
 * mixin, no login interception, no authentication. Vanilla still owns the whole
 * login and the configuration phase; a connection that starts sending
 * {@code xmc:data} is turned into a tunnel, everything else keeps its vanilla
 * path byte for byte.
 */
public final class XmcMod implements ModInitializer {
    public static final String MOD_ID = "xmc-mcserver-core";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile XmcRuntime runtime;

    @Override
    public void onInitialize() {
        XmcNetworking.register();
        ServerLifecycleEvents.SERVER_STARTING.register(XmcMod::start);
        // Properties (online-mode, compression) are only applied after
        // SERVER_STARTING, so the settings check waits for SERVER_STARTED.
        ServerLifecycleEvents.SERVER_STARTED.register(XmcMod::warnAboutServerSettings);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
        LOGGER.info("XMC MC server core initialized");
    }

    private static void start(MinecraftServer server) {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(XmcConfigLoader.FILE_NAME);
        try {
            XmcConfig config = XmcConfigLoader.loadOrCreate(configPath);
            if (!config.enabled()) {
                XmcNetworking.uninstall();
                runtime = null;
                LOGGER.info("XMC is disabled (config: {})", configPath);
                return;
            }

            XmcRuntime created = XmcRuntime.create(config);
            XmcNetworking.install(created);
            runtime = created;

            LOGGER.info("XMC enabled: channel=xmc:data, tunnel={}:{}",
                    config.tunnel().host(),
                    config.tunnel().port());
        } catch (Exception failure) {
            XmcNetworking.uninstall();
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
        XmcNetworking.uninstall();
        if (current != null) {
            current.shutdown();
        }
    }
}
