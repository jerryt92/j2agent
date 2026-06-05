package io.github.jerryt92.j2agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

// --spring.config.location=classpath:/application.yaml
@SpringBootApplication
public class AICenterStarterMain {
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(AICenterStarterMain.class, args);
        printStartupInfo(context);
    }

    private static void printStartupInfo(ConfigurableApplicationContext context) {
        Environment env = context.getEnvironment();
        String port = env.getRequiredProperty("server.port");
        String contextPath = env.getProperty("server.servlet.context-path", "");
        String host = "localhost";
        String[] profiles = env.getActiveProfiles();
        String profileInfo = profiles.length > 0 ? String.join(",", profiles) : "default";
        System.out.println("\n" + "=".repeat(60));
        System.out.println("🎉 Application started successfully!");
        System.out.println("🌐 Application URL: http://" + host + ":" + port + contextPath);
        System.out.println("📁 Profile(s): " + profileInfo);
        System.out.println("⏰ Started at: " + java.time.LocalDateTime.now());
        System.out.println("=".repeat(60));
    }
}