package com.lza.aifactory;

import com.lza.aifactory.pipeline.BashPipelineExecutor;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BashPipelineExecutorEnvTest {

    @Test
    void stripsInheritedSourcePathAndProjectMode() {
        // Simulate the gateway's own environment leaking these vars.
        Map<String, String> env = new HashMap<>();
        env.put("SOURCE_PATH", "/etc");
        env.put("PROJECT_MODE", "local");
        env.put("PATH", "/usr/bin");
        // The operator approval secret must never reach the pipeline (untrusted AI
        // agents / project tests would inherit it and could self-approve).
        env.put("AIF_INTERNAL_SECRET", "operator-key");

        BashPipelineExecutor.applyEnv(env, "TASK-1", Map.of()); // request sets neither

        assertFalse(env.containsKey("SOURCE_PATH"), "inherited SOURCE_PATH must be stripped");
        assertFalse(env.containsKey("PROJECT_MODE"), "inherited PROJECT_MODE must be stripped");
        assertFalse(env.containsKey("AIF_INTERNAL_SECRET"),
                "operator secret must never reach the pipeline env");
        assertEquals("TASK-1", env.get("TASK_ID"));
        assertEquals("/usr/bin", env.get("PATH"));
    }

    @Test
    void requestValuesWinOverInherited() {
        Map<String, String> env = new HashMap<>();
        env.put("SOURCE_PATH", "/etc"); // inherited (attacker-ish)

        BashPipelineExecutor.applyEnv(env, "TASK-2",
                Map.of("PROJECT_MODE", "local", "SOURCE_PATH", "/validated/path"));

        assertEquals("/validated/path", env.get("SOURCE_PATH"), "only the validated value should survive");
        assertEquals("local", env.get("PROJECT_MODE"));
    }
}
