package xmc.core.protocol;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the Java port against values produced by Xray-core's
 * {@code xmc.DeriveRSAKey} for the same passwords.
 */
class RsaKeyDeriverTest {
    @Test
    void matchesXrayDerivation() throws Exception {
        KeyPair keyPair = RsaKeyDeriver.derive("test-password-123");
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) keyPair.getPrivate();

        assertEquals(new BigInteger("ca322c972e517bec533ba8f4a20b43a65533f9b26c36743f0bdb9097cc051c3d"
                + "8c69d5bdc3bc506c65fe3f721ae221a047168c449fb650e96573d8669546ba03"
                + "3036b967a1328c5ad0c8e305ecc88a0eb2301cff4d77746aae7a566d31690f"
                + "cdbd1fd3203a1e2a73f37be57b7c8188656904b828acdcab2b301b1240bf71c125", 16), publicKey.getModulus());
        assertEquals(new BigInteger("19eb3e9e0e98f16ac4eca3e6e012d39431dddcc74b69db92ed6132f2d27b6138"
                + "5e947e8ab9d99cf1984d74329b519b7fe13cf9b41e490fac2b8e23125b565aa7"
                + "ad58c4e9d4ee20cf48b1c3981d465f148ae061454bdba200fab0e8d4b37b1790"
                + "c38b27eec99d63c23a129b1c466f72ddf3a156ab51d6614049c40a355d4a4ded", 16), privateKey.getPrivateExponent());
        assertEquals(new BigInteger("fbba2e553a52d452d97367667e833260268fcecac2766298b27eddb6378f165f"
                + "c93b643389ed27be3fbd9027d85bfc897a9261a8cc287c9e4115a81e65ea3d93", 16), privateKey.getPrimeP());
        assertEquals(new BigInteger("cda0c467269bc5e231fdfcc4ea7e21ee4b9b0f4a0d7d66e188dc40719c65fe8d"
                + "a40e805b4976899f9d59ebae95cd70e0f1ba94471f5588073067c015bfd5f967", 16), privateKey.getPrimeQ());
        assertEquals(BigInteger.valueOf(65537), publicKey.getPublicExponent());
    }

    @Test
    void matchesXrayDerivationForHexPassword() throws Exception {
        KeyPair keyPair = RsaKeyDeriver.derive("13f462cb64c72770");
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        assertEquals(new BigInteger("bf7b3dd13697984a8227aee6cf28801f3a1a25693bcadc60fedc16c65bb45519"
                + "547fc7623ba4d26eeaaa49e2a70df03a8847251e63786d57baeed5383b2b3a01"
                + "ae59968a507bbf1c113a556cd9294a2061d7722b29c9caf1c40148a1285aab28"
                + "e9686d5643f519f5003b729761771cd44eddc4da6df94b23d0fdead037fb622b", 16), publicKey.getModulus());
    }
}
