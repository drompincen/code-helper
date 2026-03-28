package com.javaducker.server.model;

import java.util.List;
import java.util.Map;

public record ReladomoConfigResult(
    List<ConnectionManagerDef> connectionManagers,
    List<ObjectConfigDef> objectConfigs
) {
    public record ConnectionManagerDef(
        String name,
        String className,
        Map<String, String> properties
    ) {}

    public record ObjectConfigDef(
        String objectName,
        String connectionManager,
        String cacheType,
        boolean loadCacheOnStartup
    ) {}
}
