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
 * login and the configuration phase; a connection that starts sending on the
 * configured data channel is turned into a tunnel, everything else keeps its
 * vanilla path byte for byte.
 *
 * <p>The channel ids come from the configuration, so the file is read here, in
 * {@code onInitialize}, before the payload types are registered. A configuration
 * mistake must not take the whole server down: it is logged, XMC stays disabled
 * and vanilla keeps serving.
 */
public final class XmcMod implements ModInitializer {
    public static final String MOD_ID = "xmc-mcserver-core";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile XmcConfig config;
    private static volatile boolean networkingReady;
    private static volatile XmcRuntime runtime;

    @Override
    public void onInitialize() {
        XmcConfig loaded;
        try {
            loaded = XmcConfigLoader.loadOrCreate(configPath());
        } catch (Exception failure) {
            LOGGER.error("XMC configuration failed; XMC stays disabled (config: {})", configPath(), failure);
            loaded = XmcConfig.disabled();
        }
        config = loaded;

        if (loaded.enabled()) {
            try {
                XmcNetworking.register(loaded.channels().data().id(), loaded.channels().padding().id());
                networkingReady = true;
                LOGGER.info("XMC channels registered: data={}, padding={}",
                        loaded.channels().data().id(), loaded.channels().padding().id());
            } catch (RuntimeException failure) {
                // PayloadTypeRegistry has no unregister and throws on a
                // duplicate id; leave XMC off rather than crash the server.
                LOGGER.error("XMC channel registration failed; XMC stays disabled. "
                        + "Check channels.data / channels.padding for a name already used by vanilla "
                        + "or another mod (config: {})", configPath(), failure);
            }
        }

        ServerLifecycleEvents.SERVER_STARTING.register(XmcMod::start);
        // Properties (online-mode, compression) are only applied after
        // SERVER_STARTING, so the settings check waits for SERVER_STARTED.
        ServerLifecycleEvents.SERVER_STARTED.register(XmcMod::warnAboutServerSettings);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
        LOGGER.info("XMC MC server core initialized");
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(XmcConfigLoader.FILE_NAME);
    }

    private static void start(MinecraftServer server) {
        XmcConfig current = config;
        if (current == null || !current.enabled()) {
            XmcNetworking.uninstall();
            runtime = null;
            LOGGER.info("XMC is disabled (config: {})", configPath());
            return;
        }
        if (!networkingReady) {
            runtime = null;
            LOGGER.error("XMC is enabled but its channels were not registered; XMC stays disabled "
                    + "(config: {})", configPath());
            return;
        }

        XmcRuntime created = XmcRuntime.create(current);
        XmcNetworking.install(created);
        runtime = created;

        LOGGER.info("XMC enabled: data channel={}, padding channel={}, tunnel={}:{}",
                current.channels().data().id(),
                current.channels().padding().id(),
                current.tunnel().host(),
                current.tunnel().port());
    }

    /**
     * The XMC client performs a real Minecraft login, so it needs the vanilla
     * encryption request. Login compression is supported by the client
     * (https://github.com/XTLS/Xray-core/pull/7402), but a low threshold
     * compresses the padding frames away. Both are plain server settings; the
     * mod never forces them.
     */
    private static void warnAboutServerSettings(MinecraftServer server) {
        if (!server.usesAuthentication()) {
            LOGGER.warn("XMC: online-mode is disabled. The XMC client needs the vanilla encryption request, "
                    + "so enable online-mode or XMC clients cannot log in.");
        }
        int threshold = server.getCompressionThreshold();
        if (threshold >= 0 && threshold < 65536) {
            LOGGER.warn("XMC: network-compression-threshold is {}. The XMC client reads the compressed "
                    + "framing (https://github.com/XTLS/Xray-core/pull/7402), but padding frames are "
                    + "compressed away below 65536; use 65536 to keep padding and tunnel payloads "
                    + "uncompressed, or -1 to disable compression.", threshold);
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
