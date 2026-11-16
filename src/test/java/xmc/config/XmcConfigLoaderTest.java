package xmc.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmcConfigLoaderTest {
    @TempDir
    Path directory;

    @Test
    void missingFileCreatesDisabledExample() throws Exception {
        Path path = directory.resolve(XmcConfigLoader.FILE_NAME);
        XmcConfig config = XmcConfigLoader.loadOrCreate(path);
        assertFalse(config.enabled());
        assertTrue(Files.exists(path));
    }

    @Test
    void loadsValidConfiguration() throws Exception {
        Path path = directory.resolve("config.json");
        Files.writeString(path, """
                {
                  "enabled": true,
                  "maxPayloadBytes": 8192,
                  "tunnel": { "host": "127.0.0.1", "port": 10086, "connectTimeoutMillis": 3000 }
                }
                """);
        XmcConfig config = XmcConfigLoader.loadOrCreate(path);
        assertTrue(config.enabled());
        assertEquals(10086, config.tunnel().port());
        assertEquals(3000, config.tunnel().connectTimeoutMillis());
        assertEquals(8192, config.maxPayloadBytes());
    }

    @Test
    void defaultsToThePublicChannels() throws Exception {
        Path path = directory.resolve("config.json");
        Files.writeString(path, """
                {
                  "enabled": true,
                  "tunnel": { "host": "127.0.0.1", "port": 10086 }
                }
                """);
        XmcConfig config = XmcConfigLoader.loadOrCreate(path);
        assertEquals("xmc:data", config.channels().data().id());
        assertEquals("xmc:pad", config.channels().padding().id());
    }

    @Test
    void loadsCustomChannels() throws Exception {
        Path path = directory.resolve("config.json");
        Files.writeString(path, """
                {
                  "enabled": true,
                  "channels": { "data": "myxmc:stream", "padding": "myxmc:noise" },
                  "tunnel": { "host": "127.0.0.1", "port": 10086 }
                }
                """);
        XmcConfig config = XmcConfigLoader.loadOrCreate(path);
        assertEquals("myxmc:stream", config.channels().data().id());
        assertEquals("myxmc:noise", config.channels().padding().id());
    }

    @Test
    void rejectsChannelWithoutNamespaceSeparator() throws Exception {
        assertInvalidChannels("""
                { "data": "xmc-data", "padding": "xmc:pad" }
                """);
    }

    @Test
    void rejectsUppercaseChannel() throws Exception {
        assertInvalidChannels("""
                { "data": "xmc:Data", "padding": "xmc:pad" }
                """);
    }

    @Test
    void rejectsMinecraftNamespace() throws Exception {
        assertInvalidChannels("""
                { "data": "minecraft:my_stream", "padding": "xmc:pad" }
                """);
    }

    @Test
    void rejectsDuplicateChannels() throws Exception {
        assertInvalidChannels("""
                { "data": "xmc:same", "padding": "xmc:same" }
                """);
    }

    @Test
    void rejectsDotDotNamespace() throws Exception {
        assertInvalidChannels("""
                { "data": "..:stream", "padding": "xmc:pad" }
                """);
    }

    @Test
    void rejectsOverlongChannel() throws Exception {
        assertInvalidChannels("""
                { "data": "xmc:%s", "padding": "xmc:pad" }
                """.formatted("a".repeat(XmcConfig.MAX_CHANNEL_ID_LENGTH)));
    }

    private void assertInvalidChannels(String channels) throws Exception {
        Path path = directory.resolve("config.json");
        Files.writeString(path, """
                {
                  "enabled": true,
                  "channels": %s,
                  "tunnel": { "host": "127.0.0.1", "port": 10086 }
                }
                """.formatted(channels));
        assertThrows(IllegalArgumentException.class, () -> XmcConfigLoader.loadOrCreate(path));
    }

    @Test
    void rejectsInvalidChannelsEvenWhenDisabled() throws Exception {
        Path path = directory.resolve("config.json");
        Files.writeString(path, """
                {
                  "enabled": false,
                  "channels": { "data": "XMC:data", "padding": "xmc:pad" }
                }
                """);
        assertThrows(IllegalArgumentException.class, () -> XmcConfigLoader.loadOrCreate(path));
    }

    @Test
    void rejectsPublicRelayAddress() throws Exception {
        Path path = directory.resolve("config.json");
        Files.writeString(path, """
                {
                  "enabled": true,
                  "tunnel": { "host": "1.1.1.1", "port": 10086 }
                }
                """);
        assertThrows(IllegalArgumentException.class, () -> XmcConfigLoader.loadOrCreate(path));
    }

    @Test
    void rejectsImpossiblePayloadSize() throws Exception {
        Path path = directory.resolve("config.json");
        Files.writeString(path, """
                {
                  "enabled": true,
                  "maxPayloadBytes": 100,
                  "tunnel": { "host": "127.0.0.1", "port": 10086 }
                }
                """);
        assertThrows(IllegalArgumentException.class, () -> XmcConfigLoader.loadOrCreate(path));
    }

    @Test
    void privateAddressClassification() throws Exception {
        assertTrue(PrivateAddress.isPrivate(java.net.InetAddress.getByName("127.0.0.1")));
        assertTrue(PrivateAddress.isPrivate(java.net.InetAddress.getByName("10.1.2.3")));
        assertTrue(PrivateAddress.isPrivate(java.net.InetAddress.getByName("192.168.1.1")));
        assertTrue(PrivateAddress.isPrivate(java.net.InetAddress.getByName("172.16.9.9")));
        assertTrue(PrivateAddress.isPrivate(java.net.InetAddress.getByName("100.64.0.1")));
        assertTrue(PrivateAddress.isPrivate(java.net.InetAddress.getByName("fc00::1")));
        assertFalse(PrivateAddress.isPrivate(java.net.InetAddress.getByName("8.8.8.8")));
        assertFalse(PrivateAddress.isPrivate(java.net.InetAddress.getByName("2001:4860:4860::8888")));
    }
}
