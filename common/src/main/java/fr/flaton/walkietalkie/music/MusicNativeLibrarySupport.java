package fr.flaton.walkietalkie.music;

import fr.flaton.walkietalkie.Constants;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.jar.JarFile;

final class MusicNativeLibrarySupport {
    private static final Logger LOGGER = Constants.LOGGER;
    private static final String LAVA_NATIVE_DIR = "lava.native.dir";
    private static final String LAVA_NATIVE_MPG123_DIR = "lava.native.libmpg123-0.dir";
    private static final String LAVA_NATIVE_CONNECTOR_DIR = "lava.native.connector.dir";

    private MusicNativeLibrarySupport() {
    }

    static void prepareLavaplayerNatives() {
        if (!isWindows()) {
            return;
        }
        if (System.getProperty(LAVA_NATIVE_DIR) != null || System.getProperty(LAVA_NATIVE_MPG123_DIR) != null) {
            LOGGER.info("Lavaplayer native directory is already configured: {}={}, {}={}",
                    LAVA_NATIVE_DIR, System.getProperty(LAVA_NATIVE_DIR),
                    LAVA_NATIVE_MPG123_DIR, System.getProperty(LAVA_NATIVE_MPG123_DIR));
            return;
        }

        String nativePath = is64Bit() ? "natives/win-x86-64/" : "natives/win-x86/";
        try {
            Path targetDirectory = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "walkietalkie-lavaplayer-natives", nativePath.replace('/', '_')));
            copyNative(nativePath + "connector.dll", targetDirectory.resolve("connector.dll"));
            copyNative(nativePath + "libmpg123-0.dll", targetDirectory.resolve("libmpg123-0.dll"));

            String directory = targetDirectory.toAbsolutePath().toString();
            System.setProperty(LAVA_NATIVE_DIR, directory);
            System.setProperty(LAVA_NATIVE_MPG123_DIR, directory);
            System.setProperty(LAVA_NATIVE_CONNECTOR_DIR, directory);
            LOGGER.info("Prepared Lavaplayer native libraries in {}", directory);
        } catch (Exception e) {
            LOGGER.warn("Could not prepare Lavaplayer native libraries; MP3 playback may fail", e);
        }
    }

    private static void copyNative(String resourcePath, Path targetPath) throws IOException {
        try (InputStream inputStream = openNativeResource(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Native resource was not found: " + resourcePath);
            }
            Files.copy(inputStream, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static InputStream openNativeResource(String resourcePath) throws IOException {
        ClassLoader classLoader = MusicNativeLibrarySupport.class.getClassLoader();
        InputStream stream = classLoader.getResourceAsStream(resourcePath);
        if (stream != null) {
            LOGGER.info("Found Lavaplayer native resource on mod classpath: {}", resourcePath);
            return stream;
        }

        Path gradleCacheJar = findGradleNativeJar();
        if (gradleCacheJar == null) {
            return null;
        }

        JarFile jarFile = new JarFile(gradleCacheJar.toFile());
        var entry = jarFile.getEntry(resourcePath);
        if (entry == null) {
            jarFile.close();
            return null;
        }

        LOGGER.info("Found Lavaplayer native resource in Gradle cache: jar={}, resource={}", gradleCacheJar, resourcePath);
        InputStream jarStream = jarFile.getInputStream(entry);
        return new java.io.FilterInputStream(jarStream) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    jarFile.close();
                }
            }
        };
    }

    private static Path findGradleNativeJar() throws IOException {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            return null;
        }

        Path moduleDirectory = Path.of(userHome, ".gradle", "caches", "modules-2", "files-2.1", "dev.arbjerg", "lavaplayer-natives");
        if (!Files.isDirectory(moduleDirectory)) {
            return null;
        }

        try (var files = Files.walk(moduleDirectory, 5)) {
            return files
                    .filter(path -> path.getFileName().toString().equals("lavaplayer-natives-2.2.7.jar"))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean is64Bit() {
        return System.getProperty("os.arch", "").contains("64");
    }
}
