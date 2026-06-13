package com.lza.aifactory.governance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression guard: with NO {@code AIF_INTERNAL_SECRET} configured (the default
 * local posture), the human-approval endpoints must still fail CLOSED. The
 * gateway auto-generates an ephemeral operator secret, so an unauthenticated
 * caller — e.g. untrusted workspace code that inherited {@code AIF_GATEWAY_URL}
 * and tries to self-approve its own delivery — is rejected with 403 instead of
 * silently clearing the gate. This locks in the fix for the "auth fails open
 * when the secret is unset" finding.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ai-factory.work-dir=${java.io.tmpdir}/ai-factory-gov-failclosed-test",
        "ai-factory.pipeline-script=${user.dir}/src/test/resources/noop-pipeline.sh"
        // deliberately NO AIF_INTERNAL_SECRET
})
class GovernanceFailClosedDefaultTest {

    @Autowired
    private MockMvc mvc;

    private void submit(String externalId) throws Exception {
        mvc.perform(post("/gateway/issue").contentType("application/json").content(
                "{\"source\":\"web\",\"mode\":\"new\",\"externalId\":\"" + externalId + "\",\"title\":\"t\","
                        + "\"description\":\"d\",\"maxAgents\":1,\"governanceProfileId\":\"compliance-patcher\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void approveWithoutSecretIsForbiddenByDefault() throws Exception {
        submit("FC-APR");
        // No X-AIF-Internal header: workspace code cannot read the ephemeral secret
        // that lives only in the gateway's console/memory, so it is rejected.
        mvc.perform(post("/gateway/governance/FC-APR/approve"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectWithoutSecretIsForbiddenByDefault() throws Exception {
        submit("FC-REJ");
        mvc.perform(post("/gateway/governance/FC-REJ/reject"))
                .andExpect(status().isForbidden());
    }

    @Test
    void overrideWithoutSecretIsForbiddenByDefault() throws Exception {
        submit("FC-OVR");
        mvc.perform(post("/gateway/governance/FC-OVR/override")
                        .param("rationale", "x").param("ticket", "INC-1").param("expiry", "2026-06-14T00:00:00Z"))
                .andExpect(status().isForbidden());
    }

    @Test
    void promoteCheckWithoutSecretOrTokenIsForbiddenByDefault() throws Exception {
        submit("FC-PC");
        mvc.perform(post("/gateway/governance/FC-PC/promote-check").contentType("application/json")
                        .content("{\"testStatus\":\"pass\",\"diffRef\":\"ai/x/final\",\"mode\":\"local\"}"))
                .andExpect(status().isForbidden());
    }
}
