package xmc.core.protocol;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPublicKeySpec;

/**
 * Deterministic 1024-bit RSA key derivation, byte-for-byte compatible with
 * Xray-core's {@code xmc.DeriveRSAKey}.
 *
 * <p>The password seeds two SHA-256 counter streams. 64 bytes of each stream
 * are used as a 512-bit candidate, the top two bits of the first byte are set
 * and the candidate is advanced by two until it is prime and coprime with
 * 65537.
 */
public final class RsaKeyDeriver {
    private static final BigInteger PUBLIC_EXPONENT = BigInteger.valueOf(65537);
    private static final int PRIME_CERTAINTY = 64;

    private RsaKeyDeriver() {
    }

    public static KeyPair derive(String password) throws GeneralSecurityException {
        byte[] seed = password.getBytes(StandardCharsets.UTF_8);

        BigInteger p = derivePrime(seed, "-p-prime");
        BigInteger q = derivePrime(seed, "-q-prime");

        while (p.equals(q)) {
            q = q.add(BigInteger.TWO);
            while (!isUsablePrime(q)) {
                q = q.add(BigInteger.TWO);
            }
        }

        BigInteger modulus = p.multiply(q);
        BigInteger one = BigInteger.ONE;
        BigInteger pMinusOne = p.subtract(one);
        BigInteger qMinusOne = q.subtract(one);
        BigInteger totient = pMinusOne.multiply(qMinusOne);
        BigInteger privateExponent = PUBLIC_EXPONENT.modInverse(totient);

        KeyFactory factory = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = factory.generatePrivate(new RSAPrivateCrtKeySpec(
                modulus,
                PUBLIC_EXPONENT,
                privateExponent,
                p,
                q,
                privateExponent.mod(pMinusOne),
                privateExponent.mod(qMinusOne),
                q.modInverse(p)));
        PublicKey publicKey = factory.generatePublic(new RSAPublicKeySpec(modulus, PUBLIC_EXPONENT));
        return new KeyPair(publicKey, privateKey);
    }

    private static boolean isUsablePrime(BigInteger candidate) {
        if (!candidate.isProbablePrime(PRIME_CERTAINTY)) {
            return false;
        }
        return candidate.subtract(BigInteger.ONE).gcd(PUBLIC_EXPONENT).equals(BigInteger.ONE);
    }

    private static BigInteger derivePrime(byte[] seed, String suffix) {
        Sha256Stream stream = new Sha256Stream(concat(seed, suffix.getBytes(StandardCharsets.UTF_8)));
        BigInteger candidate = new BigInteger(1, stream.read(64));
        candidate = candidate.setBit(0); // odd
        // Java's setBit(0) only guarantees oddness; the Xray derivation also forces
        // the two high bits of the first byte so that p*q is 1024 bits.
        candidate = candidate.or(new BigInteger("c0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000", 16));
        while (true) {
            if (isUsablePrime(candidate)) {
                return candidate;
            }
            candidate = candidate.add(BigInteger.TWO);
        }
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /**
     * SHA-256 counter stream matching Go's {@code sha256Stream}: every block is
     * {@code sha256(seed + "-" + counter)} with a decimal counter starting at 0.
     */
    private static final class Sha256Stream {
        private final byte[] seed;
        private long counter;
        private byte[] buffer = new byte[0];
        private int position;

        private Sha256Stream(byte[] seed) {
            this.seed = seed;
        }

        private byte[] read(int length) {
            byte[] result = new byte[length];
            int copied = 0;
            while (copied < length) {
                if (position >= buffer.length) {
                    refill();
                }
                int count = Math.min(length - copied, buffer.length - position);
                System.arraycopy(buffer, position, result, copied, count);
                position += count;
                copied += count;
            }
            return result;
        }

        private void refill() {
            byte[] suffix = ("-" + counter++).getBytes(StandardCharsets.US_ASCII);
            byte[] input = concat(seed, suffix);
            try {
                buffer = java.security.MessageDigest.getInstance("SHA-256").digest(input);
            } catch (GeneralSecurityException impossible) {
                throw new IllegalStateException(impossible);
            }
            position = 0;
        }
    }
}
