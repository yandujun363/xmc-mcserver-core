package xmc.lib;

import xmc.lib.spi.XmcLoginHandler;

/**
 * Static bridge between the Minecraft mixins (lib) and the XMC core.
 *
 * <p>The direction of the dependency is lib &lt;- core: the core installs a
 * handler at server start and the mixins only talk to this bridge. The bridge
 * carries no authentication state.
 */
public final class XmcLoginHooks {
    private static volatile XmcLoginHandler handler;

    private XmcLoginHooks() {
    }

    public static void install(XmcLoginHandler newHandler) {
        handler = newHandler;
    }

    public static void uninstall() {
        handler = null;
    }

    public static XmcLoginHandler handler() {
        return handler;
    }

    public static boolean enabled() {
        XmcLoginHandler current = handler;
        return current != null && current.enabled();
    }
}
