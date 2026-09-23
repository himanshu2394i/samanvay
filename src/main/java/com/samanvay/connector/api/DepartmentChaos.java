package com.samanvay.connector.api;

public interface DepartmentChaos {
    void kill(String dataSourceCode);

    void revive(String dataSourceCode);

    boolean killed(String dataSourceCode);
}
