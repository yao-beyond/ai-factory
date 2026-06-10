package com.lza.aifactory.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.lza.aifactory.dto.IssueDto;
import com.lza.aifactory.dto.TaskRecord;
import com.lza.aifactory.service.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/webhook")
public class TelegramWebhookController {
    private static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";
    private static final Logger log = LoggerFactory.getLogger(TelegramWebhookController.class);

    private final TaskService taskService;
    private final String telegramSecret;
    private final String botToken;
    private final String publicBaseUrl;
    private final RestClient restClient = RestClient.create();

    public TelegramWebhookController(TaskService taskService,
                                     @Value("${ai-factory.telegram-secret:}") String telegramSecret,
                                     @Value("${ai-factory.telegram-bot-token:}") String botToken,
                                     @Value("${ai-factory.public-base-url:http://localhost:8080}") String publicBaseUrl) {
        this.taskService = taskService;
        this.telegramSecret = telegramSecret;
        this.botToken = botToken;
        this.publicBaseUrl = publicBaseUrl;
    }

    @PostMapping("/telegram")
    public ResponseEntity<?> telegram(
            @RequestHeader(value = SECRET_HEADER, required = false) String secretHeader,
            @RequestBody JsonNode payload) throws Exception {

        if (telegramSecret != null && !telegramSecret.isBlank()
                && !constantTimeEquals(telegramSecret, secretHeader)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_telegram_secret"));
        }

        String text = payload.path("message").path("text").asText("");
        Long chatId = payload.path("message").path("chat").path("id").asLong();
        IssueDto dto = parseTelegramIssue(text, chatId);
        TaskRecord record = taskService.submit(dto);
        sendInlineButtons(chatId, record);
        return ResponseEntity.ok(record);
    }

    /**
     * Best-effort reply with inline buttons linking to the friendly progress page
     * and the JSON status. Uses URL buttons only (no callback handling needed).
     */
    private void sendInlineButtons(Long chatId, TaskRecord record) {
        if (botToken == null || botToken.isBlank() || chatId == null || chatId == 0L) return;
        String base = publicBaseUrl.replaceAll("/+$", "");
        String uiUrl = base + "/gateway/ui/" + record.taskId();
        Map<String, Object> body = Map.of(
                "chat_id", chatId,
                "text", "📩 已收到你的需求「" + record.title() + "」，開始處理中。點下方按鈕隨時看進度。",
                "reply_markup", Map.of("inline_keyboard", List.of(List.of(
                        Map.of("text", "📊 查看進度", "url", uiUrl)
                ))));
        try {
            restClient.post()
                    .uri("https://api.telegram.org/bot{token}/sendMessage", botToken)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Failed to send Telegram inline buttons for {}: {}", record.taskId(), e.getMessage());
        }
    }

    /** Length-independent, constant-time secret comparison (null-safe). */
    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
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
