package io.github.jerryt92.j2agent.config.database;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Set;

/**
 * 全新库：在 Flyway 迁移前执行 {@code sql/schema} 与 {@code sql/data} 引导脚本。
 */
@Slf4j
@Configuration
public class SqlBootstrapFlywayConfig {

    private static final String SCHEMA_SCRIPT = "sql/schema/postgresql/schemas.sql";
    private static final String CORE_TABLE = "api_key_info";
    private static final Set<String> ALLOWED_LOCALES = Set.of("zh_CN", "en_US");

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy(
            DataSource dataSource,
            @Value("${I18N:zh_CN}") String i18n) {
        String locale = normalizeLocale(i18n);
        return flyway -> {
            if (needsBootstrap(dataSource)) {
                log.info("Empty database detected, applying SQL bootstrap (locale={})", locale);
                runScript(dataSource, SCHEMA_SCRIPT);
                runScript(dataSource, "sql/data/postgresql/" + locale + ".sql");
            }
            flyway.migrate();
        };
    }

    private static boolean needsBootstrap(DataSource dataSource) {
        String sql = """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, CORE_TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) == 0;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to detect database bootstrap state", e);
        }
        return true;
    }

    private static void runScript(DataSource dataSource, String classpathLocation) {
        var resource = new ClassPathResource(classpathLocation);
        if (!resource.exists()) {
            throw new IllegalStateException("SQL bootstrap script not found: " + classpathLocation);
        }
        var populator = new ResourceDatabasePopulator(resource);
        populator.setSeparator(";");
        populator.setContinueOnError(false);
        try {
            populator.execute(dataSource);
            log.info("Applied SQL bootstrap script: {}", classpathLocation);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to apply SQL bootstrap script: " + classpathLocation, e);
        }
    }

    private static String normalizeLocale(String i18n) {
        if (i18n == null || i18n.isBlank()) {
            return "zh_CN";
        }
        String locale = i18n.trim();
        if (!ALLOWED_LOCALES.contains(locale)) {
            throw new IllegalArgumentException(
                    "Unsupported I18N locale: " + locale + ", allowed: " + ALLOWED_LOCALES);
        }
        return locale;
    }
}
