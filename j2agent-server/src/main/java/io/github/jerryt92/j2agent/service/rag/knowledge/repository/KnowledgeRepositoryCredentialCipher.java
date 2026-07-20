package io.github.jerryt92.j2agent.service.rag.knowledge.repository;

import com.alibaba.fastjson2.JSON;
import io.github.jerryt92.j2agent.config.rag.KnowledgeRepoProperties;
import io.github.jerryt92.j2agent.model.repository.KnowledgeRepositoryDtos;
import io.github.jerryt92.j2agent.utils.HashUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 知识库仓库凭据加解密组件。
 */
@Component
public class KnowledgeRepositoryCredentialCipher {
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final KnowledgeRepoProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public KnowledgeRepositoryCredentialCipher(KnowledgeRepoProperties properties) {
        this.properties = properties;
    }

    public String encrypt(KnowledgeRepositoryDtos.CredentialConfig credentialConfig) {
        if (credentialConfig == null || isEmpty(credentialConfig)) {
            return null;
        }
        return encrypt(JSON.toJSONString(credentialConfig));
    }

    public KnowledgeRepositoryDtos.CredentialConfig decrypt(String cipherText) {
        if (StringUtils.isBlank(cipherText)) {
            return new KnowledgeRepositoryDtos.CredentialConfig();
        }
        return JSON.parseObject(decryptToString(cipherText), KnowledgeRepositoryDtos.CredentialConfig.class);
    }

    private String encrypt(String plainText) {
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes(), "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
            buffer.put(iv);
            buffer.put(encrypted);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("加密知识库仓库凭据失败", e);
        }
    }

    private String decryptToString(String cipherText) {
        try {
            byte[] payload = Base64.getDecoder().decode(cipherText);
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes(), "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密知识库仓库凭据失败", e);
        }
    }

    private byte[] keyBytes() throws NoSuchAlgorithmException {
        String secret = StringUtils.defaultIfBlank(properties.getCredentialSecret(),
                "j2agent-knowledge-repository-default-key");
        String sha = HashUtil.getMessageDigest(secret.getBytes(StandardCharsets.UTF_8), HashUtil.MdAlgorithm.SHA256);
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(sha.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private boolean isEmpty(KnowledgeRepositoryDtos.CredentialConfig config) {
        return StringUtils.isBlank(config.getUsername())
                && StringUtils.isBlank(config.getPassword())
                && StringUtils.isBlank(config.getToken())
                && StringUtils.isBlank(config.getAccessKey())
                && StringUtils.isBlank(config.getSecretKey());
    }
}
