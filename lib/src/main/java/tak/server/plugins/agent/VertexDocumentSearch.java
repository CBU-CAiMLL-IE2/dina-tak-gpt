package tak.server.plugins.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;

/** Bounded local TXT/PDF retrieval without a separate embedding model or service. */
public final class VertexDocumentSearch {
    private static final int MAX_FILES = 100;
    private static final long MAX_FILE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_CORPUS_CHARS = 2_000_000;
    private static final int CHUNK_CHARS = 1800;
    private final List<Chunk> chunks = new ArrayList<>();

    private record Chunk(String source, String text, Set<String> words) { }
    private record Match(Chunk chunk, long score) { }

    public VertexDocumentSearch(String directory) throws IOException {
        if (directory == null) {
            return;
        }
        Path root = Path.of(directory).toRealPath();
        int totalChars = 0;
        try (var paths = Files.walk(root, 8)) {
            List<Path> files = paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().toLowerCase(Locale.ROOT).matches(".*\\.(pdf|txt)$"))
                    .sorted().limit(MAX_FILES + 1).toList();
            if (files.size() > MAX_FILES) {
                throw new IOException("ragDirectory exceeds 100 documents");
            }
            for (Path file : files) {
                if (!file.toRealPath().startsWith(root) || Files.size(file) > MAX_FILE_BYTES) {
                    throw new IOException("RAG document escapes directory or exceeds 10 MiB: " + file.getFileName());
                }
                String text;
                if (file.toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                    try (var input = Files.newInputStream(file)) {
                        text = new ApachePdfBoxDocumentParser().parse(input).text();
                    }
                } else {
                    text = Files.readString(file);
                }
                totalChars += text.length();
                if (totalChars > MAX_CORPUS_CHARS) {
                    throw new IOException("RAG corpus exceeds 2000000 extracted characters");
                }
                for (int offset = 0; offset < text.length(); offset += CHUNK_CHARS) {
                    String chunk = text.substring(offset, Math.min(text.length(), offset + CHUNK_CHARS));
                    chunks.add(new Chunk(root.relativize(file) + "#" + (offset / CHUNK_CHARS + 1), chunk, words(chunk)));
                }
            }
        }
    }

    public String search(String query) {
        Set<String> terms = words(query);
        return chunks.stream()
                .map(chunk -> new Match(chunk, terms.stream().filter(chunk.words()::contains).count()))
                .filter(match -> match.score() > 0)
                .sorted(Comparator.comparingLong(Match::score).reversed())
                .limit(4)
                .map(match -> "Source: " + match.chunk().source() + "\n" + match.chunk().text())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("No matching documents in the configured TXT/PDF corpus.");
    }

    private static Set<String> words(String text) {
        Set<String> terms = new HashSet<>(Arrays.asList(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")));
        terms.removeIf(term -> term.length() < 2);
        return terms;
    }
}
