package io.github.jerryt92.j2agent.event;

import java.util.List;

public class PropertiesUpdatedEvent {
    private final List<String> propertyNames;

    public PropertiesUpdatedEvent(List<String> propertyNames) {
        this.propertyNames = propertyNames;
    }

    public List<String> getPropertyNames() {
        return propertyNames;
    }
}
