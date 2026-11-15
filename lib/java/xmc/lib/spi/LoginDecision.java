package xmc.lib.spi;

import xmc.lib.model.McProfile;

/**
 * Result of inspecting a login key exchange.
 */
public sealed interface LoginDecision permits LoginDecision.NotXmc, LoginDecision.Accept, LoginDecision.Reject {
    /** The connection is an ordinary Minecraft client; the vanilla login flow continues. */
    record NotXmc() implements LoginDecision {
    }

    /** The connection is an XMC client; terminate the vanilla login and start the tunnel. */
    record Accept(McProfile profile) implements LoginDecision {
    }

    /** The connection announced XMC but failed authentication or is not allowed. */
    record Reject(RejectReason reason) implements LoginDecision {
    }

    enum RejectReason {
        /** The pre-shared password did not match. */
        AUTH_SERVERS_DOWN,
        /** The announced username/UUID is not in the configured profile list. */
        NOT_WHITELISTED
    }

    static LoginDecision notXmc() {
        return new NotXmc();
    }
}
