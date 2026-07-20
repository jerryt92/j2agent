package io.github.jerryt92.j2agent.service.rag.knowledge.repository;

import io.github.jerryt92.j2agent.config.rag.KnowledgeRepoProperties;
import io.github.jerryt92.j2agent.model.repository.KnowledgeRepositoryDtos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class KnowledgeRepositoryCredentialCipherTest {

    @Test
    void encryptsAndDecryptsCredentialConfig() {
        KnowledgeRepoProperties properties = new KnowledgeRepoProperties();
        properties.setCredentialSecret("test-secret");
        KnowledgeRepositoryCredentialCipher cipher = new KnowledgeRepositoryCredentialCipher(properties);
        KnowledgeRepositoryDtos.CredentialConfig config = new KnowledgeRepositoryDtos.CredentialConfig();
        config.setUsername("alice");
        config.setPassword("secret-token");

        String encrypted = cipher.encrypt(config);

        assertNotNull(encrypted);
        assertFalse(encrypted.contains("secret-token"));
        KnowledgeRepositoryDtos.CredentialConfig decrypted = cipher.decrypt(encrypted);
        assertEquals("alice", decrypted.getUsername());
        assertEquals("secret-token", decrypted.getPassword());
    }
}
