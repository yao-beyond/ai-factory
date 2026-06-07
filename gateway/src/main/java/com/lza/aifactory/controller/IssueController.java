package com.lza.aifactory.controller;

import com.lza.aifactory.dto.IssueDto;
import com.lza.aifactory.dto.TaskRecord;
import com.lza.aifactory.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/gateway")
public class IssueController {
    private final TaskService taskService;

    public IssueController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/issue")
    public ResponseEntity<TaskRecord> issue(@Valid @RequestBody IssueDto dto) throws Exception {
        return ResponseEntity.ok(taskService.submit(dto));
    }

    /**
     * Import an existing project from an uploaded zip and improve it locally —
     * no git account or token. Multipart so non-technical users can just pick a
     * file in the browser.
     */
    @PostMapping(value = "/import", consumes = "multipart/form-data")
    public ResponseEntity<?> importZip(@RequestParam(value = "file", required = false) MultipartFile file,
                                       @RequestParam(value = "title", required = false) String title,
                                       @RequestParam(value = "description", required = false) String description,
                                       @RequestParam(value = "maxAgents", required = false) Integer maxAgents,
                                       @RequestParam(value = "projectType", required = false) String projectType)
            throws Exception {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "no_file", "message", "請上傳一個 zip 檔。"));
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!name.endsWith(".zip")) {
            return ResponseEntity.badRequest().body(Map.of("error", "not_zip", "message", "請上傳 .zip 檔。"));
        }
        if (title == null || title.isBlank() || description == null || description.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing_fields", "message", "標題與描述為必填。"));
        }

        IssueDto dto = new IssueDto();
        dto.setSource("web");
        dto.setMode("import");
        dto.setTitle(title);
        dto.setDescription(description);
        dto.setMaxAgents(maxAgents == null ? 3 : Math.max(1, Math.min(10, maxAgents)));
        dto.setProjectType(projectType == null ? "recommend" : projectType);
        dto.setLabels(List.of("import"));

        try (var in = file.getInputStream()) {
            return ResponseEntity.ok(taskService.submitImportZip(dto, in));
        } catch (java.io.IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "bad_archive", "message", e.getMessage()));
        }
    }
}
