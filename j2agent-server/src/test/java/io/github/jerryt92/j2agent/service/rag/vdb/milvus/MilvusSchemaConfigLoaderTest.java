package io.github.jerryt92.j2agent.service.rag.vdb.milvus;

import io.milvus.v2.common.DataType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MilvusSchemaConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void load_blankPath_usesDefaultFieldsWithRuntimeDimension() {
        List<MilvusSchemaDefinition.FieldDef> fields = MilvusSchemaConfigLoader.load(null, 768);
        MilvusSchemaDefinition.FieldDef embedding = findEmbeddingField(fields);
        assertEquals(768, embedding.getDimension());
        assertEquals(DataType.FloatVector, embedding.getDataType());
    }

    @Test
    void load_externalJson_overridesHardcodedDimensionWithRuntime() throws Exception {
        Path schemaFile = tempDir.resolve("schema.json");
        Files.writeString(schemaFile, """
                {
                  "fields": [
                    {
                      "name": "embedding",
                      "dataType": "FloatVector",
                      "dimension": 1024,
                      "description": "dense"
                    }
                  ]
                }
                """);
        List<MilvusSchemaDefinition.FieldDef> fields = MilvusSchemaConfigLoader.load(schemaFile.toString(), 768);
        MilvusSchemaDefinition.FieldDef embedding = findEmbeddingField(fields);
        assertEquals(768, embedding.getDimension());
    }

    @Test
    void load_defaultFields_matchRuntimeDimension() {
        List<MilvusSchemaDefinition.FieldDef> fields = MilvusSchemaDefinition.defaultFields(1024);
        MilvusSchemaDefinition.FieldDef embedding = findEmbeddingField(fields);
        assertEquals(1024, embedding.getDimension());
    }

    private static MilvusSchemaDefinition.FieldDef findEmbeddingField(List<MilvusSchemaDefinition.FieldDef> fields) {
        MilvusSchemaDefinition.FieldDef embedding = fields.stream()
                .filter(field -> MilvusSchemaDefinition.FIELD_EMBEDDING.equals(field.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(embedding, "embedding field not found");
        return embedding;
    }
}
