package xmc.core.protocol;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Minecraft VarInt (LEB128, max 5 bytes) read/write helpers.
 */
public final class VarInts {
    private VarInts() {
    }

    public static int read(InputStream in) throws IOException {
        int value = 0;
        for (int index = 0; index < 5; index++) {
            int current = in.read();
            if (current < 0) {
                throw new EOFException("unexpected end of stream while reading VarInt");
            }
            if (index == 4 && (current & 0xF0) != 0) {
                throw new IOException("VarInt is too large");
            }
            value |= (current & 0x7F) << (7 * index);
            if ((current & 0x80) == 0) {
                if (size(value) != index + 1) {
                    throw new IOException("non-canonical VarInt encoding");
                }
                return value;
            }
        }
        throw new IOException("VarInt is too large");
    }

    public static void write(OutputStream out, int value) throws IOException {
        int remaining = value;
        while (true) {
            int current = remaining & 0x7F;
            remaining >>>= 7;
            if (remaining != 0) {
                current |= 0x80;
            }
            out.write(current);
            if (remaining == 0) {
                return;
            }
        }
    }

    public static int size(int value) {
        int remaining = value;
        int size = 1;
        while ((remaining & ~0x7F) != 0) {
            remaining >>>= 7;
            size++;
        }
        return size;
    }

    public static byte[] encode(int value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(5);
        try {
            write(out, value);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return out.toByteArray();
    }
}
