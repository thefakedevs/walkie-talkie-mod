package fr.flaton.walkietalkie.music;

import fr.flaton.walkietalkie.Constants;

public final class MusicLifecycle {
    private MusicLifecycle() {
    }

    public static void stopAllSafely() {
        try {
            MusicManager.getInstance().stopAll();
        } catch (NoClassDefFoundError | ExceptionInInitializerError e) {
            Constants.LOGGER.warn("Could not stop music sessions because Lavaplayer is not available on the runtime classpath", e);
        } catch (Exception e) {
            Constants.LOGGER.warn("Failed to stop music sessions during server shutdown", e);
        }
    }
}
