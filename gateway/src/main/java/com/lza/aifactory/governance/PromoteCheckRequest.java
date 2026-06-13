package com.lza.aifactory.governance;

/**
 * What the pipeline reports to promote-check about the work it produced.
 * {@code testStatus} is the independently-run result of the project's own tests
 * — one of {@code pass} / {@code fail} / {@code none} (no test framework) /
 * {@code unknown} — NOT the AI agent's self-report. The gateway decides the
 * tests-pass gate from it (and the profile's risk tier); it independently checks
 * independence and evidence completeness.
 */
public record PromoteCheckRequest(
        String testStatus,
        String reviewReportRef,
        String diffRef,
        String mode) {

    public String testStatusOrUnknown() {
        return testStatus == null || testStatus.isBlank() ? "unknown" : testStatus.toLowerCase();
    }
}
