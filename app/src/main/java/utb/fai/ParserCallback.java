package utb.fai;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts visible text, word frequencies and hyperlinks from a JSoup document.
 * One instance is created for each page, so its collections do not need
 * additional synchronization.
 */
final class ParserCallback {
    private static final Pattern WORD_PATTERN = Pattern.compile(
            "[\\p{L}]+(?:['’][\\p{L}]+)?");
    private static final Pattern HIDDEN_STYLE_PATTERN = Pattern.compile(
            "(?i)(?:^|;)\\s*(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden)\\b");

    private static final String NON_TEXT_ELEMENTS = String.join(",",
            "script", "style", "noscript", "template", "head", "title",
            "svg", "canvas", "iframe", "object", "embed", "[hidden]",
            "[aria-hidden=true]", "input[type=hidden]");

    private final URI pageURI;
    private final boolean collectLinks;
    private final Map<String, Long> wordFrequencies = new HashMap<>();
    private final Set<URI> links = new LinkedHashSet<>();

    ParserCallback(URI pageURI, boolean collectLinks) {
        this.pageURI = pageURI;
        this.collectLinks = collectLinks;
    }

    /** Parses one HTML document produced by JSoup. */
    void parse(Document document) {
        if (document == null) {
            return;
        }

        if (collectLinks) {
            collectLinks(document);
        }

        // Work on a clone so that extracting text does not alter the parsed
        // document used for link extraction or debugging.
        Document visibleDocument = document.clone();
        visibleDocument.select(NON_TEXT_ELEMENTS).remove();
        removeElementsHiddenByInlineStyles(visibleDocument);

        String visibleText = visibleDocument.body() == null
                ? visibleDocument.text()
                : visibleDocument.body().text();
        countWords(visibleText);
    }

    private void collectLinks(Document document) {
        Elements linkElements = document.select("a[href], area[href], frame[src], iframe[src]");
        for (Element element : linkElements) {
            String attribute = element.hasAttr("href") ? "href" : "src";
            String absoluteUrl = element.absUrl(attribute);

            // absUrl() uses the document's base URI (including a valid <base> tag).
            // If it cannot resolve the value, resolve it against the page URI.
            if (absoluteUrl == null || absoluteUrl.isBlank()) {
                String rawUrl = element.attr(attribute).trim();
                try {
                    absoluteUrl = pageURI.resolve(new URI(rawUrl)).toString();
                } catch (IllegalArgumentException | URISyntaxException ex) {
                    continue;
                }
            }

            addWebLink(absoluteUrl);
        }
    }

    private void addWebLink(String value) {
        try {
            URI uri = new URI(value.trim()).normalize();
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http")
                    || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null || uri.getHost().isBlank()) {
                return;
            }

            // Fragments do not identify different web pages for crawling purposes.
            String normalizedValue = uri.toString();
            int fragmentPosition = normalizedValue.indexOf('#');
            if (fragmentPosition >= 0) {
                normalizedValue = normalizedValue.substring(0, fragmentPosition);
            }
            URI normalizedUri = new URI(normalizedValue).normalize();
            links.add(normalizedUri);
        } catch (IllegalArgumentException | URISyntaxException ex) {
            // Ignore malformed, JavaScript, mailto, data and other non-web URLs.
        }
    }

    private static void removeElementsHiddenByInlineStyles(Document document) {
        Elements styledElements = document.select("[style]");
        for (Element element : styledElements) {
            if (HIDDEN_STYLE_PATTERN.matcher(element.attr("style")).find()) {
                element.remove();
            }
        }
    }

    private void countWords(String text) {
        Matcher matcher = WORD_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            wordFrequencies.merge(matcher.group(), 1L, Long::sum);
        }
    }

    Map<String, Long> getWordFrequencies() {
        return wordFrequencies;
    }

    List<URI> getLinks() {
        return new ArrayList<>(links);
    }
}
