package xmc.core.session;

import xmc.config.XmcConfig;
import xmc.core.XmcMod;
import xmc.core.protocol.RsaKeyDeriver;
import xmc.lib.XmcLoginHooks;
import xmc.lib.model.McProfile;
import xmc.lib.net.RawConnection;
import xmc.lib.spi.LoginDecision;
import xmc.lib.spi.XmcLoginHandler;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * XMC core: owns the derived key pair, authorizes logins and tracks sessions.
 */
public final class XmcRuntime implements XmcLoginHandler {
    private final XmcConfig config;
    private final KeyPair keyPair;
    private final byte[] passwordBytes;
    private final Set<XmcSession> sessions = ConcurrentHashMap.newKeySet();

    private XmcRuntime(XmcConfig config, KeyPair keyPair) {
        this.config = config;
        this.keyPair = keyPair;
        this.passwordBytes = config.passwordBytes();
    }

    public static XmcRuntime create(XmcConfig config) throws GeneralSecurityException {
        KeyPair keyPair = config.enabled() ? RsaKeyDeriver.derive(config.password()) : null;
        return new XmcRuntime(config, keyPair);
    }

    public XmcConfig config() {
        return config;
    }

    @Override
    public boolean enabled() {
        return config.enabled() && keyPair != null;
    }

    @Override
    public KeyPair keyPair() {
        return keyPair;
    }

    @Override
    public LoginDecision decide(String username, UUID profileId, byte[] verifyToken, byte[] challenge) {
        if (username == null || profileId == null) {
            return new LoginDecision.Reject(LoginDecision.RejectReason.NOT_WHITELISTED);
        }
        byte[] password = Arrays.copyOfRange(verifyToken, challenge.length, verifyToken.length);
        if (!MessageDigest.isEqual(password, passwordBytes)) {
            return new LoginDecision.Reject(LoginDecision.RejectReason.AUTH_SERVERS_DOWN);
        }
        return config.findProfile(username, profileId)
                .<LoginDecision>map(LoginDecision.Accept::new)
                .orElseGet(() -> new LoginDecision.Reject(LoginDecision.RejectReason.NOT_WHITELISTED));
    }

    @Override
    public void startSession(RawConnection connection, McProfile profile) {
        XmcSession session = new XmcSession(connection, config, profile, sessions::remove);
        sessions.add(session);
        XmcMod.LOGGER.info("XMC tunnel established for {} ({})", profile.username(), connection.remoteAddress());
        session.start();
    }

    public void closeAll() {
        for (XmcSession session : sessions) {
            session.close();
        }
        sessions.clear();
    }

    public void shutdown() {
        closeAll();
        XmcLoginHooks.uninstall();
    }
}
