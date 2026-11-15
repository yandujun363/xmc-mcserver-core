package xmc.lib.mc;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.flow.FlowControlHandler;
import net.minecraft.network.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xmc.lib.mixin.ConnectionAccess;
import xmc.lib.net.LoginAckProbe;
import xmc.lib.net.NoopPacketListener;
import xmc.lib.net.RawConnection;
import xmc.lib.net.RawFrameHandler;

/**
 * The small surface of Minecraft server internals that the XMC core needs.
 *
 * <p>Nothing here touches authentication: vanilla has already negotiated the
 * encryption, verified the player and sent Login Success before the takeover.
 */
public final class MinecraftLoginSupport {
    /** Handler names used by {@code Connection} in 26.1. */
    public static final String SPLITTER = "splitter";
    public static final String DECODER = "decoder";
    public static final String INBOUND_CONFIG = "inbound_config";
    public static final String OUTBOUND_CONFIG = "outbound_config";
    public static final String PREPENDER = "prepender";
    public static final String LEGACY_QUERY = "legacy_query";
    public static final String DECOMPRESS = "decompress";
    public static final String COMPRESS = "compress";

    private static final String XMC_RAW = "xmc_raw";
    private static final Logger LOGGER = LoggerFactory.getLogger("xmc-mcserver-core");

    /** Upper bound for bytes buffered from a client while the relay thread catches up. */
    public static final long MAX_BUFFERED_BYTES = 8L * 1024 * 1024;

    private MinecraftLoginSupport() {
    }

    public static void close(Connection connection) {
        ((ConnectionAccess) (Object) connection).xmc$channel().close();
    }

    /**
     * Switches the connection from the login packet pipeline to raw decrypted
     * bytes: the vanilla frame decoder, packet decoder and read throttling are
     * removed and a raw handler receives the cipher output directly. The packet
     * listener is replaced so the login state machine stops.
     *
     * @param pendingBytes bytes already drained from the vanilla frame decoder
     */
    public static RawConnection takeOver(Connection connection, byte[] pendingBytes) {
        ConnectionAccess access = (ConnectionAccess) (Object) connection;
        Channel channel = access.xmc$channel();
        ChannelPipeline pipeline = channel.pipeline();

        RawConnection raw = new RawConnection(channel, MAX_BUFFERED_BYTES);

        if (pipeline.get(PREPENDER) == null) {
            throw new IllegalStateException("xmc: connection pipeline has no prepender");
        }
        pipeline.addBefore(PREPENDER, XMC_RAW, new RawFrameHandler(raw));

        // The login decoder and the VarInt frame decoder must go: XMC padding
        // records use Xray's self-inclusive length encoding, which is not the
        // standard Minecraft frame encoding the decoder expects.
        remove(pipeline, DECODER);
        remove(pipeline, INBOUND_CONFIG);
        remove(pipeline, OUTBOUND_CONFIG);
        remove(pipeline, SPLITTER);
        remove(pipeline, LEGACY_QUERY);
        remove(pipeline, LoginAckProbe.NAME);
        if (pipeline.get(DECOMPRESS) != null || pipeline.get(COMPRESS) != null) {
            LOGGER.warn("xmc: connection compression is enabled; the XMC stream is not compressed, "
                    + "removing the compression handlers. Set network-compression-threshold=-1");
            remove(pipeline, DECOMPRESS);
            remove(pipeline, COMPRESS);
        }
        // The login pipeline throttles reads through a FlowControlHandler. For the
        // tunnel the raw stream must flow continuously, so drop it, restore
        // auto-read and drain one read.
        for (ChannelHandler pipelineHandler : pipeline.toMap().values()) {
            if (pipelineHandler instanceof FlowControlHandler) {
                pipeline.remove(pipelineHandler);
                break;
            }
        }

        raw.feed(pendingBytes);
        channel.config().setAutoRead(true);
        channel.read();
        access.xmc$setPacketListener(new NoopPacketListener());
        return raw;
    }

    private static void remove(ChannelPipeline pipeline, String name) {
        if (pipeline.get(name) != null) {
            pipeline.remove(name);
        }
    }
}
