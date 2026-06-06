package com.lza.aifactory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ai-factory.work-dir=${java.io.tmpdir}/ai-factory-webui-test",
        "ai-factory.pipeline-script=${user.dir}/src/test/resources/noop-pipeline.sh"
})
class WebUiTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void homeServesSubmissionForm() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("提出一個需求")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/mascot.jpg")));
    }

    @Test
    void mascotImageIsServedAsStaticResource() throws Exception {
        mvc.perform(get("/mascot.jpg"))
                .andExpect(status().isOk());
    }

    @Test
    void tasksListPageRenders() throws Exception {
        mvc.perform(get("/gateway/ui"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("所有任務")));
    }

    @Test
    void webFormSubmissionFlowsThroughIssueEndpoint() throws Exception {
        String body = """
                {"source":"web","title":"web demo","description":"please do it","maxAgents":1}
                """;
        mvc.perform(post("/gateway/issue").contentType("application/json").content(body))
                .andExpect(status().isOk());
    }

    @Test
    void perTaskUiPageReturns404ForUnknown() throws Exception {
        mvc.perform(get("/gateway/ui/__none__"))
                .andExpect(status().isNotFound());
    }

    @Test
    void completedPageShowsPlainLanguageResultAndPrLink() throws Exception {
        // Submit a task with a known id, then simulate the pipeline finishing with
        // a PR link, and assert the page speaks human (Gemini UAT must-fixes).
        String body = """
                {"source":"web","externalId":"UAT-DONE","title":"改一個顏色","description":"請把按鈕改成深藍色","maxAgents":1}
                """;
        mvc.perform(post("/gateway/issue").contentType("application/json").content(body))
                .andExpect(status().isOk());

        java.nio.file.Path taskDir = java.nio.file.Path.of(
                System.getProperty("java.io.tmpdir"), "ai-factory-webui-test", "UAT-DONE");
        java.nio.file.Files.createDirectories(taskDir);
        java.nio.file.Files.writeString(taskDir.resolve("status.txt"),
                "STATUS=COMPLETED\nMESSAGE=done\nUPDATED_AT=now\nPR_URL=https://github.com/acme/app/pull/42\n");
        java.nio.file.Files.writeString(taskDir.resolve("summary.md"),
                "# 變更摘要\n- 把按鈕顏色改成深藍色\n- 修正標題錯字\n");

        mvc.perform(get("/gateway/ui/UAT-DONE"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AI 已經完成你要求的工作了")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("查看 AI 完成的成果草案")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("https://github.com/acme/app/pull/42")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AI 做了這些變更")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("把按鈕顏色改成深藍色")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AI 不會自己改動正式版本")))
                // No ETA on a finished task.
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("預計還要"))));
    }

    @Test
    void inProgressPageShowsEstimatedTimeRemaining() throws Exception {
        String body = """
                {"source":"web","externalId":"UAT-ETA","title":"一個進行中的需求","description":"請做這件事","maxAgents":1}
                """;
        mvc.perform(post("/gateway/issue").contentType("application/json").content(body))
                .andExpect(status().isOk());

        // The /bin/true pipeline leaves status at SUBMITTED (in progress).
        mvc.perform(get("/gateway/ui/UAT-ETA"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("預計還要")));
    }
}
