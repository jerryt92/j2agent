package io.github.jerryt92.j2agent.jwt;

import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.SignatureGenerationException;
import com.auth0.jwt.exceptions.SignatureVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Base64;

public class Ed25519Algorithm extends Algorithm {
    private static final byte JWT_PART_SEPARATOR = (byte) 46;
    private final PublicKey pubKey;
    private final PrivateKey prvKey;

    public Ed25519Algorithm(PublicKey pubKey, PrivateKey prvKey) {
        super("ED25519", "EDDSA/ED25519");
        this.pubKey = pubKey;
        this.prvKey = prvKey;
    }

    @Override
    public void verify(DecodedJWT jwt) throws SignatureVerificationException {
        try {
            if (this.pubKey == null) {
                throw new IllegalStateException("The given Public Key is null.");
            }
            Signature sig = Signature.getInstance("ED25519");
            sig.initVerify(this.pubKey);
            sig.update(jwt.getHeader().getBytes(StandardCharsets.UTF_8));
            sig.update(JWT_PART_SEPARATOR);
            sig.update(jwt.getPayload().getBytes(StandardCharsets.UTF_8));
            boolean valid = sig.verify(Base64.getUrlDecoder().decode(jwt.getSignature()));
            if (!valid) {
                throw new SignatureVerificationException(this);
            }
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException | IllegalStateException e) {
            throw new SignatureVerificationException(this, e);
        }
    }

    @Override
    public byte[] sign(byte[] contentBytes) throws SignatureGenerationException {
        try {
            if (this.prvKey == null) {
                throw new IllegalStateException("The given Private Key is null.");
            }
            Signature sig = Signature.getInstance("ED25519");
            sig.initSign(prvKey);
            sig.update(contentBytes);
            return sig.sign();
        } catch (NoSuchAlgorithmException | SignatureException | InvalidKeyException | IllegalStateException e) {
            throw new SignatureGenerationException(this, e);
        }
    }
}
