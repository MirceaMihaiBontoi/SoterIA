package com.soteria.infrastructure.intelligence.knowledge;

import java.text.BreakIterator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Utility class for vector operations and text processing.
 */
public class VectorMath {
    private VectorMath() {
        throw new UnsupportedOperationException("Utility class");
    }

    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    public static float magnitude(float[] v) {
        return (float) Math.sqrt(dotProduct(v, v));
    }

    public static float[] normalize(float[] v) {
        float mag = magnitude(v);
        if (mag < 1e-9f) {
            float[] eps = new float[v.length];
            eps[0] = 1e-6f;
            return eps;
        }
        float[] res = new float[v.length];
        int i = 0;
        int upper = SPECIES.loopBound(v.length);
        for (; i < upper; i += SPECIES.length()) {
            FloatVector va = FloatVector.fromArray(SPECIES, v, i);
            va.div(mag).intoArray(res, i);
        }
        for (; i < v.length; i++) {
            res[i] = v[i] / mag;
        }
        return res;
    }

    public static float[] subtract(float[] a, float[] b) {
        float[] res = new float[a.length];
        int i = 0;
        int upper = SPECIES.loopBound(a.length);
        for (; i < upper; i += SPECIES.length()) {
            FloatVector va = FloatVector.fromArray(SPECIES, a, i);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, i);
            va.sub(vb).intoArray(res, i);
        }
        for (; i < a.length; i++) {
            res[i] = a[i] - b[i];
        }
        return res;
    }

    public static float dotProduct(float[] a, float[] b) {
        int i = 0;
        float res = 0;
        int upper = SPECIES.loopBound(a.length);
        if (upper > 0) {
            FloatVector acc = FloatVector.zero(SPECIES);
            for (; i < upper; i += SPECIES.length()) {
                FloatVector va = FloatVector.fromArray(SPECIES, a, i);
                FloatVector vb = FloatVector.fromArray(SPECIES, b, i);
                acc = va.fma(vb, acc);
            }
            res = acc.reduceLanes(VectorOperators.ADD);
        }
        for (; i < a.length; i++) {
            res += a[i] * b[i];
        }
        return res;
    }

    public static float cosineSimilarity(float[] v1, float[] v2) {
        float dot = dotProduct(v1, v2);
        float mag1 = magnitude(v1);
        float mag2 = magnitude(v2);
        float mag = mag1 * mag2;
        return (mag > 1e-9) ? (dot / mag) : 0.0f;
    }

    public static float[] computeCentroid(List<float[]> vectors) {
        if (vectors.isEmpty()) return new float[0];
        int dims = vectors.get(0).length;
        float[] centroid = new float[dims];
        int upper = SPECIES.loopBound(dims);
        float size = vectors.size();

        int i = 0;
        // Optimized SIMD: process dimensions in chunks across all vectors
        for (; i < upper; i += SPECIES.length()) {
            FloatVector acc = FloatVector.zero(SPECIES);
            for (float[] v : vectors) {
                acc = acc.add(FloatVector.fromArray(SPECIES, v, i));
            }
            acc.div(size).intoArray(centroid, i);
        }

        // Tail loop for remaining dimensions
        for (; i < dims; i++) {
            float sum = 0;
            for (float[] v : vectors) {
                sum += v[i];
            }
            centroid[i] = sum / size;
        }

        return centroid;
    }

    /**
     * Universal tokenization using BreakIterator to support >50 languages.
     * Removes diacritics and converts to lowercase.
     */
    public static Set<String> tokenize(String text) {
        if (text == null) return new HashSet<>();
        
        // Normalization: Lowercase
        String normalized = text.toLowerCase(Locale.ROOT);
        // Separate diacritics (NFD)
        normalized = java.text.Normalizer.normalize(normalized, java.text.Normalizer.Form.NFD);
        // Surgical removal: only remove marks if they follow a Latin character
        // This preserves essential marks in scripts like Korean, Arabic, Hindi, etc.
        normalized = normalized.replaceAll("(?<=\\p{IsLatin})\\p{M}+", "");
        // Re-compose to NFC
        normalized = java.text.Normalizer.normalize(normalized, java.text.Normalizer.Form.NFC);
        
        Set<String> tokens = new HashSet<>();
        // Use BreakIterator for correct word boundaries in multilingual text (including CJK)
        BreakIterator boundary = BreakIterator.getWordInstance(Locale.ROOT);
        boundary.setText(normalized);
        
        int start = boundary.first();
        for (int end = boundary.next(); end != BreakIterator.DONE; start = end, end = boundary.next()) {
            String word = normalized.substring(start, end).trim();
            // Filter out punctuation and empty strings
            if (!word.isEmpty() && Character.isLetterOrDigit(word.charAt(0))) {
                tokens.add(word);
            }
        }
        return tokens;
    }
}
