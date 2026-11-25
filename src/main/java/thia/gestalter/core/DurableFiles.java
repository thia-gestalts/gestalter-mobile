package thia.gestalter.core;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;

public final class DurableFiles {
    @FunctionalInterface
    public interface DirectorySync { void sync(Path directory) throws IOException; }

    private final DirectorySync directorySync;

    public DurableFiles(DirectorySync directorySync) {
        this.directorySync = directorySync;
    }

    public void directory(Path path) throws IOException {
        if (Files.isDirectory(path)) return;
        Path parent = path.getParent();
        if (parent != null) directory(parent);
        Files.createDirectories(path);
        if (parent != null) directorySync.sync(parent);
    }

    public void write(Path path, byte[] bytes) throws IOException {
        Path parent = path.getParent();
        directory(parent);
        Path temporary = Files.createTempFile(parent, ".write-", ".tmp");
        try {
            try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer data = ByteBuffer.wrap(bytes);
                while (data.hasRemaining()) out.write(data);
                out.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            directorySync.sync(parent);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
