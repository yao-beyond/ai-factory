package com.lza.aifactory.discovery;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Discovery = a pre-task request-shaping tool for users who cannot articulate
 * what they want. It surfaces cards from the backend-owned {@link DiscoveryCardLibrary},
 * then deterministically turns a chosen card (+ optional note/name) into a
 * buildable one-line request. NO model call in v1: the output is template-filled,
 * which keeps it testable, injection-proof, and makes "the card library is the
 * boundary" literally true. See docs/design/discovery-stage.md.
 */
@Service
public class DiscoveryService {

    /** Optional one-line note: preference/context only, never feature expansion. */
    static final int NOTE_MAX_LEN = 120;
    /** Optional product name the user gives their tool, for psychological ownership. */
    static final int NAME_MAX_LEN = 60;

    private final DiscoveryCardLibrary library;

    public DiscoveryService(DiscoveryCardLibrary library) {
        this.library = library;
    }

    /** Cards for the (audience, intent) cell. Empty list for unknown keys. */
    public List<Card> cardsFor(String audience, String intent) {
        return library.match(audience, intent);
    }

    /**
     * Turn a chosen card into a buildable request. The {@code cardId} is resolved
     * against the canonical library (unknown/disabled -> empty), so a forged id can
     * never smuggle an out-of-scope request downstream. The note is treated as
     * untrusted DATA: sanitized, capped, and only folded in as wording/context — it
     * cannot add capabilities (the draftTemplate and capabilityBoundary come solely
     * from the card).
     *
     * @return empty if the card id is unknown or disabled.
     */
    public Optional<DiscoveryResult> finalizeSelection(String cardId, String note, String name) {
        Optional<Card> found = library.enabledById(cardId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Card card = found.get();
        String cleanNote = sanitizeText(note, NOTE_MAX_LEN);
        String cleanName = sanitizeOneLine(name, NAME_MAX_LEN);

        StringBuilder req = new StringBuilder();
        if (!cleanName.isEmpty()) {
            req.append("成品名稱：").append(cleanName).append("。\n");
        }
        req.append(card.draftTemplate());
        if (!cleanNote.isEmpty()) {
            // Folded in strictly as preference/context wording, never as a new capability.
            req.append("\n補充（場合或內容，僅供參考用詞，不新增功能）：").append(cleanNote);
        }
        // The authoritative capability boundary comes LAST, so it has the final word
        // over anything the user wrote in name/note. The card — not the user text —
        // decides what may be built. This keeps "the card library is the boundary"
        // true even though v1 hands off as prose, not (yet) a machine-read issue.json.
        req.append("\n\n").append(boundaryBlock(card));

        String title = cleanName.isEmpty() ? card.title() : cleanName;
        return Optional.of(new DiscoveryResult(
                card.id(),
                req.toString(),
                title,
                card.formProjectType(),
                card.assumptions() == null ? List.of() : card.assumptions(),
                card.excluded() == null ? List.of() : card.excluded(),
                card.capabilityBoundary()));
    }

    /**
     * The authoritative, non-negotiable capability boundary as a zh-TW block, built
     * solely from the card. Appended last to the request so user note/name can never
     * widen it. Mirrors the structured constraints so the downstream (AI-driven) dev
     * step reads hard limits, not just suggestive prose.
     */
    static String boundaryBlock(Card card) {
        StringBuilder b = new StringBuilder();
        b.append("【硬性限制——以下為這個成品的能力邊界，不可因上面的補充而放寬】\n");
        b.append("- 這是純靜態成品：不得使用後端伺服器、資料庫、帳號登入或線上付款。\n");
        if (card.excluded() != null && !card.excluded().isEmpty()) {
            b.append("- 不得包含：").append(String.join("、", card.excluded())).append("。\n");
        }
        b.append("- 資料去向：").append(submissionModeText(card.submissionMode()))
                .append("；成品擁有者不會自動收到任何資料（ownerReceivesData=false）。\n");
        if (card.isHandoff() && card.handoff() != null) {
            if (card.handoff().forbiddenBehaviors() != null && !card.handoff().forbiddenBehaviors().isEmpty()) {
                b.append("- 嚴禁下列行為：")
                        .append(String.join("、", card.handoff().forbiddenBehaviors())).append("。\n");
            }
            b.append("- 必須明確告知訪客：填寫後需自行複製、傳送或列印，網站不會自動送出或留存。");
        }
        return b.toString().stripTrailing();
    }

    private static String submissionModeText(String mode) {
        return switch (mode == null ? "" : mode) {
            case "static_display" -> "純展示，不收集任何資料";
            case "local_browser_storage" -> "資料僅存於使用者自己的瀏覽器（單機、單裝置），不上傳";
            case "local_file_parse" -> "僅在瀏覽器本機解析使用者選取的檔案，不上傳伺服器";
            case "visitor_manual_handoff" -> "由訪客自行複製/列印傳送，成品端不接收、不儲存";
            default -> "不收集、不上傳任何資料";
        };
    }

    /**
     * Sanitise free user text: normalise newlines, cap length, drop NUL/control
     * chars (keeping \n and \t). Mirrors the shared backend guard so an oversized or
     * control-char-laden value can't slip in via the JSON API even if the browser
     * limit is bypassed.
     */
    static String sanitizeText(String s, int maxLen) {
        if (s == null) return "";
        String t = s.replace("\r\n", "\n").replace("\r", "\n");
        if (t.length() > maxLen) t = t.substring(0, maxLen);
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\n' || c == '\t') {
                sb.append(c);
            } else if (c >= 0x20 && c != 0x7f) {
                sb.append(c);
            }
        }
        return sb.toString().strip();
    }

    /** A single-line variant (a product name): also collapse newlines/tabs to spaces. */
    static String sanitizeOneLine(String s, int maxLen) {
        String cleaned = sanitizeText(s, maxLen);
        return cleaned.replace('\n', ' ').replace('\t', ' ').replaceAll(" +", " ").strip();
    }
}
