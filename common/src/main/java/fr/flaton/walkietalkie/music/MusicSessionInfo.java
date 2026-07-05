package fr.flaton.walkietalkie.music;

public record MusicSessionInfo(
        String frequency,
        String source,
        String sourceType,
        String trackTitle,
        boolean stream,
        long positionMillis,
        long lengthMillis
) {
}
