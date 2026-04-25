package com.soteria.infrastructure.intelligence;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility class for vector operations and text processing.
 */
public class VectorMath {
    private VectorMath() {
        throw new UnsupportedOperationException("Utility class");
    }


    public static float magnitude(float[] v) {
        float sum = 0;
        for (float x : v) sum += x * x;
        return (float) Math.sqrt(sum);
    }

    public static float[] normalize(float[] v) {
        float mag = magnitude(v);
        if (mag == 0) return v;
        float[] res = new float[v.length];
        for (int i = 0; i < v.length; i++) res[i] = v[i] / mag;
        return res;
    }

    public static float[] subtract(float[] a, float[] b) {
        float[] res = new float[a.length];
        for (int i = 0; i < a.length; i++) res[i] = a[i] - b[i];
        return res;
    }

    public static float[] computeCentroid(List<float[]> vectors) {
        if (vectors.isEmpty()) return new float[0];
        int dims = vectors.get(0).length;
        float[] centroid = new float[dims];
        for (float[] v : vectors) {
            for (int i = 0; i < dims; i++) centroid[i] += v[i];
        }
        for (int i = 0; i < dims; i++) centroid[i] /= vectors.size();
        return centroid;
    }

    public static Set<String> tokenize(String text) {
        if (text == null) return new HashSet<>();
        String normalized = java.text.Normalizer.normalize(text.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return Arrays.stream(normalized.split("[\\W_]+"))
                .filter(s -> s.length() > 2)
                .collect(Collectors.toSet());
    }

    public static float lexicalOverlap(Set<String> queryTokens, Protocol p) {
        if (queryTokens.isEmpty()) return 0.0f;
        Set<String> docTokens = tokenize(p.getTitle());
        if (p.getKeywords() != null) {
            for (String k : p.getKeywords()) docTokens.addAll(tokenize(k));
        }
        long matches = queryTokens.stream().filter(docTokens::contains).count();
        return (float) matches / queryTokens.size();
    }
}
