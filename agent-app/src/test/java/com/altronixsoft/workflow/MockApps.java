package com.altronixsoft.workflow;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * The mock CRM (MCP) and the mock rates service, started from their executable jars as real processes, once per
 * test JVM, on free ports. Import it into a test that needs them: {@code @Import(MockApps.class)}. The jars are
 * copied to {@code target/mock-apps} by the build ({@code pre-integration-test}), so run these tests with
 * {@code ./mvnw verify}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MockApps {

    /** Shared by the agent (to issue tokens in tests) and the mock CRM (to verify them). */
    public static final String APPROVAL_SECRET = "test-approval-secret-0123456789abcdef";

    private static final Path JARS = Path.of("target", "mock-apps");
    private static final Duration STARTUP = Duration.ofSeconds(90);

    private static String crmUrl;
    private static String ratesUrl;

    @Bean
    DynamicPropertyRegistrar mockAppsProperties() {
        return registry -> {
            registry.add("workflow.tools.crm-url", MockApps::crmUrl);
            registry.add("workflow.tools.rates-url", MockApps::ratesUrl);
        };
    }

    public static synchronized String crmUrl() {
        if (crmUrl == null) {
            crmUrl = start("mock-crm-mcp", List.of("--approval.token.secret=" + APPROVAL_SECRET));
        }
        return crmUrl;
    }

    public static synchronized String ratesUrl() {
        if (ratesUrl == null) {
            ratesUrl = start("mock-rates", List.of());
        }
        return ratesUrl;
    }

    private static String start(String module, List<String> args) {
        int port = freePort();
        Path jar = jar(module);
        String java = ProcessHandle.current().info().command().orElse("java");
        List<String> command = new ArrayList<>(
                List.of(java, "-jar", jar.toString(), "--server.port=" + port, "--spring.main.banner-mode=off"));
        command.addAll(args);
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(JARS.resolve(module + ".log").toFile())
                    .start();
            Runtime.getRuntime().addShutdownHook(new Thread(process::destroy));
            String url = "http://localhost:" + port;
            await().atMost(STARTUP)
                    .pollInterval(Duration.ofMillis(250))
                    .failFast(module + " exited, see " + JARS.resolve(module + ".log"), () -> !process.isAlive())
                    .until(() -> healthy(url));
            return url;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot start " + module, e);
        }
    }

    private static boolean healthy(String url) {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<Void> response = http.send(
                    HttpRequest.newBuilder(URI.create(url + "/actuator/health"))
                            .timeout(Duration.ofSeconds(2))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Path jar(String module) {
        try (DirectoryStream<Path> found = Files.newDirectoryStream(JARS, module + "-*-exec.jar")) {
            for (Path jar : found) {
                return jar;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("No " + JARS + ": run the tests with ./mvnw verify", e);
        }
        throw new IllegalStateException("No " + module + " jar in " + JARS + ": run the tests with ./mvnw verify");
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
