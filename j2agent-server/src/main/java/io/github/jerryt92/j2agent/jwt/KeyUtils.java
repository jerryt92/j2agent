package io.github.jerryt92.j2agent.jwt;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;

public class KeyUtils {
    private KeyUtils() {
    }

    public static KeyPair readKeyPair(String alg, String prvKeyFile, String pubKeyFile)
            throws NoSuchAlgorithmException, IOException, InvalidKeySpecException {
        KeyFactory keyFactory = KeyFactory.getInstance(alg);
        PrivateKey prvKey;
        PublicKey publicKey;
        if (null == prvKeyFile) {
            prvKey = null;
        } else {
            prvKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(readPrvPemFile(prvKeyFile)));
        }
        if (null == pubKeyFile) {
            publicKey = null;
        } else {
            publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(readPubPemFile(pubKeyFile)));
        }
        return new KeyPair(publicKey, prvKey);
    }

    private static byte[] readPrvPemFile(String prvKeyFile) throws IOException {
        String content = readFile(prvKeyFile);
        content = content.replace("-----BEGIN PRIVATE KEY-----", "");
        content = content.replace("-----END PRIVATE KEY-----", "");
        content = content.replace("\n", "");
        return Base64.getDecoder().decode(content);
    }

    private static byte[] readPubPemFile(String pubKeyFile) throws IOException {
        String content = readFile(pubKeyFile);
        content = content.replace("-----BEGIN PUBLIC KEY-----", "");
        content = content.replace("-----END PUBLIC KEY-----", "");
        content = content.replace("\n", "");
        return Base64.getDecoder().decode(content);
    }

    private static String readFile(String fileName) throws IOException {
        Objects.requireNonNull(fileName);
        if (fileName.startsWith("classpath:")) {
            fileName = fileName.substring("classpath:".length());
            try (InputStream in = KeyUtils.class.getResourceAsStream(fileName)) {
                Objects.requireNonNull(in);
                return new String(in.readAllBytes());
            }
        }
        if (fileName.startsWith("file:")) {
            URL url = new URL(fileName);
            try (InputStream in = url.openStream()) {
                return new String(in.readAllBytes());
            }
        }
        return Files.readString(Path.of(fileName));
    }
}
