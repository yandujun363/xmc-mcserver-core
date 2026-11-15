package xmc.lib.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.Connection;
import net.minecraft.network.Varint21FrameDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xmc.lib.mc.MinecraftLoginSupport;
import xmc.lib.mixin.ConnectionAccess;
import xmc.lib.spi.XmcLoginHandler;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Decides, immediately after the vanilla login acknowledgement, whether the
 * connection continues as an ordinary Minecraft client or switches to the XMC
 * tunnel.
 *
 * <p>Nothing is added to the wire and no client is identified by name, address
 * or any extra field:
 *
 * <ul>
 *   <li>An ordinary client always sends its first configuration packet right
 *       after the login acknowledgement. The vanilla frame decoder turns it
 *       into a frame immediately, this probe sees it and hands the connection
 *       back to vanilla untouched.</li>
 *   <li>An XMC client starts its padding schedule instead. A padding record
 *       encodes its length <em>including the length field itself</em>
 *       (see Xray-core {@code padding.go}), so the record is always at least
 *       one byte short of a valid Minecraft frame and the decoder can never
 *       produce a frame from it. Once the probe times out the connection is
 *       taken over.</li>
 * </ul>
 *
 * <p>Because the padding record can never be mistaken for a frame, and because
 * an ordinary client's packet is a complete frame by construction, the decision
 * stays deterministic for both sides.
 */
public final class LoginAckProbe extends ChannelInboundHandlerAdapter {
    public static final String NAME = "xmc_probe";

    private static final Logger LOGGER = LoggerFactory.getLogger("xmc-mcserver-core");

    private final Connection connection;
    private final Channel channel;
    private final XmcLoginHandler handler;
    private final Runnable vanillaReplay;
    private final String playerName;

    private final List<byte[]> pendingFrames = new ArrayList<>();
    private final ByteArrayOutputStream drainedBytes = new ByteArrayOutputStream();

    private ChannelHandlerContext context;
    private ScheduledFuture<?> timeout;
    private boolean decided;
    private boolean replayed;
    private boolean draining;

    private LoginAckProbe(Connection connection, Channel channel, XmcLoginHandler handler,
                          Runnable vanillaReplay, String playerName) {
        this.connection = connection;
        this.channel = channel;
        this.handler = handler;
        this.vanillaReplay = vanillaReplay;
        this.playerName = playerName;
    }

    /**
     * Installs the probe behind the vanilla frame decoder. Returns {@code false}
     * when the pipeline does not look like a login connection, in which case the
     * caller must leave the connection to vanilla.
     */
    public static boolean install(Connection connection, XmcLoginHandler handler,
                                  Runnable vanillaReplay, String playerName) {
        Channel channel = ((ConnectionAccess) (Object) connection).xmc$channel();
        ChannelPipeline pipeline = channel.pipeline();
        // In 26.1 the packet decoder is an "UnconfiguredPipelineHandler" that is
        // only replaced by "decoder" once the next protocol is set up, so the
        // frame decoder is the only stable anchor here.
        if (pipeline.get(MinecraftLoginSupport.SPLITTER) == null) {
            LOGGER.warn("xmc: unexpected pipeline for {} ({}) ; leaving the connection to vanilla",
                    connection.getRemoteAddress(), pipeline.names());
            return false;
        }
        LoginAckProbe probe = new LoginAckProbe(connection, channel, handler, vanillaReplay, playerName);
        pipeline.addAfter(MinecraftLoginSupport.SPLITTER, NAME, probe);
        probe.arm();
        // Vanilla pauses reads while it switches protocols, and we cancelled that
        // switch. Nothing can reach the decoder while the probe is installed, so
        // reading is safe for both outcomes and lets the peer's first bytes in.
        channel.config().setAutoRead(true);
        channel.read();
        LOGGER.debug("xmc: landing probe armed for {} ({})", playerName, pipeline.names());
        return true;
    }

    private void arm() {
        long millis = Math.max(1L, handler.probeTimeoutMillis());
        timeout = channel.eventLoop().schedule(this::onTimeout, millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.context = ctx;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf buffer)) {
            ctx.fireChannelRead(msg);
            return;
        }
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        buffer.release();

        if (draining) {
            drainedBytes.writeBytes(bytes);
            return;
        }
        if (replayed) {
            forward(ctx, bytes);
            return;
        }
        pendingFrames.add(bytes);
        LOGGER.debug("xmc: landing probe for {} saw a frame of {} bytes", playerName, bytes.length);
        if (!decided) {
            decideVanilla();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cancelTimeout();
        pendingFrames.clear();
        super.channelInactive(ctx);
    }

    private void decideVanilla() {
        if (decided) {
            return;
        }
        decided = true;
        cancelTimeout();
        replayVanilla();
    }

    private void replayVanilla() {
        if (replayed) {
            return;
        }
        LOGGER.debug("xmc: replaying the vanilla login acknowledgement for {}", playerName);
        try {
            vanillaReplay.run();
        } catch (Throwable failure) {
            LOGGER.warn("xmc: could not continue the vanilla login of {}", playerName, failure);
            channel.close();
            return;
        }
        replayed = true;
        LOGGER.debug("xmc: replayed for {}, pipeline now {}", playerName, channel.pipeline().names());
        ChannelHandlerContext ctx = context;
        if (ctx == null) {
            return;
        }
        for (byte[] frame : pendingFrames) {
            forward(ctx, frame);
        }
        pendingFrames.clear();
    }

    private void onTimeout() {
        if (decided || !channel.isActive()) {
            return;
        }
        decided = true;
        ChannelPipeline pipeline = channel.pipeline();

        // The frame decoder never produced a frame: an ordinary client would
        // have sent its first configuration packet by now. Removing the decoder
        // releases whatever it is still holding, which for XMC is the beginning
        // of the padding schedule.
        draining = true;
        try {
            if (pipeline.get(MinecraftLoginSupport.SPLITTER) != null) {
                pipeline.remove(MinecraftLoginSupport.SPLITTER);
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("xmc: could not drain the frame decoder of {}", playerName, failure);
        } finally {
            draining = false;
        }
        byte[] leftover = concat(pendingFrames, drainedBytes.toByteArray());
        LOGGER.debug("xmc: landing probe for {} timed out: frames={}, leftover={}",
                playerName, pendingFrames.size(), leftover.length);

        if (leftover.length == 0) {
            restoreVanilla(pipeline);
            replayVanilla();
            return;
        }

        pipeline.remove(this);
        RawConnection raw = MinecraftLoginSupport.takeOver(connection, leftover);
        handler.startSession(raw, playerName);
    }

    private void restoreVanilla(ChannelPipeline pipeline) {
        pipeline.remove(this);
        if (pipeline.get(MinecraftLoginSupport.DECODER) != null
                && pipeline.get(MinecraftLoginSupport.SPLITTER) == null) {
            pipeline.addBefore(MinecraftLoginSupport.DECODER, MinecraftLoginSupport.SPLITTER,
                    new Varint21FrameDecoder(null));
        }
    }

    private void cancelTimeout() {
        ScheduledFuture<?> current = timeout;
        timeout = null;
        if (current != null) {
            current.cancel(false);
        }
    }

    private static byte[] concat(List<byte[]> prefix, byte[] tail) {
        if (prefix.isEmpty()) {
            return tail;
        }
        int length = tail.length;
        for (byte[] part : prefix) {
            length += part.length;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] part : prefix) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        System.arraycopy(tail, 0, result, offset, tail.length);
        return result;
    }

    private static void forward(ChannelHandlerContext ctx, byte[] bytes) {
        ctx.fireChannelRead(Unpooled.wrappedBuffer(bytes));
    }
}
