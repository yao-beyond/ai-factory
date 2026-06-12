package com.lza.aifactory.service;

import com.lza.aifactory.dto.IssueDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The repository allowlist is a security boundary: with a configured allowlist,
 * a URL-like repo outside it must be rejected at submit time. Short repo names
 * (resolved server-side from config) are not blocked by this check.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "ai-factory.work-dir=${java.io.tmpdir}/ai-factory-allowlist-test",
        "ai-factory.pipeline-script=${user.dir}/src/test/resources/noop-pipeline.sh",
        "ai-factory.allow-repositories=https://github.com/acme/*,git@github.com:acme/*"
})
class TaskServiceAllowlistTest {

    @Autowired
    private TaskService taskService;

    private IssueDto dto(String externalId, String repo) {
        IssueDto dto = new IssueDto();
        dto.setSource("web");
        dto.setExternalId(externalId);
        dto.setTitle("t");
        dto.setDescription("d");
        dto.setMaxAgents(1);
        dto.setRepo(repo);
        return dto;
    }

    @Test
    void allowsRepoMatchingGlob() {
        assertDoesNotThrow(() ->
                taskService.submit(dto("ALLOW-1", "https://github.com/acme/site.git")));
        assertDoesNotThrow(() ->
                taskService.submit(dto("ALLOW-2", "git@github.com:acme/tools.git")));
    }

    @Test
    void rejectsRepoOutsideAllowlist() {
        assertThrows(IllegalArgumentException.class, () ->
                taskService.submit(dto("DENY-1", "https://github.com/evil/exfil.git")));
        // A prefix-lookalike host must not pass the glob.
        assertThrows(IllegalArgumentException.class, () ->
                taskService.submit(dto("DENY-2", "https://github.com.evil.example/acme/site.git")));
    }

    @Test
    void shortRepoNamesAreNotBlockedHere() {
        // Not URL-like: resolved server-side from config, so the allowlist check
        // intentionally lets it through.
        assertDoesNotThrow(() -> taskService.submit(dto("SHORT-1", "acme/site")));
    }
}
