package com.altronixsoft.workflow.tools;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * The MCP session with the CRM. Opened on first use, not at startup: the agent must start (and take mail in)
 * while the CRM is down, and a failed connect is retried on the next call instead of failing the context.
 */
@Component
class CrmConnection {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final ToolsProperties properties;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile McpSyncClient client;

    CrmConnection(ToolsProperties properties) {
        this.properties = properties;
    }

    List<McpSchema.Tool> listTools() {
        return client().listTools().tools();
    }

    McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments) {
        return client().callTool(new McpSchema.CallToolRequest(name, arguments));
    }

    private McpSyncClient client() {
        McpSyncClient current = client;
        if (current != null) {
            return current;
        }
        lock.lock();
        try {
            if (client == null) {
                client = connect();
            }
            return client;
        } finally {
            lock.unlock();
        }
    }

    private McpSyncClient connect() {
        var transport = HttpClientStreamableHttpTransport.builder(properties.crmUrl())
                .endpoint(properties.crmEndpoint())
                .build();
        McpSyncClient created = McpClient.sync(transport)
                .requestTimeout(REQUEST_TIMEOUT)
                .initializationTimeout(REQUEST_TIMEOUT)
                .build();
        try {
            created.initialize();
            return created;
        } catch (RuntimeException e) {
            created.close();
            throw new ToolCallFailed("CRM is not reachable at " + properties.crmUrl() + ": " + e.getMessage(), true, e);
        }
    }

    @PreDestroy
    void close() {
        McpSyncClient current = client;
        if (current != null) {
            current.close();
        }
    }
}
