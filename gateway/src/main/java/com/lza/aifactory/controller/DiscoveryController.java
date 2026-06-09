package com.lza.aifactory.controller;

import com.lza.aifactory.discovery.Card;
import com.lza.aifactory.discovery.DiscoveryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovery endpoints: help a user with no idea pick a buildable starter, then
 * hand a one-line request back to the existing homepage form. Stateless and
 * cheap: the questions are answered on the client; the backend only owns the
 * card library (the capability boundary) and the deterministic finalize step.
 * See docs/design/discovery-stage.md.
 */
@RestController
@RequestMapping("/gateway/discovery")
public class DiscoveryController {

    private final DiscoveryService discoveryService;

    public DiscoveryController(DiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    /**
     * Cards for the chosen (audience, intent) cell. Only display-safe fields are
     * returned; the policy fields stay server-side until finalize. Unknown keys
     * yield an empty list (the UI then offers the free-text escape hatch).
     */
    @GetMapping("/cards")
    public Map<String, Object> cards(@RequestParam String audience,
                                     @RequestParam String intent) {
        List<Map<String, Object>> view = discoveryService.cardsFor(audience, intent).stream()
                .map(DiscoveryController::cardView)
                .toList();
        return Map.of("audience", audience, "intent", intent, "cards", view);
    }

    /**
     * The request body for finalize. cardId is required; note/name are optional.
     * The @Size caps are generous abuse guards (a huge body is rejected before it
     * reaches the service); the functional truncation to the design's 120/60 limits
     * happens in DiscoveryService's sanitize step, so normal over-long input is
     * trimmed gracefully rather than rejected.
     */
    public record FinalizeRequest(
            @Size(max = 128) String cardId,
            @Size(max = 2000) String note,
            @Size(max = 200) String name) {
    }

    /**
     * Resolve a chosen card into a buildable request. Rejects unknown/disabled
     * card ids with 400 so a forged id cannot smuggle an out-of-scope request.
     */
    @PostMapping("/finalize")
    public ResponseEntity<?> finalizeSelection(@Valid @RequestBody FinalizeRequest body) {
        if (body == null || body.cardId() == null || body.cardId().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "missing_card", "message", "請先選一張卡片"));
        }
        return discoveryService.finalizeSelection(body.cardId(), body.note(), body.name())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "unknown_card",
                                "message", "找不到這張卡片（可能已下架）")));
    }

    /** Display-safe projection of a card for the picker UI. */
    private static Map<String, Object> cardView(Card c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("title", c.title());
        m.put("oneLiner", c.oneLiner());
        m.put("handoff", c.isHandoff());
        return m;
    }
}
