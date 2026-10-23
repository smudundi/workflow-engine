package com.example.workflow.handler;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

@Component
public class HandlerRegistry {

    private final Map<String, StepHandler> handlers;

    public HandlerRegistry(List<StepHandler> handlers) {
        Map<String, StepHandler> byType = new TreeMap<>();
        for (StepHandler handler : handlers) {
            if (byType.putIfAbsent(handler.type(), handler) != null) {
                throw new IllegalStateException("duplicate step handler for type " + handler.type());
            }
        }
        this.handlers = Map.copyOf(byType);
    }

    public boolean supports(String type) {
        return handlers.containsKey(type);
    }

    public StepHandler get(String type) {
        StepHandler handler = handlers.get(type);
        if (handler == null) {
            throw new IllegalArgumentException("unknown step type '" + type + "'");
        }
        return handler;
    }

    public Set<String> types() {
        return new TreeSet<>(handlers.keySet());
    }
}
