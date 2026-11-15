package xmc.core.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VarIntsTest {
    private static final int[] VALUES = {0, 1, 2, 127, 128, 255, 300, 25565, 2_097_151, Integer.MAX_VALUE, -1};

    @Test
    void roundTrip() throws IOException {
        for (int value : VALUES) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            VarInts.write(out, value);
            assertEquals(VarInts.size(value), out.size(), "size for " + value);
            assertEquals(value, VarInts.read(new ByteArrayInputStream(out.toByteArray())));
        }
    }

    @Test
    void rejectsNonCanonicalEncoding() {
        // 0 encoded with two bytes is not canonical.
        byte[] nonCanonical = {(byte) 0x80, 0x00};
        assertThrows(IOException.class, () -> VarInts.read(new ByteArrayInputStream(nonCanonical)));
    }

    @Test
    void frameRoundTrip() throws Exception {
        byte[] body = new byte[300];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) i;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameCodec.write(out, body);
        FrameCodec.Frame frame = FrameCodec.read(new ByteArrayInputStream(out.toByteArray()), 1024);
        assertEquals(body.length, frame.body().length);
        assertEquals(VarInts.size(body.length) + body.length, frame.wireLength());
    }
}
