package utb.fai;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Web crawler that analyzes visible text on web pages and follows links
 * up to the selected maximum depth.
 *
 * Usage: java utb.fai.App <start-url> [max-depth] [debug-level]
 */
public final class App {
    private static final int DEFAULT_MAX_DEPTH = 2;
    private static final int DEFAULT_DEBUG_LEVEL = 0;
    private static final int CONNECTION_TIMEOUT_MS = 10_000;
    private static final int MAX_PAGE_SIZE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_THREADS = 32;

    private App() {
        // Utility class.
    }

    public static void main(String[] args) {
        // Keep Czech and other Unicode words intact in terminal output.
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));

        if (args.length == 0 || args[0] == null || args[0].isBlank()) {
            printUsage();
            return;
        }

        final URI startUri;
        final int maxDepth;
        final int debugLevel;
        try {
            startUri = parseStartUri(args[0]);
            maxDepth = parseNonNegativeInt(args, 1, DEFAULT_MAX_DEPTH, "max-depth");
            debugLevel = parseNonNegativeInt(args, 2, DEFAULT_DEBUG_LEVEL, "debug-level");
        } catch (IllegalArgumentException ex) {
            System.err.println("Invalid arguments: " + ex.getMessage());
            printUsage();
            return;
        }

        int threadCount = Math.max(2,
                Math.min(Runtime.getRuntime().availableProcessors(), MAX_THREADS));
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // The ConcurrentHashMap stores all discovered pages and their discovery depth.
        // putIfAbsent prevents the same URL from being scheduled more than once.
        ConcurrentHashMap<URI, Integer> visitedURIs = new ConcurrentHashMap<>();
        visitedURIs.put(startUri, 0);

        Map<String, Long> totalWordFrequencies = new HashMap<>();
        List<URIinfo> currentLevel = new ArrayList<>();
        currentLevel.add(new URIinfo(startUri, 0));

        try {
            // Breadth-first traversal makes the depth limit explicit and predictable.
            for (int depth = 0; depth <= maxDepth && !currentLevel.isEmpty(); depth++) {
                CompletionService<PageResult> completionService =
                        new ExecutorCompletionService<>(executor);

                for (URIinfo page : currentLevel) {
                    completionService.submit(() -> analyzePage(page, debugLevel));
                }

                List<URIinfo> nextLevel = new ArrayList<>();
                int submittedTasks = currentLevel.size();

                for (int i = 0; i < submittedTasks; i++) {
                    try {
                        PageResult result = completionService.take().get();
                        if (result == null) {
                            continue;
                        }

                        mergeFrequencies(totalWordFrequencies, result.wordFrequencies);

                        if (debugLevel > 1) {
                            System.err.println("Finished " + result.uri + ": "
                                    + result.wordFrequencies.size() + " unique words, "
                                    + result.links.size() + " links found.");
                        }

                        // Links are collected from every analyzed page, but pages at
                        // maxDepth are not scheduled for another level of crawling.
                        if (depth < maxDepth) {
                            for (URI link : result.links) {
                                Integer oldDepth = visitedURIs.putIfAbsent(link, depth + 1);
                                if (oldDepth == null) {
                                    nextLevel.add(new URIinfo(link, depth + 1));
                                    if (debugLevel > 1) {
                                        System.err.println("Discovered: " + link);
                                    }
                                }
                            }
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        System.err.println("Crawler was interrupted.");
                        printTopWords(totalWordFrequencies, 20);
                        return;
                    } catch (ExecutionException ex) {
                        Throwable cause = ex.getCause();
                        String message = cause == null ? ex.getMessage() : cause.getMessage();
                        System.err.println("Page processing failed: " + message);
                    }
                }

                currentLevel = nextLevel;
            }
        } finally {
            executor.shutdownNow();
        }

        printTopWords(totalWordFrequencies, 20);
    }

    private static void printUsage() {
        System.err.println("Usage: java utb.fai.App <start-url> [max-depth] [debug-level]");
        System.err.println("  start-url:   required HTTP or HTTPS URL");
        System.err.println("  max-depth:   optional integer >= 0 (default: 2; 0 = start page only)");
        System.err.println("  debug-level: optional integer >= 0 (default: 0)");
    }

    private static URI parseStartUri(String value) {
        String input = value.trim();
        if (!input.matches("(?i)^https?://.*")) {
            input = "https://" + input;
        }

        try {
            URI uri = new URI(input).normalize();
            String scheme = uri.getScheme();
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("The start URL must contain a valid host.");
            }
            if (scheme == null || !(scheme.equalsIgnoreCase("http")
                    || scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("Only HTTP and HTTPS URLs are supported.");
            }
            return removeFragment(uri);
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Invalid start URL: " + value, ex);
        }
    }

    private static int parseNonNegativeInt(String[] args, int index, int defaultValue,
            String parameterName) {
        if (args.length <= index) {
            return defaultValue;
        }

        try {
            int parsed = Integer.parseInt(args[index]);
            if (parsed < 0) {
                throw new IllegalArgumentException(parameterName + " must not be negative.");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(parameterName + " must be a whole number.", ex);
        }
    }

    /** Downloads and analyzes one page. This method is executed by a worker thread. */
    private static PageResult analyzePage(URIinfo page, int debugLevel) {
        if (debugLevel > 0) {
            System.err.println("Analyzing depth " + page.depth + ": " + page.uri);
        }

        try {
            Connection.Response response = Jsoup.connect(page.uri.toString())
                    .userAgent("UTB-WebCrawler/1.0")
                    .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1")
                    .timeout(CONNECTION_TIMEOUT_MS)
                    .maxBodySize(MAX_PAGE_SIZE_BYTES)
                    .followRedirects(true)
                    .execute();

            String contentType = response.contentType();
            if (contentType != null
                    && !contentType.toLowerCase(Locale.ROOT).contains("text/html")
                    && !contentType.toLowerCase(Locale.ROOT).contains("application/xhtml+xml")) {
                if (debugLevel > 1) {
                    System.err.println("Skipping non-HTML resource: " + page.uri
                            + " (" + contentType + ")");
                }
                return PageResult.empty(page.uri);
            }

            // Jsoup parses the response bytes and detects the charset from the
            // HTTP Content-Type header or an HTML META charset declaration.
            // It also resolves relative URLs using the final response URL.
            Document document = response.parse();
            ParserCallback callback = new ParserCallback(page.uri, true);
            callback.parse(document);

            if (debugLevel > 1) {
                System.err.println("Downloaded " + response.url() + " (charset: "
                        + document.outputSettings().charset().name() + ")");
            }

            return new PageResult(page.uri, callback.getWordFrequencies(), callback.getLinks());
        } catch (IOException ex) {
            System.err.println("Unable to analyze " + page.uri + ": " + ex.getMessage());
            return PageResult.empty(page.uri);
        } catch (RuntimeException ex) {
            System.err.println("Invalid page " + page.uri + ": " + ex.getMessage());
            return PageResult.empty(page.uri);
        }
    }

    private static URI removeFragment(URI uri) {
        String value = uri.toString();
        int fragmentPosition = value.indexOf('#');
        if (fragmentPosition >= 0) {
            value = value.substring(0, fragmentPosition);
        }
        try {
            return new URI(value).normalize();
        } catch (URISyntaxException ex) {
            return uri;
        }
    }

    private static void mergeFrequencies(Map<String, Long> destination,
            Map<String, Long> source) {
        for (Map.Entry<String, Long> entry : source.entrySet()) {
            destination.merge(entry.getKey(), entry.getValue(), Long::sum);
        }
    }

    private static void printTopWords(Map<String, Long> frequencies, int limit) {
        if (frequencies.isEmpty()) {
            System.out.println("No words found.");
            return;
        }

        frequencies.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .forEach(entry -> System.out.println(entry.getKey() + ";" + entry.getValue()));
    }

    private static final class PageResult {
        private final URI uri;
        private final Map<String, Long> wordFrequencies;
        private final List<URI> links;

        private PageResult(URI uri, Map<String, Long> wordFrequencies, List<URI> links) {
            this.uri = uri;
            this.wordFrequencies = wordFrequencies;
            this.links = links;
        }

        private static PageResult empty(URI uri) {
            return new PageResult(uri, Map.of(), List.of());
        }
    }
}