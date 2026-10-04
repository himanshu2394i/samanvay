package in.samanvay.departments.kit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Writes a key or a secret to a file that nobody else can read, from the first byte: the file is CREATED owner-only (not made and then
 * chmod'ed, which leaves a window where any local user could read it), filled in a temporary file next to it, and moved into place
 * in one step, so a reader never sees a half-written secret and a crash never leaves one.
 *
 * <p>Where the file system has no POSIX permissions (Windows) it falls back to the owner-only flags of {@link java.io.File}.
 */
final class SecretFiles {

    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private SecretFiles() {}

    static void writeOwnerOnly(Path file, String content) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = createOwnerOnly(dir);
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static Path createOwnerOnly(Path dir) throws IOException {
        String name = ".secret-" + Long.toHexString(System.nanoTime()) + "-" + Long.toHexString(new java.security.SecureRandom().nextLong()) + ".tmp";
        Path tmp = dir.resolve(name);
        if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
            return Files.createFile(tmp, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        }
        Files.createFile(tmp);
        java.io.File f = tmp.toFile();
        f.setReadable(false, false);
        f.setReadable(true, true);
        f.setWritable(false, false);
        f.setWritable(true, true);
        return tmp;
    }
}
