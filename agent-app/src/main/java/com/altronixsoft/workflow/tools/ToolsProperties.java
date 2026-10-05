package com.altronixsoft.workflow.tools;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param crmUrl base URL of the CRM's MCP server
 * @param crmEndpoint the MCP endpoint path on it
 * @param ratesUrl base URL of the rates service
 * @param ratesSimulate if set ({@code slow} or {@code error}), sent as {@code X-Simulate} on every rates call:
 *     how a demo shows the agent coping with a sick dependency
 * @param policies one entry per tool the agent may call; a tool without one is refused
 */
@ConfigurationProperties("workflow.tools")
public record ToolsProperties(
        String crmUrl, String crmEndpoint, String ratesUrl, String ratesSimulate, Map<String, ToolPolicy> policies) {

    public ToolsProperties {
        crmUrl = crmUrl == null ? "http://localhost:8091" : crmUrl;
        crmEndpoint = crmEndpoint == null ? "/mcp" : crmEndpoint;
        ratesUrl = ratesUrl == null ? "http://localhost:8092" : ratesUrl;
        policies = policies == null ? Map.of() : Map.copyOf(policies);
    }
}
