package io.github.jerryt92.j2agent.jwt;

import com.auth0.jwt.algorithms.Algorithm;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.Objects;

public class AlgorithmBuilder {
    public enum AlgType {
        HmacSHA256, HmacSHA384, HmacSHA512, SHA256withRSA, SHA384withRSA, SHA512withRSA, SHA256withECDSA,
        SHA384withECDSA, SHA512withECDSA, ED25519, EDDSA, Kmac128, Kmac256
    }

    private static final int JAVA_15_CLASS_VERSION = 59;

    public static void registerBouncyCastleProvider() {
        float clazzVer = Float.parseFloat(System.getProperty("java.class.version"));
        if (clazzVer < JAVA_15_CLASS_VERSION && null == Security.getProvider("BC")) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private String prvKeyFile;
    private String pubKeyFile;
    private String secret;
    private AlgType algorithm;
    private Integer kmacOutputLengthBytes;

    private AlgorithmBuilder() {
    }

    public static AlgorithmBuilder newBuilder() {
        return new AlgorithmBuilder();
    }

    public AlgorithmBuilder algorithm(AlgType algorithm) {
        this.algorithm = algorithm;
        return this;
    }

    public AlgorithmBuilder secret(String secret) {
        this.secret = secret;
        return this;
    }

    public AlgorithmBuilder prvKeyFile(String prvKeyFile) {
        this.prvKeyFile = prvKeyFile;
        return this;
    }

    public AlgorithmBuilder pubKeyFile(String pubKeyFile) {
        this.pubKeyFile = pubKeyFile;
        return this;
    }

    public AlgorithmBuilder kmacOutputLengthBytes(Integer kmacOutputLengthBytes) {
        this.kmacOutputLengthBytes = kmacOutputLengthBytes;
        return this;
    }

    static byte[] getSecretBytes(String secret) {
        String base64Prefix = "base64:";
        if (secret.startsWith(base64Prefix)) {
            return Base64.getDecoder().decode(secret.substring(base64Prefix.length()));
        }
        return secret.getBytes(StandardCharsets.UTF_8);
    }

    public Algorithm build() throws NoSuchAlgorithmException, IOException, InvalidKeySpecException {
        Objects.requireNonNull(this.algorithm);
        return switch (this.algorithm) {
            case HmacSHA256 -> Algorithm.HMAC256(getSecretBytes(secret));
            case HmacSHA384 -> Algorithm.HMAC384(getSecretBytes(secret));
            case HmacSHA512 -> Algorithm.HMAC512(getSecretBytes(secret));
            case Kmac128 -> {
                if (null == kmacOutputLengthBytes) {
                    kmacOutputLengthBytes = 32;
                }
                yield new KMacAlgorithm(128, getSecretBytes(secret), kmacOutputLengthBytes);
            }
            case Kmac256 -> {
                if (null == kmacOutputLengthBytes) {
                    kmacOutputLengthBytes = 64;
                }
                yield new KMacAlgorithm(256, getSecretBytes(secret), kmacOutputLengthBytes);
            }
            case SHA256withRSA -> {
                KeyPair keyPair = KeyUtils.readKeyPair("RSA", prvKeyFile, pubKeyFile);
                yield Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());
            }
            case SHA384withRSA -> {
                KeyPair keyPair = KeyUtils.readKeyPair("RSA", prvKeyFile, pubKeyFile);
                yield Algorithm.RSA384((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());
            }
            case SHA512withRSA -> {
                KeyPair keyPair = KeyUtils.readKeyPair("RSA", prvKeyFile, pubKeyFile);
                yield Algorithm.RSA512((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());
            }
            case SHA256withECDSA -> {
                KeyPair keyPair = KeyUtils.readKeyPair("EC", prvKeyFile, pubKeyFile);
                yield Algorithm.ECDSA256((ECPublicKey) keyPair.getPublic(), (ECPrivateKey) keyPair.getPrivate());
            }
            case SHA384withECDSA -> {
                KeyPair keyPair = KeyUtils.readKeyPair("EC", prvKeyFile, pubKeyFile);
                yield Algorithm.ECDSA384((ECPublicKey) keyPair.getPublic(), (ECPrivateKey) keyPair.getPrivate());
            }
            case SHA512withECDSA -> {
                KeyPair keyPair = KeyUtils.readKeyPair("EC", prvKeyFile, pubKeyFile);
                yield Algorithm.ECDSA512((ECPublicKey) keyPair.getPublic(), (ECPrivateKey) keyPair.getPrivate());
            }
            case ED25519, EDDSA -> {
                KeyPair keyPair = KeyUtils.readKeyPair("ED25519", prvKeyFile, pubKeyFile);
                yield new Ed25519Algorithm(keyPair.getPublic(), keyPair.getPrivate());
            }
            default -> throw new IllegalArgumentException("illegal argument of algorithm " + this.algorithm);
        };
    }
}
