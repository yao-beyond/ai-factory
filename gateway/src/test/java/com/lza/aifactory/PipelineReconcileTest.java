package com.lza.aifactory;

import com.lza.aifactory.dto.TaskRecord;
import com.lza.aifactory.dto.TaskStatus;
import com.lza.aifactory.service.TaskService;
import com.lza.aifactory.dto.IssueDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies the executor's safety net: a pipeline process that exits non-zero
 * without writing a terminal status is reconciled to FAILED instead of hanging
 * forever as "in progress". Uses /bin/false as the pipeline script.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "ai-factory.work-dir=${java.io.tmpdir}/ai-factory-reconcile-test",
        "ai-factory.pipeline-script=${user.dir}/src/test/resources/fail-pipeline.sh"
})
class PipelineReconcileTest {

    @Autowired
    private TaskService taskService;

    @Test
    void nonZeroExitWithoutTerminalStatusIsReconciledToFailed() throws Exception {
        IssueDto dto = new IssueDto();
        dto.setSource("web");
        dto.setExternalId("RECON-1");
        dto.setTitle("t");
        dto.setDescription("d");
        dto.setMaxAgents(1);
        taskService.submit(dto);

        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        TaskStatus status = null;
        while (Instant.now().isBefore(deadline)) {
            TaskRecord r = taskService.findStatus("RECON-1").orElse(null);
            if (r != null && r.status() == TaskStatus.FAILED) {
                status = r.status();
                break;
            }
            Thread.sleep(100);
        }
        if (status == null) {
            fail("task was not reconciled to FAILED after the process exited non-zero");
        }
        assertEquals(TaskStatus.FAILED, status);
    }
}
