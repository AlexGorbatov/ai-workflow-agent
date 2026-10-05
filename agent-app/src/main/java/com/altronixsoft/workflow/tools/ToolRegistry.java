package com.altronixsoft.workflow.tools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every tool the agent can reach, by name: the CRM's tools (as the CRM lists them over MCP) and {@code getRates}.
 * Package-private on purpose: only {@link ToolGateway} calls tools, so nothing outside this package can get
 * hold of a callback that skips its checks. The CRM list is fetched on first use and kept once it succeeds.
 */
@Component
class ToolRegistry {

    private final Supplier<Map<String, ToolCallback>> loader;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Map<String, ToolCallback> tools;

    @Autowired
    ToolRegistry(CrmConnection crm, JsonMapper json, RatesTool rates) {
        this(() -> {
            Map<String, ToolCallback> loaded = new LinkedHashMap<>();
            crm.listTools().forEach(tool -> loaded.put(tool.name(), new McpToolCallback(crm, json, tool)));
            loaded.put(RatesTool.NAME, rates.callback());
            return loaded;
        });
    }

    private ToolRegistry(Supplier<Map<String, ToolCallback>> loader) {
        this.loader = loader;
    }

    /** A registry of fixed tools, for tests. */
    static ToolRegistry of(Map<String, ToolCallback> tools) {
        return new ToolRegistry(() -> tools);
    }

    Optional<ToolCallback> find(String name) {
        return Optional.ofNullable(all().get(name));
    }

    Set<String> names() {
        return all().keySet();
    }

    private Map<String, ToolCallback> all() {
        Map<String, ToolCallback> current = tools;
        if (current != null) {
            return current;
        }
        lock.lock();
        try {
            if (tools == null) {
                tools = Collections.unmodifiableMap(new LinkedHashMap<>(loader.get()));
            }
            return tools;
        } finally {
            lock.unlock();
        }
    }
}
