package com.javaducker.server.ingestion;

import com.javaducker.server.config.AppConfig;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class EmbeddingService {

    private final int dimension;

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "can", "shall", "to", "of", "in", "for",
            "on", "with", "at", "by", "from", "as", "into", "through", "during",
            "before", "after", "above", "below", "between", "out", "off", "over",
            "under", "again", "further", "then", "once", "and", "but", "or", "nor",
            "not", "so", "too", "very", "just", "about", "up", "down", "here", "there",
            "when", "where", "why", "how", "all", "each", "every", "both", "few",
            "more", "most", "other", "some", "such", "no", "only", "own", "same",
            "than", "this", "that", "these", "those", "it", "its"
    );

    public EmbeddingService(AppConfig config) {
        this.dimension = config.getEmbeddingDim();
    }

    public double[] embed(String text) {
        double[] vector = new double[dimension];
        List<String> tokens = tokenize(text);

        if (tokens.isEmpty()) return vector;

        // Unigram features
        for (String token : tokens) {
            int idx = Math.abs(murmurHash(token)) % dimension;
            vector[idx] += 1.0;
        }

        // Bigram features for context
        for (int i = 0; i < tokens.size() - 1; i++) {
            String bigram = tokens.get(i) + "_" + tokens.get(i + 1);
            int idx = Math.abs(murmurHash(bigram)) % dimension;
            vector[idx] += 0.5;
        }

        // L2 normalize
        normalize(vector);
        return vector;
    }

    public int getDimension() {
        return dimension;
    }

    static List<String> tokenize(String text) {
        return Arrays.stream(text.toLowerCase().split("[^a-zA-Z0-9]+"))
                .filter(t -> t.length() > 1)
                .filter(t -> !STOP_WORDS.contains(t))
                .collect(Collectors.toList());
    }

    static int murmurHash(String s) {
        // Simple hash inspired by MurmurHash
        int h = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x01000193;
        }
        return h;
    }

    static void normalize(double[] vector) {
        double norm = 0;
        for (double v : vector) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
    }
}
