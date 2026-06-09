package com.lza.aifactory.discovery;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Loads and validates the discovery starter-card library from
 * {@code discovery-cards.json}. This library IS the v1 capability boundary, so
 * the backend owns it as the single source of truth and treats it like code:
 * malformed or out-of-policy cards fail startup, and lookups reject unknown,
 * disabled, or audience/intent-mismatched card ids (so a forged {@code cardId}
 * cannot escape the fence). See docs/design/discovery-stage.md.
 */
@Component
public class DiscoveryCardLibrary {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryCardLibrary.class);

    static final Set<String> AUDIENCES = Set.of("customers", "self", "coworkers");
    static final Set<String> INTENTS = Set.of("showcase", "collect", "automate");
    static final Set<String> FORM_PROJECT_TYPES = Set.of("web", "interactive", "mobile", "backend");
    static final Set<String> SUBMISSION_MODES =
            Set.of("static_display", "local_browser_storage", "local_file_parse", "visitor_manual_handoff");

    private final ObjectMapper objectMapper;
    private final String resourcePath;
    private List<Card> cards = List.of();
    private Map<String, Card> byId = Map.of();

    @Autowired
    public DiscoveryCardLibrary(ObjectMapper objectMapper) {
        this(objectMapper, "discovery-cards.json");
    }

    /** Test seam: load from an arbitrary classpath resource. */
    DiscoveryCardLibrary(ObjectMapper objectMapper, String resourcePath) {
        this.objectMapper = objectMapper;
        this.resourcePath = resourcePath;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Catalog(int schemaVersion, List<Card> cards) {
    }

    @PostConstruct
    public void load() {
        Catalog catalog;
        try (InputStream in = new ClassPathResource(resourcePath).getInputStream()) {
            catalog = objectMapper.readValue(in, Catalog.class);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read discovery card library: " + resourcePath, e);
        }
        if (catalog == null || catalog.cards() == null || catalog.cards().isEmpty()) {
            throw new IllegalStateException("Discovery card library is empty: " + resourcePath);
        }
        if (catalog.schemaVersion() != 1) {
            throw new IllegalStateException(
                    "Unsupported discovery card schemaVersion " + catalog.schemaVersion() + " in " + resourcePath);
        }
        List<String> errors = new ArrayList<>();
        Map<String, Card> ids = new LinkedHashMap<>();
        List<Card> loaded = new ArrayList<>();
        for (Card c : catalog.cards()) {
            validate(c, errors, ids.keySet());
            if (c != null && c.id() != null) {
                ids.put(c.id(), c);
            }
            if (c != null) {
                loaded.add(c);
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "Invalid discovery card library (" + errors.size() + " problem(s)):\n - "
                            + String.join("\n - ", errors));
        }
        this.cards = List.copyOf(loaded);
        this.byId = Map.copyOf(ids);
        log.info("Loaded {} discovery cards ({} enabled)", cards.size(),
                cards.stream().filter(Card::enabled).count());
    }

    /** Validate one card against the v1 policy; collect human-readable problems. */
    private void validate(Card c, List<String> errors, Set<String> seenIds) {
        if (c == null) {
            errors.add("null card entry");
            return;
        }
        String id = c.id();
        String tag = "card[" + id + "]";
        if (id == null || id.isBlank()) {
            errors.add("card with blank id");
            return;
        }
        if (seenIds.contains(id)) {
            errors.add(tag + ": duplicate id");
        }
        if (c.version() < 1) errors.add(tag + ": version must be >= 1");
        if (isBlank(c.title())) errors.add(tag + ": missing title");
        if (isBlank(c.oneLiner())) errors.add(tag + ": missing oneLiner");
        if (isBlank(c.draftTemplate())) errors.add(tag + ": missing draftTemplate");
        if (empty(c.audiences())) errors.add(tag + ": missing audiences");
        else if (!AUDIENCES.containsAll(c.audiences())) errors.add(tag + ": invalid audience key");
        if (empty(c.intents())) errors.add(tag + ": missing intents");
        else if (!INTENTS.containsAll(c.intents())) errors.add(tag + ": invalid intent key");
        if (!FORM_PROJECT_TYPES.contains(c.formProjectType()))
            errors.add(tag + ": invalid formProjectType '" + c.formProjectType() + "'");
        if (!SUBMISSION_MODES.contains(c.submissionMode()))
            errors.add(tag + ": invalid submissionMode '" + c.submissionMode() + "'");
        if (empty(c.excluded())) errors.add(tag + ": must list at least one exclusion");
        if (empty(c.included())) errors.add(tag + ": must list at least one included capability");
        if (c.constraints() == null) errors.add(tag + ": missing constraints");

        // v1 hard rule: no card may auto-receive data, authenticate, or take payment.
        if (c.ownerReceivesData()) errors.add(tag + ": ownerReceivesData must be false in v1");
        if (c.constraints() != null) {
            if (c.constraints().auth()) errors.add(tag + ": auth must be false in v1");
            if (c.constraints().payment()) errors.add(tag + ": payment must be false in v1");
            if (c.constraints().externalIntegrations())
                errors.add(tag + ": externalIntegrations must be false in v1");
            if (c.constraints().actors() < 1) errors.add(tag + ": actors must be >= 1");
            if (c.constraints().workflows() < 1) errors.add(tag + ": workflows must be >= 1");
            // Data destination must match the declared submission mode, so a card's
            // privacy/storage promise cannot quietly drift from its data sources.
            validateDataSources(c, errors, tag);
        }
        // Handoff metadata must be present exactly when the card is a handoff card,
        // and such cards must forbid network submission.
        if (c.isHandoff()) {
            if (c.handoff() == null) {
                errors.add(tag + ": visitor_manual_handoff card must carry handoff metadata");
            } else if (c.handoff().networkSubmissionAllowed()) {
                errors.add(tag + ": handoff card must not allow network submission");
            } else if (isBlank(c.handoff().requiredUserFacingDisclosure())) {
                errors.add(tag + ": handoff card must declare requiredUserFacingDisclosure");
            }
        } else if (c.handoff() != null) {
            errors.add(tag + ": non-handoff card must not carry handoff metadata");
        }
    }

    /**
     * The card's {@code constraints.dataSources} must match its {@code submissionMode}:
     * display/handoff cards store nothing; local cards declare exactly their one local
     * source. This stops a handoff card from quietly carrying a storage data source.
     */
    private void validateDataSources(Card c, List<String> errors, String tag) {
        List<String> ds = c.constraints().dataSources() == null ? List.of() : c.constraints().dataSources();
        String expected = switch (c.submissionMode()) {
            case "static_display", "visitor_manual_handoff" -> "";
            case "local_browser_storage" -> "local_browser_storage";
            case "local_file_parse" -> "local_file_parse";
            default -> null;
        };
        if (expected == null) return; // invalid submissionMode already reported
        if (expected.isEmpty()) {
            if (!ds.isEmpty()) {
                errors.add(tag + ": " + c.submissionMode() + " card must have empty dataSources, got " + ds);
            }
        } else if (!(ds.size() == 1 && expected.equals(ds.get(0)))) {
            errors.add(tag + ": " + c.submissionMode() + " card must have dataSources=[" + expected + "], got " + ds);
        }
    }

    /** All cards (including disabled), defensive copy not needed (immutable list). */
    public List<Card> all() {
        return cards;
    }

    /**
     * Cards matching the (audience, intent) cell, enabled only. This is what the UI
     * surfaces after the two questions.
     */
    public List<Card> match(String audience, String intent) {
        if (!AUDIENCES.contains(audience) || !INTENTS.contains(intent)) {
            return List.of();
        }
        List<Card> out = new ArrayList<>();
        for (Card c : cards) {
            if (c.enabled() && c.audiences().contains(audience) && c.intents().contains(intent)) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * Canonical lookup by id. Returns empty for unknown OR disabled ids, so a
     * forged/stale {@code cardId} from the client can never be used to publish a
     * request the library does not vouch for.
     */
    public Optional<Card> enabledById(String cardId) {
        if (cardId == null) return Optional.empty();
        Card c = byId.get(cardId);
        if (c == null || !c.enabled()) return Optional.empty();
        return Optional.of(c);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean empty(List<?> l) {
        return l == null || l.isEmpty();
    }
}
