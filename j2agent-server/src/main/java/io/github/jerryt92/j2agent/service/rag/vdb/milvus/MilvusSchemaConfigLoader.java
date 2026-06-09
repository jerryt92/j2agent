package io.github.jerryt92.j2agent.service.rag.vdb.milvus;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.milvus.v2.common.DataType;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Milvus 外置 schema 配置加载器。
 */
@Slf4j
public final class MilvusSchemaConfigLoader {
    private MilvusSchemaConfigLoader() {
    }

    /**
     * 读取外置 schema，失败时返回默认定义。
     */
    public static List<MilvusSchemaDefinition.FieldDef> load(String schemaConfigPath, int dimension) {
        if (StringUtils.isBlank(schemaConfigPath)) {
            return MilvusSchemaDefinition.defaultFields(dimension);
        }
        try {
            String content = readConfigContent(schemaConfigPath);
            JSONObject root = JSONObject.parseObject(content);
            JSONArray fields = root.getJSONArray("fields");
            if (fields == null || fields.isEmpty()) {
                return MilvusSchemaDefinition.defaultFields(dimension);
            }
            List<MilvusSchemaDefinition.FieldDef> defs = new ArrayList<>();
            for (int i = 0; i < fields.size(); i++) {
                JSONObject field = fields.getJSONObject(i);
                DataType dataType = DataType.valueOf(field.getString("dataType"));
                Integer fieldDimension = field.getInteger("dimension");
                if (isDenseVectorType(dataType)) {
                    if (fieldDimension != null && !fieldDimension.equals(dimension)) {
                        log.warn("外置 Milvus schema 字段 {} 的 dimension={} 与当前 Embedding 维度 {} 不一致，将使用运行时维度",
                                field.getString("name"), fieldDimension, dimension);
                    }
                    fieldDimension = dimension;
                }
                defs.add(MilvusSchemaDefinition.FieldDef.builder()
                        .name(field.getString("name"))
                        .dataType(dataType)
                        .maxLength(field.getInteger("maxLength"))
                        .dimension(fieldDimension)
                        .primaryKey(field.getBooleanValue("primaryKey"))
                        .autoId(field.getBooleanValue("autoId"))
                        .enableAnalyzer(field.getBooleanValue("enableAnalyzer"))
                        .description(field.getString("description"))
                        .build());
            }
            return defs;
        } catch (Exception e) {
            return MilvusSchemaDefinition.defaultFields(dimension);
        }
    }

    private static boolean isDenseVectorType(DataType dataType) {
        return DataType.FloatVector.equals(dataType)
                || DataType.BinaryVector.equals(dataType)
                || DataType.Float16Vector.equals(dataType)
                || DataType.BFloat16Vector.equals(dataType);
    }

    /**
     * 读取 schema 配置内容，支持 classpath 或绝对路径。
     */
    private static String readConfigContent(String schemaConfigPath) throws IOException {
        if (schemaConfigPath.startsWith("classpath:/")) {
            String relativePath = schemaConfigPath.substring("classpath:/".length());
            return Files.readString(new ClassPathResource(relativePath).getFile().toPath());
        }
        return Files.readString(Path.of(schemaConfigPath));
    }
}

