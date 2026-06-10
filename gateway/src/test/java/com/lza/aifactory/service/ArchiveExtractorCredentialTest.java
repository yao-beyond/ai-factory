package com.lza.aifactory.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The upload filter must drop credential-bearing entries (mirror of the
 * result.zip egress exclusions) while keeping ordinary project files.
 */
class ArchiveExtractorCredentialTest {

    @Test
    void skipsCredentialBearingPaths() {
        // git metadata (token-in-URL remote lives here), root and nested
        assertTrue(ArchiveExtractor.isCredentialPath(".git/config"));
        assertTrue(ArchiveExtractor.isCredentialPath("sub/project/.git/config"));
        // env files
        assertTrue(ArchiveExtractor.isCredentialPath(".env"));
        assertTrue(ArchiveExtractor.isCredentialPath("app/.env.production"));
        // auth / cloud / CLI dirs
        assertTrue(ArchiveExtractor.isCredentialPath(".ssh/id_rsa"));
        assertTrue(ArchiveExtractor.isCredentialPath("home/.aws/credentials"));
        assertTrue(ArchiveExtractor.isCredentialPath(".claude/.credentials.json"));
        assertTrue(ArchiveExtractor.isCredentialPath("x/.codex/auth.json"));
        // credential files
        assertTrue(ArchiveExtractor.isCredentialPath(".netrc"));
        assertTrue(ArchiveExtractor.isCredentialPath(".git-credentials"));
        assertTrue(ArchiveExtractor.isCredentialPath("proj/.npmrc"));
        // private keys
        assertTrue(ArchiveExtractor.isCredentialPath("id_ed25519"));
        assertTrue(ArchiveExtractor.isCredentialPath("keys/server.key"));
        assertTrue(ArchiveExtractor.isCredentialPath("tls/cert.pem"));
        assertTrue(ArchiveExtractor.isCredentialPath("store.p12"));
        assertTrue(ArchiveExtractor.isCredentialPath("app.keystore"));
    }

    @Test
    void keepsOrdinaryProjectFiles() {
        assertFalse(ArchiveExtractor.isCredentialPath("index.html"));
        assertFalse(ArchiveExtractor.isCredentialPath("src/main.js"));
        assertFalse(ArchiveExtractor.isCredentialPath("styles/app.css"));
        assertFalse(ArchiveExtractor.isCredentialPath("README.md"));
        assertFalse(ArchiveExtractor.isCredentialPath("data/config.json"));
        // ".gitignore" is not git metadata and is a legitimate file to keep
        assertFalse(ArchiveExtractor.isCredentialPath(".gitignore"));
        // a directory literally named "environments" must not trip the .env rule
        assertFalse(ArchiveExtractor.isCredentialPath("environments/list.txt"));
    }
}
