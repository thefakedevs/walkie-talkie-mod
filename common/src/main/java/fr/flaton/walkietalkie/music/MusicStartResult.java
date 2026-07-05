package fr.flaton.walkietalkie.music;

public record MusicStartResult(boolean success, String trackTitle, String sourceType, String message) {
    public static MusicStartResult success(String trackTitle, String sourceType) {
        return new MusicStartResult(true, trackTitle, sourceType, "");
    }

    public static MusicStartResult failure(String message) {
        return new MusicStartResult(false, "", "", message);
    }
}
