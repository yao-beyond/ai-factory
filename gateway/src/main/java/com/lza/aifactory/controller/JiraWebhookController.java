package com.lza.aifactory.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.lza.aifactory.dto.IssueDto;
import com.lza.aifactory.dto.TaskRecord;
import com.lza.aifactory.service.TaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/webhook")
public class JiraWebhookController {
    private final TaskService taskService;

    public JiraWebhookController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/jira")
    public ResponseEntity<TaskRecord> jira(@RequestBody JsonNode payload) throws Exception {
        JsonNode issue = payload.path("issue");
        JsonNode fields = issue.path("fields");

        IssueDto dto = new IssueDto();
        dto.setSource("jira");
        dto.setExternalId(issue.path("key").asText());
        dto.setTitle(fields.path("summary").asText("Jira AI Task"));
        dto.setDescription(extractDescription(fields.path("description")));
        dto.setPriority(fields.path("priority").path("name").asText("P2"));

        List<String> labels = new ArrayList<>();
        labels.add("jira");
        labels.add("ai-factory");
        for (JsonNode label : fields.path("labels")) {
            labels.add(label.asText());
        }
        dto.setLabels(labels);

        dto.setTargetBranch("main");
        dto.setMaxAgents(3);
        return ResponseEntity.ok(taskService.submit(dto));
    }

    /**
     * Jira description can be a plain string (legacy) or an Atlassian Document Format (ADF) object.
     * For ADF, walk the doc and concatenate text nodes; otherwise return the textual value.
     */
    private String extractDescription(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        if (node.isTextual()) return node.asText();
        StringBuilder sb = new StringBuilder();
        collectAdfText(node, sb);
        String text = sb.toString().trim();
        return text.isEmpty() ? node.toString() : text;
    }

    private void collectAdfText(JsonNode node, StringBuilder sb) {
        if (node.has("text") && node.get("text").isTextual()) {
            sb.append(node.get("text").asText()).append(' ');
        }
        JsonNode content = node.path("content");
        if (content.isArray()) {
            for (JsonNode child : content) collectAdfText(child, sb);
            sb.append('\n');
        }
    }
}
