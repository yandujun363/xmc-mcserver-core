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
                  "password": "13f462cb64c72770",
                  "channel": "xmc:data",
                  "profiles": [
                    {
                      "username": "Dream",
                      "uuid": "00000000-0000-0000-0000-000000000001",
                      "texturesValue": "dGV4dHVyZQ==",
                      "texturesSignature": "c2lnbmF0dXJl"
                    }
                  ],
                  "tunnel": { "host": "127.0.0.1", "port": 10086 }
                }
                """);
        XmcConfig config = XmcConfigLoader.loadOrCreate(path);
        assertTrue(config.enabled());
        assertEquals(1, config.profiles().size());
        assertEquals(10086, config.tunnel().port());
        assertEquals(15, config.keepAliveSeconds());
    }

    @Test
    void rejectsPublicRelayAddress() throws Exception {
        Path path = directory.resolve("config.json");
        Files.writeString(path, """
                {
                  "enabled": true,
                  "password": "pw",
                  "profiles": [
                    {
                      "username": "Dream",
                      "uuid": "00000000-0000-0000-0000-000000000001",
                      "texturesValue": "v",
                      "texturesSignature": "s"
                    }
                  ],
                  "tunnel": { "host": "1.1.1.1", "port": 10086 }
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
