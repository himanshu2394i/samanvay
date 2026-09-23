package com.samanvay.connector.internal.service;

import com.samanvay.connector.api.DepartmentChaos;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
class InMemoryDepartmentChaos implements DepartmentChaos {

    private final Set<String> killed = ConcurrentHashMap.newKeySet();

    @Override
    public void kill(String dataSourceCode) {
        killed.add(dataSourceCode);
    }

    @Override
    public void revive(String dataSourceCode) {
        killed.remove(dataSourceCode);
    }

    @Override
    public boolean killed(String dataSourceCode) {
        return killed.contains(dataSourceCode);
    }
}
