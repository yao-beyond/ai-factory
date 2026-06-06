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
    void homeFormOffersNewProjectChoice() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("做一個全新的東西")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("不需要 git")));
    }

    @Test
    void resultDownloadServesZipWhenPresent() throws Exception {
        String body = """
                {"source":"web","mode":"new","externalId":"UAT-ZIP","title":"全新小工具","description":"做個東西","maxAgents":1}
                """;
        mvc.perform(post("/gateway/issue").contentType("application/json").content(body))
                .andExpect(status().isOk());
        java.nio.file.Path dir = workDir().resolve("UAT-ZIP");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.write(dir.resolve("result.zip"), new byte[]{0x50, 0x4b, 0x05, 0x06});

        mvc.perform(get("/gateway/result/UAT-ZIP"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Content-Disposition",
                                org.hamcrest.Matchers.containsString("ai-factory-UAT-ZIP.zip")));
    }

    @Test
    void resultDownloadUnknownReturns404() throws Exception {
        mvc.perform(get("/gateway/result/__none__")).andExpect(status().isNotFound());
    }

    @Test
    void localResultPageShowsDownloadNotPullRequest() throws Exception {
        String body = """
                {"source":"web","mode":"new","externalId":"UAT-LOCAL","title":"全新需求","description":"做個東西","maxAgents":1}
                """;
        mvc.perform(post("/gateway/issue").contentType("application/json").content(body))
                .andExpect(status().isOk());
        java.nio.file.Path dir = workDir().resolve("UAT-LOCAL");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("status.txt"),
                "STATUS=COMPLETED\nMESSAGE=done\nUPDATED_AT=now\nRESULT_ZIP=" + dir.resolve("result.zip") + "\n");
        java.nio.file.Files.write(dir.resolve("result.zip"), new byte[]{0x50, 0x4b, 0x05, 0x06});
        java.nio.file.Files.writeString(dir.resolve("summary.md"), "# 成果\n- 做了一個小工具\n");

        mvc.perform(get("/gateway/ui/UAT-LOCAL"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("你的全新專案做好了")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("下載你的專案")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("請工程師"))));
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
    void previewServesGeneratedIndexHtml() throws Exception {
        java.nio.file.Path repo = workDir().resolve("UAT-PREVIEW").resolve("workspace").resolve("repo");
        java.nio.file.Files.createDirectories(repo);
        java.nio.file.Files.writeString(repo.resolve("index.html"),
                "<!doctype html><html><body><h1>我的待辦清單</h1></body></html>");
        mvc.perform(get("/gateway/preview/UAT-PREVIEW/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("我的待辦清單")));
    }

    @Test
    void previewBlocksPathTraversal() throws Exception {
        java.nio.file.Path repo = workDir().resolve("UAT-TRAVERSAL").resolve("workspace").resolve("repo");
        java.nio.file.Files.createDirectories(repo);
        java.nio.file.Files.writeString(repo.resolve("index.html"), "<html>ok</html>");
        mvc.perform(get("/gateway/preview/UAT-TRAVERSAL/../../../../etc/hosts"))
                .andExpect(status().isNotFound());
    }

    @Test
    void previewBlocksSymlinkEscape() throws Exception {
        java.nio.file.Path repo = workDir().resolve("UAT-SYMLINK").resolve("workspace").resolve("repo");
        java.nio.file.Files.createDirectories(repo);
        java.nio.file.Files.writeString(repo.resolve("index.html"), "<html>ok</html>");
        // A secret file OUTSIDE the project, and a symlink inside pointing to it.
        java.nio.file.Path secret = workDir().resolve("UAT-SYMLINK").resolve("secret.txt");
        java.nio.file.Files.writeString(secret, "TOP SECRET");
        try {
            java.nio.file.Files.createSymbolicLink(repo.resolve("escape.txt"), secret);
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks unsupported here");
        }
        mvc.perform(get("/gateway/preview/UAT-SYMLINK/escape.txt"))
                .andExpect(status().isNotFound());
    }

    @Test
    void completedWebProjectOffersPreviewButton() throws Exception {
        String body = """
                {"source":"web","mode":"new","externalId":"UAT-WEB","title":"小網站","description":"做一個小網站","maxAgents":1}
                """;
        mvc.perform(post("/gateway/issue").contentType("application/json").content(body))
                .andExpect(status().isOk());
        java.nio.file.Path dir = workDir().resolve("UAT-WEB");
        java.nio.file.Files.createDirectories(dir.resolve("workspace").resolve("repo"));
        java.nio.file.Files.writeString(dir.resolve("status.txt"), "STATUS=COMPLETED\nMESSAGE=done\nUPDATED_AT=now\n");
        java.nio.file.Files.write(dir.resolve("result.zip"), new byte[]{0x50, 0x4b, 0x05, 0x06});
        java.nio.file.Files.writeString(dir.resolve("workspace").resolve("repo").resolve("index.html"), "<html>hi</html>");
        mvc.perform(get("/gateway/ui/UAT-WEB"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("線上預覽成果")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("下載你的專案")));
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

        // The noop pipeline leaves status at SUBMITTED (in progress).
        mvc.perform(get("/gateway/ui/UAT-ETA"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("預計還要")));
    }

    private java.nio.file.Path workDir() {
        return java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "ai-factory-webui-test");
    }

    private void putAwaitingTask(String id, boolean withPlan) throws Exception {
        String body = "{\"source\":\"web\",\"externalId\":\"" + id
                + "\",\"title\":\"待確認需求\",\"description\":\"請做這件事\",\"maxAgents\":1}";
        mvc.perform(post("/gateway/issue").contentType("application/json").content(body))
                .andExpect(status().isOk());
        java.nio.file.Path dir = workDir().resolve(id);
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("status.txt"),
                "STATUS=AWAITING_CONFIRMATION\nMESSAGE=waiting\nUPDATED_AT=now\n");
        if (withPlan) {
            java.nio.file.Files.writeString(dir.resolve("plan_summary.md"),
                    "# 計畫\n- 在首頁加上聯絡我們連結\n- 預計改動 1 個檔案\n");
        }
    }

    @Test
    void awaitingConfirmationPageShowsPlanAndButtons() throws Exception {
        putAwaitingTask("UAT-AWAIT", true);
        mvc.perform(get("/gateway/ui/UAT-AWAIT"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("請先確認開工方向")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("在首頁加上聯絡我們連結")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("確認開工")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("取消")))
                // ETA must be hidden while waiting on a human.
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("預計還要"))));
    }

    @Test
    void confirmWritesApproveMarkerWhenAwaiting() throws Exception {
        putAwaitingTask("UAT-OK", false);
        mvc.perform(post("/gateway/confirm/UAT-OK")).andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertTrue(
                java.nio.file.Files.exists(workDir().resolve("UAT-OK").resolve("confirm.approve")));
    }

    @Test
    void cancelWritesCancelMarkerWhenAwaiting() throws Exception {
        putAwaitingTask("UAT-NO", false);
        mvc.perform(post("/gateway/cancel/UAT-NO")).andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertTrue(
                java.nio.file.Files.exists(workDir().resolve("UAT-NO").resolve("confirm.cancel")));
    }

    @Test
    void confirmRejectedWhenNotAwaiting() throws Exception {
        // A freshly submitted task is SUBMITTED, not AWAITING_CONFIRMATION.
        String body = """
                {"source":"web","externalId":"UAT-CONFLICT","title":"t","description":"d","maxAgents":1}
                """;
        mvc.perform(post("/gateway/issue").contentType("application/json").content(body))
                .andExpect(status().isOk());
        mvc.perform(post("/gateway/confirm/UAT-CONFLICT")).andExpect(status().isConflict());
    }

    @Test
    void confirmUnknownTaskReturns404() throws Exception {
        mvc.perform(post("/gateway/confirm/__nope__")).andExpect(status().isNotFound());
    }
}
