package com.javaducker.server.ingestion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HnswIndexTest {

    private static final int DIM = 8;
    private static final int M = 16;
    private static final int EF_CONSTRUCTION = 200;
    private static final int EF_SEARCH = 50;

    private HnswIndex createIndex() {
        return new HnswIndex(DIM, M, EF_CONSTRUCTION, EF_SEARCH);
    }

    private HnswIndex createIndex(int dim) {
        return new HnswIndex(dim, M, EF_CONSTRUCTION, EF_SEARCH);
    }

    private double[] vector(double... values) {
        return values;
    }

    @Test
    void emptyIndexIsEmpty() {
        HnswIndex index = createIndex();
        assertTrue(index.isEmpty());
        assertEquals(0, index.size());
    }

    @Test
    void addAndSearch() {
        HnswIndex index = createIndex();
        double[] target = vector(1, 0, 0, 0, 0, 0, 0, 0);
        index.insert("a", target);
        index.insert("b", vector(0, 1, 0, 0, 0, 0, 0, 0));
        index.insert("c", vector(0, 0, 1, 0, 0, 0, 0, 0));
        index.insert("d", vector(0, 0, 0, 1, 0, 0, 0, 0));
        index.insert("e", vector(0, 0, 0, 0, 1, 0, 0, 0));

        assertEquals(5, index.size());
        assertFalse(index.isEmpty());

        List<HnswIndex.Result> results = index.search(target, 1);
        assertFalse(results.isEmpty());
        assertEquals("a", results.get(0).id());
        assertEquals(0.0, results.get(0).distance(), 1e-9);
    }

    @Test
    void searchReturnsTopK() {
        HnswIndex index = createIndex();
        for (int i = 0; i < 10; i++) {
            double[] v = new double[DIM];
            v[i % DIM] = 1.0;
            v[(i + 1) % DIM] = 0.5 * (i + 1);
            index.insert("node-" + i, v);
        }

        List<HnswIndex.Result> results = index.search(new double[]{1, 0, 0, 0, 0, 0, 0, 0}, 3);
        assertEquals(3, results.size());
    }

    @Test
    void searchEmptyIndex() {
        HnswIndex index = createIndex();
        List<HnswIndex.Result> results = index.search(new double[DIM], 5);
        assertTrue(results.isEmpty());
    }

    @Test
    void addDuplicateId() {
        HnswIndex index = createIndex();
        index.insert("dup", vector(1, 0, 0, 0, 0, 0, 0, 0));
        index.insert("dup", vector(0, 1, 0, 0, 0, 0, 0, 0));
        // insert with existing ID is silently ignored
        assertEquals(1, index.size());

        // search should still find the original vector
        List<HnswIndex.Result> results = index.search(vector(1, 0, 0, 0, 0, 0, 0, 0), 1);
        assertEquals("dup", results.get(0).id());
        assertEquals(0.0, results.get(0).distance(), 1e-9);
    }

    @Test
    void buildIndexAndSearch() {
        HnswIndex index = createIndex();
        index.insert("x", vector(1, 1, 0, 0, 0, 0, 0, 0));
        index.insert("y", vector(0, 0, 1, 1, 0, 0, 0, 0));
        index.insert("z", vector(0, 0, 0, 0, 1, 1, 0, 0));

        // After inserting, search still works correctly
        List<HnswIndex.Result> results = index.search(vector(1, 1, 0, 0, 0, 0, 0, 0), 3);
        assertFalse(results.isEmpty());
        assertEquals("x", results.get(0).id());
    }

    @Test
    void distanceOrdering() {
        HnswIndex index = createIndex();
        // identical to query
        index.insert("identical", vector(1, 0, 0, 0, 0, 0, 0, 0));
        // similar to query (small angle)
        index.insert("similar", vector(1, 0.2, 0, 0, 0, 0, 0, 0));
        // distant from query (orthogonal)
        index.insert("distant", vector(0, 0, 0, 0, 0, 0, 0, 1));

        double[] query = vector(1, 0, 0, 0, 0, 0, 0, 0);
        List<HnswIndex.Result> results = index.search(query, 3);

        assertEquals(3, results.size());
        assertEquals("identical", results.get(0).id());
        assertEquals("similar", results.get(1).id());
        assertEquals("distant", results.get(2).id());

        // distances should be ascending
        assertTrue(results.get(0).distance() < results.get(1).distance());
        assertTrue(results.get(1).distance() < results.get(2).distance());
    }

    @Test
    void highDimensionalVectors() {
        int dim = 300;
        HnswIndex index = createIndex(dim);

        // Create a target vector: first component = 1, rest = 0
        double[] target = new double[dim];
        target[0] = 1.0;

        // Create 20 vectors with varying similarity to target
        for (int i = 0; i < 20; i++) {
            double[] v = new double[dim];
            v[0] = 1.0 - (i * 0.05);  // decreasing similarity along first component
            v[i % dim] += 0.5;          // add some variation
            index.insert("vec-" + i, v);
        }

        assertEquals(20, index.size());

        List<HnswIndex.Result> results = index.search(target, 5);
        assertEquals(5, results.size());

        // Results should be ordered by distance
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i - 1).distance() <= results.get(i).distance(),
                    "Results should be sorted by distance ascending");
        }
    }

    @Test
    void dimensionMismatchThrows() {
        HnswIndex index = createIndex();
        assertThrows(IllegalArgumentException.class,
                () -> index.insert("bad", new double[]{1, 2, 3}));
        assertThrows(IllegalArgumentException.class,
                () -> index.search(new double[]{1, 2, 3}, 1));
    }

    @Test
    void cosineDistanceBasics() {
        // identical vectors -> distance 0
        double[] a = {1, 0, 0};
        assertEquals(0.0, HnswIndex.cosineDistance(a, a), 1e-9);

        // orthogonal vectors -> distance 1
        double[] b = {0, 1, 0};
        assertEquals(1.0, HnswIndex.cosineDistance(a, b), 1e-9);

        // opposite vectors -> distance 2
        double[] c = {-1, 0, 0};
        assertEquals(2.0, HnswIndex.cosineDistance(a, c), 1e-9);

        // zero vector -> distance 1
        double[] zero = {0, 0, 0};
        assertEquals(1.0, HnswIndex.cosineDistance(a, zero), 1e-9);
    }
}
