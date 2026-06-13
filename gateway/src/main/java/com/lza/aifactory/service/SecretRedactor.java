package com.lza.aifactory.service;

import java.util.regex.Pattern;

/**
 * Masks credential-shaped text before it leaves the server. Shared by the live
 * activity feed, AI-authored markdown, and governance event readouts so every
 * surface that echoes pipeline/agent output gets the same defence-in-depth view.
 *
 * <p>Patterns cover: URL userinfo, known provider token shapes, secret-ish
 * assignments, authorization headers, Bearer/Basic values, bare AWS secret keys,
 * and host home-directory paths.
 */
public final class SecretRedactor {

    private SecretRedactor() {
    }

    // scheme://user:secret@host  ->  scheme://<redacted>@host
    private static final Pattern URL_CREDS =
            Pattern.compile("([a-zA-Z][a-zA-Z0-9+.\\-]*://)[^/\\s:@]+:[^/\\s@]+@");
    // Known provider token shapes (GitHub, GitLab, Slack, AWS, OpenAI, Google).
    private static final Pattern KNOWN_TOKEN =
            Pattern.compile(
                    "(gh[posru]_[A-Za-z0-9]{16,}"
                    + "|glpat-[A-Za-z0-9_\\-]{16,}"
                    + "|xox[baprs]-[A-Za-z0-9\\-]{10,}"
                    + "|AKIA[0-9A-Z]{16}"
                    + "|sk-[A-Za-z0-9]{20,}"
                    + "|AIza[0-9A-Za-z_\\-]{20,})");
    // An identifier that *contains* a secret word, then = or : and a value.
    private static final Pattern ASSIGN_SECRET =
            Pattern.compile(
                    "(?i)([A-Za-z0-9_\\-]*"
                    + "(?:secret|token|passwd|password|pwd|api[_-]?key|access[_-]?key|"
                    + "private[_-]?key|credential)"
                    + "[A-Za-z0-9_\\-]*)(\\s*[:=]\\s*)(?:Bearer\\s+|Basic\\s+)?\\S+");
    // An `authorization` / `auth` key (whole word) followed by : or =.
    private static final Pattern AUTH_HEADER =
            Pattern.compile("(?i)\\b(authorization|auth)\\b(\\s*[:=]\\s*).+");
    // Bearer/Basic <token> anywhere; token long enough that it isn't prose.
    private static final Pattern BEARER =
            Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._+/=\\-]{8,}");
    // A bare 40-char AWS secret access key (requires at least one + or / so a
    // 40-hex git SHA is never matched).
    private static final Pattern AWS_SECRET_BARE =
            Pattern.compile(
                    "(?<![A-Za-z0-9+/])(?=[A-Za-z0-9+/]{40}(?![A-Za-z0-9+/]))"
                    + "[A-Za-z0-9+/]*[+/][A-Za-z0-9+/]*");
    // Host home directory: /Users/<name>/...  ->  /Users/<user>/...
    private static final Pattern HOME_PATH =
            Pattern.compile("(/Users/|/home/)[^/\\s]+");

    /** Mask all credential shapes in one line of text. */
    public static String redact(String line) {
        if (line == null) return "";
        String s = URL_CREDS.matcher(line).replaceAll("$1<redacted>@");
        s = KNOWN_TOKEN.matcher(s).replaceAll("<redacted>");
        s = ASSIGN_SECRET.matcher(s).replaceAll("$1$2<redacted>");
        s = AUTH_HEADER.matcher(s).replaceAll("$1$2<redacted>");
        s = BEARER.matcher(s).replaceAll("$1 <redacted>");
        s = AWS_SECRET_BARE.matcher(s).replaceAll("<redacted>");
        s = HOME_PATH.matcher(s).replaceAll("$1<user>");
        return s;
    }
}
