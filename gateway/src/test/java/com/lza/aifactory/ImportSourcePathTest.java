package com.lza.aifactory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Local-folder import path validation when it IS enabled: a path inside the
 * configured root is accepted; anything outside is rejected.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ai-factory.work-dir=${java.io.tmpdir}/ai-factory-import-test",
        "ai-factory.pipeline-script=${user.dir}/src/test/resources/noop-pipeline.sh",
        "security.importRootDir=${java.io.tmpdir}/ai-factory-import-root"
})
class ImportSourcePathTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void acceptsFolderInsideRootRejectsOutside() throws Exception {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "ai-factory-import-root");
        Path inside = root.resolve("my-project");
        Files.createDirectories(inside);
        Files.writeString(inside.resolve("index.html"), "<html>mine</html>");

        // Inside the configured root -> accepted.
        mvc.perform(post("/gateway/issue").contentType("application/json").content(
                        "{\"source\":\"web\",\"mode\":\"import\",\"sourcePath\":\"" + inside + "\","
                                + "\"title\":\"t\",\"description\":\"d\",\"maxAgents\":1}"))
                .andExpect(status().isOk());

        // Outside the root -> rejected.
        mvc.perform(post("/gateway/issue").contentType("application/json").content(
                        "{\"source\":\"web\",\"mode\":\"import\",\"sourcePath\":\"/etc\","
                                + "\"title\":\"t\",\"description\":\"d\",\"maxAgents\":1}"))
                .andExpect(status().isBadRequest());
    }
}
