package com.lza.aifactory.service;

import org.springframework.stereotype.Component;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Safely extracts an uploaded zip into a target directory. Protects against:
 * - zip-slip / path traversal (entries that resolve outside the target),
 * - absolute-path entries,
 * - zip bombs (caps total uncompressed bytes and entry count),
 * - credential ingestion (git metadata, env files, SSH/cloud/CLI auth dirs and
 *   private keys are skipped, so an uploaded project can't smuggle a secret into
 *   the workspace where the AI agent runs — the mirror of result.zip's egress
 *   exclusions).
 */
@Component
public class ArchiveExtractor {

    private static final long MAX_TOTAL_BYTES = 100L * 1024 * 1024; // 100 MB
    private static final int MAX_ENTRIES = 5000;
    private static final int BUFFER = 8192;

    public void extractZip(InputStream in, Path targetDir) throws IOException {
        Path root = targetDir.toAbsolutePath().normalize();
        Files.createDirectories(root);

        long totalBytes = 0;
        int entries = 0;

        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new IOException("封存檔包含過多檔案（上限 " + MAX_ENTRIES + "）。");
                }
                String name = entry.getName();
                // Reject absolute paths outright.
                if (name.startsWith("/") || name.startsWith("\\") || name.contains(":")) {
                    throw new IOException("封存檔含不安全的路徑：" + name);
                }
                Path resolved = root.resolve(name).normalize();
                // Zip-slip: the resolved path must stay inside the target dir.
                if (!resolved.startsWith(root)) {
                    throw new IOException("封存檔含路徑穿越項目：" + name);
                }
                // Never ingest credential-bearing files from an uploaded project.
                if (isCredentialPath(name)) {
                    zis.closeEntry();
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                    zis.closeEntry();
                    continue;
                }
                Files.createDirectories(resolved.getParent());
                try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(resolved))) {
                    byte[] buf = new byte[BUFFER];
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        totalBytes += n;
                        if (totalBytes > MAX_TOTAL_BYTES) {
                            throw new IOException("封存檔解壓後過大（上限 100 MB）。");
                        }
                        os.write(buf, 0, n);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    /**
     * True for archive entries that may carry a credential and must never be
     * written into the workspace: git metadata (a token-in-URL remote lives in
     * .git/config), env files, SSH / cloud / AI-CLI auth dirs, and private keys.
     * Kept in sync with the egress exclusions in scripts/run-task.sh.
     */
    static boolean isCredentialPath(String name) {
        String n = name.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        String[] parts = n.split("/");
        for (String p : parts) {
            if (p.equals(".git") || p.equals(".ssh") || p.equals(".aws")
                    || p.equals(".claude") || p.equals(".codex")) {
                return true;
            }
        }
        String base = parts.length == 0 ? n : parts[parts.length - 1];
        if (base.equals(".env") || base.startsWith(".env.")
                || base.equals(".netrc") || base.equals(".git-credentials")
                || base.equals(".npmrc") || base.equals(".pypirc")) {
            return true;
        }
        if (base.startsWith("id_rsa") || base.startsWith("id_dsa")
                || base.startsWith("id_ecdsa") || base.startsWith("id_ed25519")) {
            return true;
        }
        return base.endsWith(".pem") || base.endsWith(".key") || base.endsWith(".p12")
                || base.endsWith(".pfx") || base.endsWith(".ppk") || base.endsWith(".jks")
                || base.endsWith(".keystore");
    }
}
