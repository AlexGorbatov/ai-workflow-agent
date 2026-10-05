package com.altronixsoft.workflow;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** The one PostgreSQL for tests and test-run: the same image as compose.yaml, wired by @ServiceConnection. */
@TestConfiguration(proxyBeanMethods = false)
public class ContainersConfig {

    static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres");

    /** The same Mailpit version as compose.yaml: the SMTP sink the agent writes to and the inbox it reads. */
    static final DockerImageName MAILPIT_IMAGE = DockerImageName.parse("axllent/mailpit:v1.31.3");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(POSTGRES_IMAGE);
    }

    @Bean
    GenericContainer<?> mailpit() {
        return new GenericContainer<>(MAILPIT_IMAGE)
                .withExposedPorts(1025, 8025)
                .waitingFor(Wait.forHttp("/livez").forPort(8025));
    }

    @Bean
    DynamicPropertyRegistrar mailpitProperties(GenericContainer<?> mailpit) {
        return registry -> {
            registry.add("spring.mail.host", mailpit::getHost);
            registry.add("spring.mail.port", () -> mailpit.getMappedPort(1025));
            registry.add(
                    "workflow.mail.mailpit-url",
                    () -> "http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025));
        };
    }
}
