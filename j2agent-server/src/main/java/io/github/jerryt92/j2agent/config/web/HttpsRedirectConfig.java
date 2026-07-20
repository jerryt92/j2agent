package io.github.jerryt92.j2agent.config.web;

import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.descriptor.web.SecurityCollection;
import org.apache.tomcat.util.descriptor.web.SecurityConstraint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Configuration
public class HttpsRedirectConfig implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    private static final Logger log = LoggerFactory.getLogger(HttpsRedirectConfig.class);

    private final boolean sslEnabled;
    private final int httpsPort;
    private final String redirectPort;

    public HttpsRedirectConfig(@Value("${server.ssl.enabled:false}") boolean sslEnabled,
                               @Value("${server.port}") int httpsPort,
                               @Value("${j2agent.https.redirect-port:}") String redirectPort) {
        this.sslEnabled = sslEnabled;
        this.httpsPort = httpsPort;
        this.redirectPort = redirectPort;
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        createRedirectConnector().ifPresent(connector -> {
            factory.addAdditionalTomcatConnectors(connector);
            factory.addContextCustomizers(this::requireConfidentialTransport);
            log.info("HTTP to HTTPS redirect enabled: http port {} -> https port {}",
                    connector.getPort(), httpsPort);
        });
    }

    Optional<Connector> createRedirectConnector() {
        Optional<Integer> httpPort = resolveRedirectPort();
        if (httpPort.isEmpty()) {
            return Optional.empty();
        }

        Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        connector.setScheme("http");
        connector.setSecure(false);
        connector.setPort(httpPort.get());
        connector.setRedirectPort(httpsPort);
        return Optional.of(connector);
    }

    private Optional<Integer> resolveRedirectPort() {
        if (!sslEnabled) {
            return Optional.empty();
        }
        if (!StringUtils.hasText(redirectPort)) {
            return Optional.empty();
        }

        int httpPort;
        try {
            httpPort = Integer.parseInt(redirectPort.trim());
        } catch (NumberFormatException ex) {
            log.warn("Ignoring invalid j2agent.https.redirect-port value: {}", redirectPort);
            return Optional.empty();
        }

        if (httpPort < 1 || httpPort > 65535) {
            log.warn("Ignoring out-of-range j2agent.https.redirect-port value: {}", httpPort);
            return Optional.empty();
        }
        if (httpPort == httpsPort) {
            log.warn("Ignoring j2agent.https.redirect-port because it equals server.port: {}", httpPort);
            return Optional.empty();
        }
        return Optional.of(httpPort);
    }

    private void requireConfidentialTransport(Context context) {
        SecurityConstraint constraint = new SecurityConstraint();
        constraint.setUserConstraint("CONFIDENTIAL");

        SecurityCollection collection = new SecurityCollection();
        collection.addPattern("/*");
        constraint.addCollection(collection);

        context.addConstraint(constraint);
    }
}
