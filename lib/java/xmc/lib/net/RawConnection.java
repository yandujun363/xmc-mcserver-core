package xmc.lib.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A taken-over Minecraft connection.
 *
 * <p>After takeover the vanilla frame splitter, packet decoder and flow control
 * handlers are gone, so {@link RawFrameHandler} delivers exactly the decrypted
 * bytes the peer wrote. {@link #inputStream()} exposes them as a blocking
 * stream. {@link #write(byte[], int, int)} writes raw bytes below the packet
 * encoder but above the encryption handler.
 */
public final class RawConnection {
    private static final byte[] CLOSED = new byte[0];

    private final Channel channel;
    private final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
    private final AtomicLong bufferedBytes = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final long maxBufferedBytes;
    private final Object writeLock = new Object();
    private final RawInputStream input = new RawInputStream();
    private final OutputStream output = new OutputStream() {
        @Override
        public void write(int value) {
            RawConnection.this.write(new byte[]{(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            RawConnection.this.write(bytes, offset, length);
        }
    };

    private volatile ChannelHandlerContext context;

    public RawConnection(Channel channel, long maxBufferedBytes) {
        this.channel = channel;
        this.maxBufferedBytes = maxBufferedBytes;
    }

    void attach(ChannelHandlerContext context) {
        this.context = context;
    }

    void onBytes(ByteBuf buffer) {
        int length = buffer.readableBytes();
        if (length <= 0) {
            buffer.release();
            return;
        }
        if (bufferedBytes.addAndGet(length) > maxBufferedBytes) {
            buffer.release();
            close();
            return;
        }
        byte[] body = new byte[length];
        buffer.readBytes(body);
        buffer.release();
        if (closed.get()) {
            return;
        }
        chunks.offer(body);
    }

    /**
     * Queues bytes that were already drained from the vanilla frame decoder, so
     * the session sees them before anything that arrives after the takeover.
     */
    public void feed(byte[] bytes) {
        if (bytes.length == 0 || closed.get()) {
            return;
        }
        if (bufferedBytes.addAndGet(bytes.length) > maxBufferedBytes) {
            close();
            return;
        }
        chunks.offer(bytes.clone());
    }

    void onClosed() {
        if (closed.compareAndSet(false, true)) {
            chunks.offer(CLOSED);
        }
    }

    public InputStream inputStream() {
        return input;
    }

    /** Writes raw bytes through the encryption handler; each call is an atomic write. */
    public void write(byte[] bytes, int offset, int length) {
        if (closed.get()) {
            return;
        }
        ChannelHandlerContext ctx = context;
        if (ctx == null) {
            throw new IllegalStateException("xmc: raw connection is not attached yet");
        }
        byte[] copy = new byte[length];
        System.arraycopy(bytes, offset, copy, 0, length);
        synchronized (writeLock) {
            ctx.writeAndFlush(Unpooled.wrappedBuffer(copy));
        }
    }

    public OutputStream outputStream() {
        return output;
    }

    public boolean isOpen() {
        return !closed.get() && channel.isOpen();
    }

    public String remoteAddress() {
        return String.valueOf(channel.remoteAddress());
    }

    public void close() {
        onClosed();
        channel.close();
    }

    private byte[] takeChunk() throws IOException {
        try {
            byte[] body = chunks.take();
            if (body == CLOSED) {
                throw new EOFException("xmc: connection closed");
            }
            bufferedBytes.addAndGet(-body.length);
            return body;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("xmc: interrupted while reading");
        }
    }

    /** Streams the decrypted bytes exactly as the peer wrote them. */
    private final class RawInputStream extends InputStream {
        private byte[] chunk;
        private int position;

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read <= 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            while (true) {
                if (chunk != null && position < chunk.length) {
                    int count = Math.min(length, chunk.length - position);
                    System.arraycopy(chunk, position, target, offset, count);
                    position += count;
                    return count;
                }
                chunk = takeChunk();
                position = 0;
            }
        }
    }
}
