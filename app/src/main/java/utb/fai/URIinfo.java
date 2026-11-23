package utb.fai;

import java.net.URI;
import java.util.Objects;
import java.net.URISyntaxException;


public final class URIinfo {
    public final URI uri;
    public final int depth;

    public URIinfo(URI uri, int depth) {
        this.uri = Objects.requireNonNull(uri, "uri");
        if (depth < 0) {
            throw new IllegalArgumentException("Depth must not be negative.");
        }
        this.depth = depth;
    }

    public URIinfo(String value, int depth) {
        this(parseUri(value), depth);
    }

    private static URI parseUri(String value) {
        try {
            return new URI(Objects.requireNonNull(value, "value"));
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Invalid URI: " + value, ex);
        }
    }
}
