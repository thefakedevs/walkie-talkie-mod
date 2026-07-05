package fr.flaton.walkietalkie.music;

import fr.flaton.walkietalkie.config.ModConfig;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

public final class MusicSourceValidator {

    private MusicSourceValidator() {
    }

    public static ValidationResult validate(String rawIdentifier) {
        String identifier = rawIdentifier == null ? "" : rawIdentifier.trim();
        if (identifier.isEmpty()) {
            return ValidationResult.denied("Music source is empty.");
        }

        URI uri;
        try {
            uri = URI.create(identifier);
        } catch (IllegalArgumentException e) {
            return ValidationResult.denied("Music source is not a valid URI.");
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            if (ModConfig.musicAllowLocalFiles) {
                return ValidationResult.allowed(identifier, "local file");
            }
            return ValidationResult.denied("Local file paths are disabled.");
        }

        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        if ("file".equals(normalizedScheme)) {
            if (!ModConfig.musicAllowLocalFiles) {
                return ValidationResult.denied("Local file URLs are disabled.");
            }
            return ValidationResult.allowed(identifier, "local file");
        }

        if (!"http".equals(normalizedScheme) && !"https".equals(normalizedScheme)) {
            return ValidationResult.denied("Only http(s) URLs are allowed for remote music sources.");
        }

        if (isYoutubeHost(uri)) {
            if (!ModConfig.musicAllowYoutube) {
                return ValidationResult.denied("YouTube music sources are disabled.");
            }
        } else if (!ModConfig.musicAllowDirectUrls) {
            return ValidationResult.denied("Direct URL music sources are disabled.");
        }

        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return ValidationResult.denied("Music URL must include a host.");
        }

        if (ModConfig.musicBlockPrivateNetworkUrls && isPrivateNetworkHost(uri.getHost())) {
            return ValidationResult.denied("Music URL resolves to a private or local network address.");
        }

        return ValidationResult.allowed(identifier, isYoutubeHost(uri) ? "youtube" : "direct url");
    }

    private static boolean isYoutubeHost(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("youtu.be")
                || normalized.equals("youtube.com")
                || normalized.equals("www.youtube.com")
                || normalized.equals("music.youtube.com")
                || normalized.endsWith(".youtube.com");
    }

    private static boolean isPrivateNetworkHost(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                return true;
            }
            for (InetAddress address : addresses) {
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    public record ValidationResult(boolean allowed, String identifier, String sourceType, String message) {
        public static ValidationResult allowed(String identifier, String sourceType) {
            return new ValidationResult(true, identifier, sourceType, "");
        }

        public static ValidationResult denied(String message) {
            return new ValidationResult(false, "", "", message);
        }
    }
}
