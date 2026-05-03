package com.lza.aifactory.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.lza.aifactory.dto.IssueDto;
import com.lza.aifactory.dto.TaskRecord;
import com.lza.aifactory.service.TaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Map;

@RestController
@RequestMapping("/webhook")
public class TelegramWebhookController {
    private static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    private final TaskService taskService;
    private final String telegramSecret;

    public TelegramWebhookController(TaskService taskService,
                                     @Value("${ai-factory.telegram-secret:}") String telegramSecret) {
        this.taskService = taskService;
        this.telegramSecret = telegramSecret;
    }

    @PostMapping("/telegram")
    public ResponseEntity<?> telegram(
            @RequestHeader(value = SECRET_HEADER, required = false) String secretHeader,
            @RequestBody JsonNode payload) throws Exception {

        if (telegramSecret != null && !telegramSecret.isBlank()
                && !telegramSecret.equals(secretHeader)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_telegram_secret"));
        }

        String text = payload.path("message").path("text").asText("");
        Long chatId = payload.path("message").path("chat").path("id").asLong();
        IssueDto dto = parseTelegramIssue(text, chatId);
        TaskRecord record = taskService.submit(dto);
        return ResponseEntity.ok(record);
    }

    private IssueDto parseTelegramIssue(String text, Long chatId) {
        IssueDto dto = new IssueDto();
        dto.setSource("telegram");
        dto.setExternalId(chatId == null || chatId == 0L ? null : "telegram-" + chatId + "-" + System.currentTimeMillis());
        dto.setPriority("P2");
        dto.setTargetBranch("main");
        dto.setMaxAgents(3);
        dto.setLabels(Arrays.asList("telegram", "ai-factory"));

        String title = "Telegram AI Task";
        StringBuilder desc = new StringBuilder();
        boolean inDesc = false;
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.startsWith("title:")) {
                title = line.substring("title:".length()).trim();
            } else if (line.startsWith("priority:")) {
                dto.setPriority(line.substring("priority:".length()).trim());
            } else if (line.startsWith("repo:")) {
                dto.setRepo(line.substring("repo:".length()).trim());
            } else if (line.startsWith("branch:")) {
                dto.setTargetBranch(line.substring("branch:".length()).trim());
            } else if (line.startsWith("desc:")) {
                inDesc = true;
                desc.append(line.substring("desc:".length()).trim()).append('\n');
            } else if (inDesc) {
                desc.append(raw).append('\n');
            }
        }
        dto.setTitle(title);
        dto.setDescription(desc.length() == 0 ? text : desc.toString());
        return dto;
    }
}
