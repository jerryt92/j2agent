package io.github.jerryt92.j2agent.jwt;

import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.SignatureGenerationException;
import com.auth0.jwt.exceptions.SignatureVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.bouncycastle.crypto.macs.KMAC;
import org.bouncycastle.crypto.params.KeyParameter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

public class KMacAlgorithm extends Algorithm {
    private final byte[] secret;
    private final int bitLength;
    private final int outputLengthBytes;

    public KMacAlgorithm(int bitLength, byte[] secret, int outputLengthBytes) {
        super("KMac" + bitLength, "KMac" + bitLength);
        this.bitLength = bitLength;
        this.secret = secret;
        this.outputLengthBytes = outputLengthBytes;
    }

    @Override
    public void verify(DecodedJWT jwt) throws SignatureVerificationException {
        byte[] inputTag = Base64.getUrlDecoder().decode(jwt.getSignature());
        byte[] computeTag = encrypt((jwt.getHeader() + "." + jwt.getPayload()).getBytes(StandardCharsets.UTF_8));
        boolean valid = MessageDigest.isEqual(inputTag, computeTag);
        if (!valid) {
            throw new SignatureVerificationException(this);
        }
    }

    byte[] encrypt(byte[] contentBytes) {
        KMAC kmac = new KMAC(bitLength, new byte[0]);
        kmac.init(new KeyParameter(this.secret));
        kmac.update(contentBytes, 0, contentBytes.length);
        byte[] out = new byte[this.outputLengthBytes];
        kmac.doOutput(out, 0, this.outputLengthBytes);
        return out;
    }

    @Override
    public byte[] sign(byte[] contentBytes) throws SignatureGenerationException {
        return encrypt(contentBytes);
    }
}
