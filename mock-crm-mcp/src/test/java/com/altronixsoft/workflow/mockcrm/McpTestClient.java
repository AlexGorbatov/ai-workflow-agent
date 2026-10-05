package com.altronixsoft.workflow.mockcrm;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Map;

/** A plain MCP client for tests: what an agent would see when it connects to the CRM. */
final class McpTestClient implements AutoCloseable {

    private final McpSyncClient client;

    McpTestClient(int port) {
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint("/mcp")
                .build();
        this.client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(15))
                .initializationTimeout(Duration.ofSeconds(15))
                .build();
        client.initialize();
    }

    McpSchema.ListToolsResult tools() {
        return client.listTools();
    }

    /** The text of the first content block; fails the test if the call came back as an error. */
    String call(String tool, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(tool, arguments));
        String text = text(result);
        if (Boolean.TRUE.equals(result.isError())) {
            throw new ToolError(text);
        }
        return text;
    }

    /** The error text of a call that is expected to fail. */
    String callExpectingError(String tool, Map<String, Object> arguments) {
        try {
            String text = call(tool, arguments);
            throw new AssertionError("expected an error but got: " + text);
        } catch (ToolError e) {
            return e.getMessage();
        }
    }

    private static String text(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(c -> ((McpSchema.TextContent) c).text())
                .findFirst()
                .orElse("");
    }

    @Override
    public void close() {
        client.close();
    }

    static final class ToolError extends RuntimeException {
        ToolError(String message) {
            super(message);
        }
    }
}
