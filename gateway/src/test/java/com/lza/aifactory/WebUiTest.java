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
                .andExpect(content().string(org.hamcrest.Matchers.containsString("做全新的")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("改我的檔案")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("不需要 git")));
    }

    private static byte[] zipOf(java.util.Map<String, String> entries) throws Exception {
        var bos = new java.io.ByteArrayOutputStream();
        try (var zos = new java.util.zip.ZipOutputStream(bos)) {
            for (var e : entries.entrySet()) {
                zos.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    @Test
    void importZipCreatesTaskAndExtractsFiles() throws Exception {
        byte[] zip = zipOf(java.util.Map.of(
                "index.html", "<html>my existing site</html>",
                "css/app.css", "body{}"));
        var file = new org.springframework.mock.web.MockMultipartFile("file", "site.zip", "application/zip", zip);
        String json = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/gateway/import").file(file)
                        .param("title", "改我的網站").param("description", "把標題改大").param("maxAgents", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String taskId = com.jayway.jsonpath.JsonPath.read(json, "$.taskId");
        // The zip was extracted into the task's workspace/repo.
        java.nio.file.Path repo = workDir().resolve(taskId).resolve("workspace").resolve("repo");
        org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.exists(repo.resolve("index.html")));
        org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.exists(repo.resolve("css/app.css")));
        // issue.json records mode=import.
        String issue = java.nio.file.Files.readString(workDir().resolve(taskId).resolve("issue.json"));
        org.junit.jupiter.api.Assertions.assertTrue(issue.contains("\"mode\" : \"import\""));
    }

    @Test
    void importRejectsZipSlip() throws Exception {
        byte[] evil = zipOf(java.util.Map.of("../../../../tmp/evil.txt", "pwned"));
        var file = new org.springframework.mock.web.MockMultipartFile("file", "evil.zip", "application/zip", evil);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/gateway/import").file(file)
                        .param("title", "t").param("description", "d"))
                .andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertFalse(
                java.nio.file.Files.exists(java.nio.file.Path.of("/tmp/evil.txt")));
    }

    @Test
    void importFolderRejectedByDefault() throws Exception {
        // Local-folder import (sourcePath) is OFF unless importRootDir is configured,
        // so an API caller can't copy arbitrary host directories into a result.
        String body = """
                {"source":"web","mode":"import","sourcePath":"/etc","title":"t","description":"d","maxAgents":1}
                """;
        mvc.perform(post("/gateway/issue").contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void importRejectsMissingFile() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/gateway/import")
                        .param("title", "t").param("description", "d"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void importRejectsNonZip() throws Exception {
        var file = new org.springframework.mock.web.MockMultipartFile("file", "notes.txt", "text/plain", "hi".getBytes());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/gateway/import").file(file)
                        .param("title", "t").param("description", "d"))
                .andExpect(status().isBadRequest());
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
    void downloadDeniedForExistingRepoEvenWithStaleZip() throws Exception {
        // An existing-repo task with a stale result.zip must not be downloadable.
        java.nio.file.Path dir = workDir().resolve("UAT-DL-EXISTING");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("issue.json"),
                "{\"source\":\"web\",\"title\":\"t\",\"description\":\"d\",\"mode\":\"existing\"}");
        java.nio.file.Files.write(dir.resolve("result.zip"), new byte[]{0x50, 0x4b, 0x05, 0x06});
        mvc.perform(get("/gateway/result/UAT-DL-EXISTING")).andExpect(status().isNotFound());
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

    // Mark a task as a new-project (local) result so preview is allowed: the gate
    // is the authoritative mode in issue.json, not a sibling artifact.
    private void markLocalResult(String id) throws Exception {
        java.nio.file.Path dir = workDir().resolve(id);
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("issue.json"),
                "{\"source\":\"web\",\"title\":\"t\",\"description\":\"d\",\"mode\":\"new\"}");
    }

    @Test
    void previewServesGeneratedIndexHtml() throws Exception {
        java.nio.file.Path repo = workDir().resolve("UAT-PREVIEW").resolve("workspace").resolve("repo");
        java.nio.file.Files.createDirectories(repo);
        java.nio.file.Files.writeString(repo.resolve("index.html"),
                "<!doctype html><html><body><h1>我的待辦清單</h1></body></html>");
        markLocalResult("UAT-PREVIEW");
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
        markLocalResult("UAT-TRAVERSAL");
        mvc.perform(get("/gateway/preview/UAT-TRAVERSAL/../../../../etc/hosts"))
                .andExpect(status().isNotFound());
    }

    @Test
    void previewBlocksSymlinkEscape() throws Exception {
        java.nio.file.Path repo = workDir().resolve("UAT-SYMLINK").resolve("workspace").resolve("repo");
        java.nio.file.Files.createDirectories(repo);
        java.nio.file.Files.writeString(repo.resolve("index.html"), "<html>ok</html>");
        markLocalResult("UAT-SYMLINK");
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
    void previewDeniedForExistingRepoEvenWithStaleZip() throws Exception {
        // Existing-repo tasks also clone into workspace/repo. Preview must NOT expose
        // that private cloned source — even if a stale result.zip artifact is present,
        // because the gate is the authoritative mode in issue.json, not the zip.
        java.nio.file.Path dir = workDir().resolve("UAT-EXISTING");
        java.nio.file.Path repo = dir.resolve("workspace").resolve("repo");
        java.nio.file.Files.createDirectories(repo);
        java.nio.file.Files.writeString(repo.resolve("index.html"), "<html>private repo</html>");
        java.nio.file.Files.writeString(repo.resolve("secrets.env"), "API_KEY=super-secret");
        java.nio.file.Files.writeString(dir.resolve("issue.json"),
                "{\"source\":\"web\",\"title\":\"t\",\"description\":\"d\",\"mode\":\"existing\"}");
        // A stale/forged result.zip must NOT bypass the mode gate.
        java.nio.file.Files.write(dir.resolve("result.zip"), new byte[]{0x50, 0x4b, 0x05, 0x06});
        mvc.perform(get("/gateway/preview/UAT-EXISTING/")).andExpect(status().isNotFound());
        mvc.perform(get("/gateway/preview/UAT-EXISTING/secrets.env")).andExpect(status().isNotFound());
    }

    @Test
    void completedNewProjectWithoutZipShowsNoBrokenDownloadLink() throws Exception {
        String body = """
                {"source":"web","mode":"new","externalId":"UAT-NOZIP","title":"全新但無檔","description":"d","maxAgents":1}
                """;
        mvc.perform(post("/gateway/issue").contentType("application/json").content(body))
                .andExpect(status().isOk());
        java.nio.file.Path dir = workDir().resolve("UAT-NOZIP");
        java.nio.file.Files.createDirectories(dir);
        // COMPLETED, mode=new, but NO result.zip produced.
        java.nio.file.Files.writeString(dir.resolve("status.txt"), "STATUS=COMPLETED\nMESSAGE=done\nUPDATED_AT=now\n");
        mvc.perform(get("/gateway/ui/UAT-NOZIP"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("你的全新專案做好了")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("成果整理中")))
                // No broken download link.
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("href=\"/gateway/result/UAT-NOZIP\""))));
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

    private void putRunningTaskWithLog(String id, String log) throws Exception {
        String body = "{\"source\":\"web\",\"externalId\":\"" + id
                + "\",\"title\":\"進行中需求\",\"description\":\"請做這件事\",\"maxAgents\":1}";
        mvc.perform(post("/gateway/issue").contentType("application/json").content(body))
                .andExpect(status().isOk());
        java.nio.file.Path dir = workDir().resolve(id);
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("status.txt"),
                "STATUS=DEVELOPING\nMESSAGE=spawning 3 dev agents\nUPDATED_AT=now\n");
        java.nio.file.Files.writeString(dir.resolve("run.log"), log);
    }

    @Test
    void inProgressPageShowsLiveActivityFeed() throws Exception {
        putRunningTaskWithLog("UAT-FEED", "AI 正在開發…\n");
        mvc.perform(get("/gateway/ui/UAT-FEED"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AI 即時執行紀錄")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"feed\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("var id = \"UAT-FEED\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("loadActivity")))
                // Running pages drive updates with JS, not a full-page meta refresh.
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("http-equiv=\"refresh\""))));
    }

    @Test
    void completedPageHasNoLiveActivityFeed() throws Exception {
        String body = """
                {"source":"web","externalId":"UAT-NOFEED","title":"t","description":"d","maxAgents":1}
                """;
        mvc.perform(post("/gateway/issue").contentType("application/json").content(body))
                .andExpect(status().isOk());
        java.nio.file.Path dir = workDir().resolve("UAT-NOFEED");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("status.txt"), "STATUS=COMPLETED\nMESSAGE=done\nUPDATED_AT=now\n");
        java.nio.file.Files.writeString(dir.resolve("run.log"), "some log line\n");
        mvc.perform(get("/gateway/ui/UAT-NOFEED"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("AI 即時執行紀錄"))));
    }

    @Test
    void activityEndpointStripsAnsiButKeepsGitBrackets() throws Exception {
        // A real ANSI colour code wraps the first line; the second is git output
        // whose literal [brackets] must survive.
        String log = "\u001B[32mGREENLINE\u001B[0m\n[ai/UAT-ACT/dev-1 abc123] feat: implement candidate 1\n";
        putRunningTaskWithLog("UAT-ACT", log);
        String json = mvc.perform(get("/gateway/activity/UAT-ACT"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // Visible text survives; ANSI fragments are gone.
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("GREENLINE"));
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("[32m"));
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("[0m"));
        org.junit.jupiter.api.Assertions.assertFalse(json.toLowerCase().contains("u001b"));
        // Literal git bracket output is preserved (the ESC-anchored regex must not eat it).
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("[ai/UAT-ACT/dev-1 abc123]"));
    }

    @Test
    void activityEndpointReturnsEmptyLinesWhenNoLogYet() throws Exception {
        String body = """
                {"source":"web","externalId":"UAT-NOLOG","title":"t","description":"d","maxAgents":1}
                """;
        mvc.perform(post("/gateway/issue").contentType("application/json").content(body))
                .andExpect(status().isOk());
        mvc.perform(get("/gateway/activity/UAT-NOLOG"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"lines\":[]")));
    }

    @Test
    void activityFeedRedactsSecrets() throws Exception {
        // run.log can echo credentials a pipeline command printed. The feed must
        // surface a sanitised view, never the raw secret bytes.
        String log = String.join("\n",
                "cloning https://oscar:ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345@github.com/acme/app.git",
                "export GITHUB_TOKEN=ghp_ZYXWVUTSRQPONMLKJIHGFEDCBA987654",
                "Authorization: Bearer sk-abcdefghijklmnopqrstuvwxyz0123456789",
                "password=hunter2supersecret",
                "writing to /Users/oscar/secret/path/file.txt",
                "");
        putRunningTaskWithLog("UAT-REDACT", log);
        String json = mvc.perform(get("/gateway/activity/UAT-REDACT"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // No secret value survives.
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"));
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("ghp_ZYXWVUTSRQPONMLKJIHGFEDCBA987654"));
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("sk-abcdefghijklmnopqrstuvwxyz0123456789"));
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("hunter2supersecret"));
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("oscar:"));      // URL userinfo gone
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("/Users/oscar")); // OS account masked
        // Non-secret context is preserved so the feed is still useful.
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("github.com/acme/app.git"));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("<redacted>"));
    }

    @Test
    void activityFeedRedactsAwsSecretKeys() throws Exception {
        String log = String.join("\n",
                "AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                "aws_secret_access_key = abcdEFGH1234ijklMNOP5678qrstUVWX90+/abcd",
                "AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE",
                "commit 1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9012 landed",
                "");
        putRunningTaskWithLog("UAT-AWS", log);
        String json = mvc.perform(get("/gateway/activity/UAT-AWS"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // Secret access keys (env-var form and AWS CLI config form) are masked.
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"));
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("abcdEFGH1234ijklMNOP5678qrstUVWX90+/abcd"));
        // Access key id is masked too.
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("AKIAIOSFODNN7EXAMPLE"));
        // A 40-hex git SHA is NOT a secret and must stay visible (no + or /).
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9012"));
        // The label stays so the line is still informative.
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("AWS_SECRET_ACCESS_KEY=<redacted>"));
    }

    @Test
    void activityFeedMasksAuthKeyButNotLookalikes() throws Exception {
        String log = String.join("\n",
                "auth=topSecretValue123",
                "auth: anotherSecretValue",
                "author=Bob Smith",     // 'author' is not the whole word 'auth'
                "oauth_state=xyz123",   // 'oauth' is not the whole word 'auth'
                "");
        putRunningTaskWithLog("UAT-AUTH", log);
        String json = mvc.perform(get("/gateway/activity/UAT-AUTH"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // The auth key's value is masked (regression guard).
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("topSecretValue123"));
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("anotherSecretValue"));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("auth=<redacted>"));
        // Look-alike identifiers keep their (non-secret) values.
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("Bob Smith"));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("xyz123"));
    }

    @Test
    void confirmPageRendersOptionCardsAndPreselectsRecommended() throws Exception {
        putAwaitingTask("UAT-OPTS", true);
        // The recommended option is the SECOND one — the hidden field must default
        // to it, not to options[0] (guards the preselect bug).
        String optionsJson = """
            [
              {"id":"fast","title":"輕巧耐用型","description":"產出超快","stack":"HTML + Vanilla JS",
               "ratings":{"speed":5,"smoothness":3,"scalability":2},"recommended":false},
              {"id":"modern","title":"華麗專業型","description":"畫面很順","stack":"React + Vite",
               "ratings":{"speed":3,"smoothness":5,"scalability":5},"recommended":true}
            ]
            """;
        java.nio.file.Files.writeString(workDir().resolve("UAT-OPTS").resolve("options.json"), optionsJson);
        String html = mvc.perform(get("/gateway/ui/UAT-OPTS"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("粉圓推薦"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("輕巧耐用型"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("華麗專業型"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("★"));        // stars rendered
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("開發速度"));
        // Hidden default = the recommended id ("modern"), not the first ("fast").
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("id=\"selectedOption\" value=\"modern\""));
    }

    @Test
    void confirmWithOptionWritesOptionMarker() throws Exception {
        putAwaitingTask("UAT-OPT-PICK", false);
        mvc.perform(post("/gateway/confirm/UAT-OPT-PICK").param("option", "modern"))
                .andExpect(status().isOk());
        java.nio.file.Path optFile = workDir().resolve("UAT-OPT-PICK").resolve("confirm.option");
        org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.exists(optFile));
        org.junit.jupiter.api.Assertions.assertTrue(
                java.nio.file.Files.readString(optFile).contains("modern"));
    }

    @Test
    void optionCardIdIsNotInterpolatedIntoJavaScript() throws Exception {
        putAwaitingTask("UAT-XSS", true);
        // A malicious AI-generated id with a quote must not break out into JS.
        String optionsJson = """
            [
              {"id":"x'),alert(1)//","title":"惡意","description":"d","recommended":true}
            ]
            """;
        java.nio.file.Files.writeString(workDir().resolve("UAT-XSS").resolve("options.json"), optionsJson);
        String html = mvc.perform(get("/gateway/ui/UAT-XSS"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // The id rides a data attribute and is read via the DOM, never spliced
        // into a JS string literal — so the vulnerable call form must be absent.
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("selectOption(this, '"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("onclick=\"selectOption(this)\""));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("data-opt-id="));
    }

    @Test
    void malformedOptionsDoNotCrashConfirmPage() throws Exception {
        putAwaitingTask("UAT-BADOPT", true);
        // Valid JSON, wrong shapes: a bare string, a number, and an object whose
        // fields are non-strings. None of these may throw / 500 the page.
        String optionsJson = """
            [
              "just a string",
              42,
              {"id":123,"title":99,"description":["a"],"ratings":"oops","recommended":"true"}
            ]
            """;
        java.nio.file.Files.writeString(workDir().resolve("UAT-BADOPT").resolve("options.json"), optionsJson);
        mvc.perform(get("/gateway/ui/UAT-BADOPT"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("請先確認開工方向")));
    }

    @Test
    void nonArrayOptionsAreIgnored() throws Exception {
        putAwaitingTask("UAT-OBJOPT", true);
        java.nio.file.Files.writeString(workDir().resolve("UAT-OBJOPT").resolve("options.json"),
                "{\"not\":\"an array\"}");
        mvc.perform(get("/gateway/ui/UAT-OBJOPT"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("請先確認開工方向")));
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
