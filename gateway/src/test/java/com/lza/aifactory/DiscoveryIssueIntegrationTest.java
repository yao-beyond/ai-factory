package com.lza.aifactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The structured discovery capability boundary must reach the real task pipeline,
 * not be dropped at the gateway: a discovery-originated /gateway/issue submit
 * persists the SERVER-derived boundary into issue.json, and a forged card id is
 * cleared rather than trusted. See docs/design/discovery-stage.md.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ai-factory.work-dir=${java.io.tmpdir}/ai-factory-discovery-itest",
        "ai-factory.pipeline-script=${user.dir}/src/test/resources/noop-pipeline.sh"
})
class DiscoveryIssueIntegrationTest {

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper om = new ObjectMapper();

    private Path issueJson(String taskId) {
        return Path.of(System.getProperty("java.io.tmpdir"), "ai-factory-discovery-itest", taskId, "issue.json");
    }

    @Test
    @SuppressWarnings("unchecked")
    void validCardIdPersistsServerDerivedBoundary() throws Exception {
        String body = """
                {"source":"web","mode":"new","externalId":"DISCO-1",
                 "title":"美霞的洗車預約","description":"建立一個靜態頁面…",
                 "maxAgents":1,"projectType":"web","discoveryCardId":"appointment_request"}
                """;
        mvc.perform(post("/gateway/issue").contentType("application/json").content(body))
                .andExpect(status().isOk());

        Map<String, Object> issue = om.readValue(Files.readString(issueJson("DISCO-1")), Map.class);
        Map<String, Object> boundary = (Map<String, Object>) issue.get("capabilityBoundary");
        assertNotNull(boundary, "discovery boundary persisted into issue.json");
        assertEquals("appointment_request", boundary.get("cardId"));
        assertEquals(Boolean.FALSE, boundary.get("ownerReceivesData"));
        assertTrue(boundary.containsKey("handoff"), "handoff hard constraints carried downstream");
    }

    @Test
    @SuppressWarnings("unchecked")
    void forgedCardIdIsClearedNotTrusted() throws Exception {
        // A forged boundary AND a forged card id are both sent; the server must ignore
        // the boundary and clear the unknown card id.
        String body = """
                {"source":"web","mode":"new","externalId":"DISCO-2",
                 "title":"t","description":"d","maxAgents":1,
                 "discoveryCardId":"payments_checkout_app",
                 "capabilityBoundary":{"ownerReceivesData":true,"payment":true}}
                """;
        mvc.perform(post("/gateway/issue").contentType("application/json").content(body))
                .andExpect(status().isOk());

        Map<String, Object> issue = om.readValue(Files.readString(issueJson("DISCO-2")), Map.class);
        assertNull(issue.get("capabilityBoundary"), "forged boundary must not be trusted/persisted");
        assertNull(issue.get("discoveryCardId"), "forged card id is cleared");
    }
}
