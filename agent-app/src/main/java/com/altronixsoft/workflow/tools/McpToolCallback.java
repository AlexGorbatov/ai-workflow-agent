package com.altronixsoft.workflow.tools;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * One CRM tool as a Spring AI {@link ToolCallback}. Returns the tool's text result as is. A result the CRM marks
 * as an error (bad arguments, a rejected token) is final; a broken connection is worth retrying.
 */
class McpToolCallback implements ToolCallback {

    private static final TypeReference<Map<String, Object>> ARGUMENTS = new TypeReference<>() {};

    private final CrmConnection crm;
    private final JsonMapper json;
    private final ToolDefinition definition;

    McpToolCallback(CrmConnection crm, JsonMapper json, McpSchema.Tool tool) {
        this.crm = crm;
        this.json = json;
        this.definition = DefaultToolDefinition.builder()
                .name(tool.name())
                .description(tool.description() == null ? tool.name() : tool.description())
                .inputSchema(json.writeValueAsString(tool.inputSchema()))
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        Map<String, Object> arguments = json.readValue(toolInput, ARGUMENTS);
        McpSchema.CallToolResult result;
        try {
            result = crm.callTool(definition.name(), arguments);
        } catch (ToolCallFailed e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ToolCallFailed("CRM call " + definition.name() + " failed: " + e.getMessage(), true, e);
        }
        String text = text(result);
        if (Boolean.TRUE.equals(result.isError())) {
            throw new ToolCallFailed("CRM refused " + definition.name() + ": " + text, false);
        }
        return text;
    }

    private static String text(McpSchema.CallToolResult result) {
        if (result.content() == null) {
            return "";
        }
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(c -> ((McpSchema.TextContent) c).text())
                .findFirst()
                .orElse("");
    }
}
