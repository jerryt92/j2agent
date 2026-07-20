package io.github.jerryt92.j2agent.config.web;

import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpsRedirectConfigTest {

    @Test
    void doesNotCreateConnectorWhenSslDisabled() {
        HttpsRedirectConfig config = new HttpsRedirectConfig(false, 30111, "30110");

        assertTrue(config.createRedirectConnector().isEmpty());
    }

    @Test
    void createsConnectorWhenSslEnabledAndRedirectPortConfigured() {
        HttpsRedirectConfig config = new HttpsRedirectConfig(true, 30111, "30110");

        Optional<Connector> connector = config.createRedirectConnector();

        assertTrue(connector.isPresent());
        assertEquals("http", connector.get().getScheme());
        assertEquals(30110, connector.get().getPort());
        assertEquals(30111, connector.get().getRedirectPort());
    }

    @Test
    void doesNotCreateConnectorWhenRedirectPortBlank() {
        HttpsRedirectConfig config = new HttpsRedirectConfig(true, 30111, "");

        assertTrue(config.createRedirectConnector().isEmpty());
    }

    @Test
    void doesNotCreateConnectorWhenRedirectPortEqualsServerPort() {
        HttpsRedirectConfig config = new HttpsRedirectConfig(true, 30111, "30111");

        assertTrue(config.createRedirectConnector().isEmpty());
    }
}
