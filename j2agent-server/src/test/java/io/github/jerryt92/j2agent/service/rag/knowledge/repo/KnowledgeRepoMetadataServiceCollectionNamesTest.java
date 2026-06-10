package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeRepoMetadataServiceCollectionNamesTest {

    @Test
    void listConfiguredCollectionNames_deduplicatesAndTrims() throws Exception {
        KnowledgeRepoMetadataService service = new KnowledgeRepoMetadataService(
                new io.github.jerryt92.j2agent.config.rag.KnowledgeRepoProperties());
        injectCollections(service, " rc_wiki ", "rc_wiki", "other");

        Set<String> names = service.listConfiguredCollectionNames();

        assertEquals(Set.of("rc_wiki", "other"), names);
    }

    private static void injectCollections(KnowledgeRepoMetadataService service, String... collections) throws Exception {
        var field = KnowledgeRepoMetadataService.class.getDeclaredField("collectionByPrefixDir");
        field.setAccessible(true);
        var map = new java.util.HashMap<java.nio.file.Path, String>();
        for (int i = 0; i < collections.length; i++) {
            map.put(java.nio.file.Path.of("/tmp/prefix-" + i), collections[i]);
        }
        field.set(service, java.util.Collections.unmodifiableMap(map));
    }
}
