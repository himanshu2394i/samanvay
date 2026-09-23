package com.samanvay.orchestration.api;

import java.util.List;

public record EngineState(boolean done, List<String> activeActivities) {
    public static EngineState completed() {
        return new EngineState(true, List.of());
    }

    public static EngineState active(List<String> activities) {
        return new EngineState(false, activities);
    }
}
