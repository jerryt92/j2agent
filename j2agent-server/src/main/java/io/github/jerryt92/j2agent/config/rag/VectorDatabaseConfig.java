package io.github.jerryt92.j2agent.config.rag;

import io.github.jerryt92.j2agent.service.rag.vdb.VectorDatabaseService;
import io.github.jerryt92.j2agent.service.rag.vdb.milvus.MilvusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class VectorDatabaseConfig {
    @Value("${j2agent.vector-database.provider}")
    public String vectorDatabase;
    @Value("${j2agent.vector-database.milvus.cluster-endpoint}")
    private String milvusClusterEndpoint;
    @Value("${j2agent.vector-database.milvus.token}")
    private String milvusToken;
    @Value("${j2agent.vector-database.milvus.schema-config-path:}")
    private String milvusSchemaConfigPath;

    @Bean
    public VectorDatabaseService vectorDatabaseService() {
        VectorDatabaseService vectorDatabaseService;
        switch (vectorDatabase) {
            case "milvus":
                vectorDatabaseService = new MilvusService(
                        milvusClusterEndpoint,
                        milvusToken,
                        milvusSchemaConfigPath
                );
                break;
            default:
                throw new RuntimeException("Unknown vector database: " + vectorDatabase);
        }
        return vectorDatabaseService;
    }
}
