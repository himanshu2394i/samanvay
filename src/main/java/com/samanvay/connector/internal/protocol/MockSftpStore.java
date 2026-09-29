package com.samanvay.connector.internal.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class MockSftpStore {

    public record File(String filename, String csv, String checksum) {}

    private final Map<String, List<File>> files = new ConcurrentHashMap<>();

    MockSftpStore() {
        put("municipal-sftp-mock", "property-2026-09-13.csv", "propertyId,propertyRef\nPROP-88,WARD-12-88\n");
    }

    public void put(String dataSourceCode, String filename, String csv) {
        files.computeIfAbsent(dataSourceCode, k -> new ArrayList<>()).removeIf(f -> f.filename().equals(filename));
        files.get(dataSourceCode).add(new File(filename, csv, sha256(csv)));
    }

    public List<File> list(String dataSourceCode) {
        return List.copyOf(files.getOrDefault(dataSourceCode, List.of()));
    }

    JsonRow lookup(String dataSourceCode, String idColumn, String idValue) {
        for (File file : list(dataSourceCode)) {
            JsonRow row = findRow(file.csv(), idColumn, idValue);
            if (row != null) {
                return row;
            }
        }
        return null;
    }

    /** First row of {@code csv} whose id column equals {@code idValue}; shared by the mock and real SFTP paths. */
    static JsonRow findRow(String csv, String idColumn, String idValue) {
        String[] lines = csv.split("\\R");
        if (lines.length < 2) {
            return null;
        }
        String[] headers = lines[0].split(",");
        int idIdx = -1;
        for (int i = 0; i < headers.length; i++) {
            if (headers[i].equals(idColumn) || "propertyId".equals(headers[i])) {
                idIdx = i;
                break;
            }
        }
        if (idIdx < 0) {
            return null;
        }
        for (int r = 1; r < lines.length; r++) {
            if (lines[r].isBlank()) {
                continue;
            }
            String[] cols = lines[r].split(",", -1);
            if (idIdx < cols.length && cols[idIdx].equals(idValue)) {
                return new JsonRow(headers, cols);
            }
        }
        return null;
    }

    static String sha256(String csv) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(csv.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    record JsonRow(String[] headers, String[] cols) {}
}
