package xmc.lib.mc;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.flow.FlowControlHandler;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.util.Crypt;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import xmc.lib.mixin.ConnectionAccess;
import xmc.lib.mixin.ServerLoginPacketListenerImplAccess;
import xmc.lib.model.McProfile;
import xmc.lib.net.NoopPacketListener;
import xmc.lib.net.RawConnection;
import xmc.lib.net.RawFrameHandler;

import javax.crypto.SecretKey;

/**
 * The small surface of Minecraft server internals that the XMC core needs.
 * Everything else stays out of {@code xmc.core} and {@code xmc.config}.
 */
public final class MinecraftLoginSupport {
    /** Handler names used by {@code Connection} in 26.1. */
    public static final String DECODER = "decoder";
    public static final String PREPENDER = "prepender";
    public static final String LEGACY_QUERY = "legacy_query";
    private static final String XMC_RAW = "xmc_raw";

    /** Upper bound for bytes buffered from a client while the relay thread catches up. */
    public static final long MAX_BUFFERED_BYTES = 8L * 1024 * 1024;

    private MinecraftLoginSupport() {
    }

    public static void enableEncryption(Connection connection, SecretKey secretKey) throws Exception {
        connection.setEncryptionKey(Crypt.getCipher(2, secretKey), Crypt.getCipher(1, secretKey));
    }

    public static void close(Connection connection) {
        ((ConnectionAccess) (Object) connection).xmc$channel().close();
    }

    public static void sendLoginSuccess(Connection connection, McProfile profile) {
        PropertyMap properties = profile.hasTextures()
                ? new PropertyMap(ImmutableMultimap.of(
                        "textures", new Property("textures", profile.texturesValue(), profile.texturesSignature())))
                : new PropertyMap(ImmutableMultimap.of());
        GameProfile gameProfile = new GameProfile(profile.uuid(), profile.username(), properties);
        connection.send(new ClientboundLoginFinishedPacket(gameProfile));
    }

    public static void disconnectLogin(Connection connection, Component reason) {
        connection.send(new ClientboundLoginDisconnectPacket(reason), ChannelFutureListener.CLOSE);
    }

    /** Calls the private vanilla path that moves the login listener into {@code VERIFYING}. */
    public static void completeOfflineLogin(ServerLoginPacketListenerImpl listener, String username) {
        ((ServerLoginPacketListenerImplAccess) (Object) listener)
                .xmc$startClientVerification(UUIDUtil.createOfflineProfile(username));
    }

    /**
     * Switches the connection from the login packet pipeline to raw decrypted
     * bytes: the vanilla frame splitter, packet decoder and read throttling are
     * removed and a raw handler receives the cipher output directly. The packet
     * listener is replaced so the login state machine stops.
     */
    public static RawConnection takeOver(Connection connection) {
        ConnectionAccess access = (ConnectionAccess) (Object) connection;
        Channel channel = access.xmc$channel();
        ChannelPipeline pipeline = channel.pipeline();

        RawConnection raw = new RawConnection(channel, MAX_BUFFERED_BYTES);
        RawFrameHandler handler = new RawFrameHandler(raw);

        if (pipeline.get(PREPENDER) == null) {
            throw new IllegalStateException("xmc: connection pipeline has no prepender");
        }
        pipeline.addBefore(PREPENDER, XMC_RAW, handler);
        // Both the login decoder and the VarInt frame splitter must go: XMC
        // padding records use Xray's self-inclusive length encoding, which is
        // not the standard Minecraft frame encoding the splitter expects.
        if (pipeline.get(DECODER) != null) {
            pipeline.remove(DECODER);
        }
        if (pipeline.get("splitter") != null) {
            pipeline.remove("splitter");
        }
        if (pipeline.get(LEGACY_QUERY) != null) {
            pipeline.remove(LEGACY_QUERY);
        }
        // The login pipeline throttles reads through a FlowControlHandler. For the
        // tunnel we want the splitter output to flow continuously, so drop it,
        // restore auto-read and drain one read.
        for (ChannelHandler pipelineHandler : pipeline.toMap().values()) {
            if (pipelineHandler instanceof FlowControlHandler) {
                pipeline.remove(pipelineHandler);
                break;
            }
        }
        channel.config().setAutoRead(true);
        channel.read();
        access.xmc$setPacketListener(new NoopPacketListener());
        return raw;
    }
}
