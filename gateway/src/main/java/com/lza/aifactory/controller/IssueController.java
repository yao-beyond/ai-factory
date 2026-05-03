package com.lza.aifactory.controller;

import com.lza.aifactory.dto.IssueDto;
import com.lza.aifactory.dto.TaskRecord;
import com.lza.aifactory.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
}
