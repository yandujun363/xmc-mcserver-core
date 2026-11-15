package xmc.core.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Minecraft frame ({@code VarInt length || body}) helpers. The same framing is
 * used by the XMC handshake/padding and by the Configuration-phase custom
 * payload stream.
 */
public final class FrameCodec {
    private FrameCodec() {
    }

    public record Frame(byte[] body, int wireLength) {
    }

    public static Frame read(InputStream in, int maxBodyLength) throws IOException {
        int length = VarInts.read(in);
        if (length < 1 || length > maxBodyLength) {
            throw new IOException("invalid frame length: " + length);
        }
        byte[] body = in.readNBytes(length);
        if (body.length != length) {
            throw new EOFException("truncated frame: expected " + length + " bytes, got " + body.length);
        }
        return new Frame(body, VarInts.size(length) + length);
    }

    public static void write(OutputStream out, byte[] body) throws IOException {
        VarInts.write(out, body.length);
        out.write(body);
        out.flush();
    }
}
