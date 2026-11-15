package xmc.lib.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Receives decrypted raw bytes after the login pipeline has been replaced and
 * forwards them to a {@link RawConnection}.
 */
public final class RawFrameHandler extends ChannelInboundHandlerAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger("xmc-mcserver-core");

    private final RawConnection owner;

    public RawFrameHandler(RawConnection owner) {
        this.owner = owner;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        owner.attach(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf buffer) {
            owner.onBytes(buffer);
            return;
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        owner.onClosed();
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOGGER.debug("XMC raw connection error ({})", owner.remoteAddress(), cause);
        owner.close();
    }
}
