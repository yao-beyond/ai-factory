package com.lza.aifactory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ai-factory.work-dir=${java.io.tmpdir}/ai-factory-test-work",
        "ai-factory.pipeline-script=/bin/true",
        "ai-factory.telegram-secret=mysecret"
})
class GatewaySmokeTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void healthEndpointReturnsUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void submitsValidIssueAndReadsBackStatus() throws Exception {
        String body = """
                {
                  "source":"jira",
                  "externalId":"TEST-1",
                  "title":"hello",
                  "description":"world",
                  "repo":"https://example.com/repo.git",
                  "targetBranch":"main",
                  "maxAgents":1
                }
                """;

        mvc.perform(post("/gateway/issue")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("TEST-1"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        mvc.perform(get("/gateway/status/TEST-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("TEST-1"));
    }

    @Test
    void rejectsEmptyIssueWithValidationError() throws Exception {
        mvc.perform(post("/gateway/issue")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));
    }

    @Test
    void unknownTaskReturns404() throws Exception {
        mvc.perform(get("/gateway/status/__nope__"))
                .andExpect(status().isNotFound());
    }

    @Test
    void telegramRejectsWrongSecret() throws Exception {
        mvc.perform(post("/webhook/telegram")
                        .header("X-Telegram-Bot-Api-Secret-Token", "wrong")
                        .contentType("application/json")
                        .content("{\"message\":{\"chat\":{\"id\":1},\"text\":\"hi\"}}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void telegramAcceptsCorrectSecret() throws Exception {
        mvc.perform(post("/webhook/telegram")
                        .header("X-Telegram-Bot-Api-Secret-Token", "mysecret")
                        .contentType("application/json")
                        .content("{\"message\":{\"chat\":{\"id\":99},\"text\":\"title: t\\ndesc: d\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("telegram"));
    }

    @Test
    void jiraWebhookFlattensAdfDescription() throws Exception {
        String adf = """
                {
                  "issue":{
                    "key":"JIRA-ADF",
                    "fields":{
                      "summary":"adf",
                      "description":{
                        "type":"doc",
                        "content":[{"type":"paragraph","content":[
                          {"type":"text","text":"hello "},
                          {"type":"text","text":"world"}
                        ]}]
                      },
                      "priority":{"name":"P2"},
                      "labels":["x"]
                    }
                  }
                }
                """;

        mvc.perform(post("/webhook/jira")
                        .contentType("application/json")
                        .content(adf))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("JIRA-ADF"))
                .andExpect(jsonPath("$.source").value("jira"));
    }
}
