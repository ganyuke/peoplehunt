package io.github.ganyuke.peoplehunt.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ZipUtil {
    private ZipUtil() {}

    public static void zipDirectory(Path source, Path zipFile) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            try (Stream<Path> stream = Files.walk(source).sorted(Comparator.naturalOrder())) {
                stream.filter(path -> !Files.isDirectory(path)).forEach(path -> {
                    String entryName = source.relativize(path).toString().replace('\\', '/');
                    try (InputStream input = Files.newInputStream(path)) {
                        output.putNextEntry(new ZipEntry(entryName));
                        input.transferTo(output);
                        output.closeEntry();
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });
            }
        } catch (RuntimeException exception) {
            if (exception.getCause() instanceof IOException io) {
                throw io;
            }
            throw exception;
        }
    }
}
