package dev.agentkit.core.knowledge;

/**
 * A deterministic bag-of-words embedding model for tests: each token increments a
 * dimension chosen by its hash. Not semantically meaningful, but stable and
 * dependency-free — enough to exercise cosine ranking and dimension checks.
 */
final class HashingEmbeddingModel implements EmbeddingModel {

    private final int dim;

    HashingEmbeddingModel(int dim) {
        this.dim = dim;
    }

    @Override
    public float[] embed(String text) {
        float[] v = new float[dim];
        for (String token : text.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.isEmpty()) {
                continue;
            }
            int bucket = Math.floorMod(token.hashCode(), dim);
            v[bucket] += 1f;
        }
        return v;
    }
}
