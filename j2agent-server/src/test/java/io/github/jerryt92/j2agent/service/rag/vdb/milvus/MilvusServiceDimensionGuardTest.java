package io.github.jerryt92.j2agent.service.rag.vdb.milvus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MilvusServiceDimensionGuardTest {

    @Test
    void getExpectedDimension_reflectsRebuildParameter() {
        MilvusService service = new MilvusService("http://localhost:19530", "token", null);
        service.reBuildVectorDatabase(1024, "COSINE");
        assertEquals(1024, service.getExpectedDimension());
        service.reBuildVectorDatabase(768, "IP");
        assertEquals(768, service.getExpectedDimension());
    }
}
