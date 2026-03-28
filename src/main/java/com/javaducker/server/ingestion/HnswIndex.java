package com.javaducker.server.ingestion;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Pure Java HNSW (Hierarchical Navigable Small World) index for approximate nearest neighbor search.
 * Uses cosine distance (1 - cosine_similarity) as the distance metric.
 */
public class HnswIndex {

    private final int dimension;
    private final int m;
    private final int efConstruction;
    private final int efSearch;
    private final double mL; // level multiplier: 1 / ln(M)

    private final ConcurrentHashMap<String, Node> nodes = new ConcurrentHashMap<>();
    private volatile String entryPointId;
    private volatile int maxLevel;

    public HnswIndex(int dimension, int m, int efConstruction, int efSearch) {
        this.dimension = dimension;
        this.m = m;
        this.efConstruction = efConstruction;
        this.efSearch = efSearch;
        this.mL = 1.0 / Math.log(m);
        this.maxLevel = -1;
    }

    public record Result(String id, double distance) {}

    private static class Node {
        final String id;
        final double[] vector;
        final int level;
        final List<List<String>> connections; // connections[layer] = neighbor IDs

        Node(String id, double[] vector, int level) {
            this.id = id;
            this.vector = vector;
            this.level = level;
            this.connections = new ArrayList<>(level + 1);
            for (int i = 0; i <= level; i++) {
                this.connections.add(Collections.synchronizedList(new ArrayList<>()));
            }
        }
    }

    public synchronized void insert(String id, double[] vector) {
        if (vector.length != dimension) {
            throw new IllegalArgumentException("Expected dimension " + dimension + ", got " + vector.length);
        }
        if (nodes.containsKey(id)) return;

        int nodeLevel = randomLevel();
        Node newNode = new Node(id, vector, nodeLevel);
        nodes.put(id, newNode);

        if (entryPointId == null) {
            entryPointId = id;
            maxLevel = nodeLevel;
            return;
        }

        String currentId = entryPointId;
        int currentMaxLevel = maxLevel;

        // Greedy descent from top to nodeLevel + 1
        for (int layer = currentMaxLevel; layer > nodeLevel; layer--) {
            currentId = greedyClosest(currentId, vector, layer);
        }

        // For each layer from min(nodeLevel, currentMaxLevel) down to 0, search and connect
        for (int layer = Math.min(nodeLevel, currentMaxLevel); layer >= 0; layer--) {
            List<String> neighbors = searchLayer(currentId, vector, efConstruction, layer);
            // Select M closest
            List<String> selected = selectNeighbors(vector, neighbors, m);

            // Set connections for new node at this layer
            newNode.connections.get(layer).addAll(selected);

            // Add bidirectional connections and prune if needed
            for (String neighborId : selected) {
                Node neighbor = nodes.get(neighborId);
                if (neighbor == null || layer > neighbor.level) continue;
                List<String> nConns = neighbor.connections.get(layer);
                nConns.add(id);
                if (nConns.size() > m) {
                    pruneConnections(neighbor, layer);
                }
            }

            if (!neighbors.isEmpty()) {
                // Update currentId to closest found for next layer descent
                currentId = neighbors.get(0);
            }
        }

        // Update entry point if new node has higher level
        if (nodeLevel > maxLevel) {
            entryPointId = id;
            maxLevel = nodeLevel;
        }
    }

    public List<Result> search(double[] query, int k) {
        if (query.length != dimension) {
            throw new IllegalArgumentException("Expected dimension " + dimension + ", got " + query.length);
        }
        if (entryPointId == null) return Collections.emptyList();

        String currentId = entryPointId;

        // Greedy descent from top layer to layer 1
        for (int layer = maxLevel; layer > 0; layer--) {
            currentId = greedyClosest(currentId, query, layer);
        }

        // Beam search at layer 0
        List<String> candidates = searchLayer(currentId, query, Math.max(efSearch, k), 0);

        // Return top-k
        List<Result> results = new ArrayList<>();
        for (String candidateId : candidates) {
            Node node = nodes.get(candidateId);
            if (node != null) {
                results.add(new Result(candidateId, cosineDistance(query, node.vector)));
            }
        }
        results.sort(Comparator.comparingDouble(Result::distance));
        return results.size() > k ? results.subList(0, k) : results;
    }

    public int size() {
        return nodes.size();
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /**
     * Greedily traverse the layer to find the single closest node to the query vector.
     */
    private String greedyClosest(String startId, double[] query, int layer) {
        String bestId = startId;
        double bestDist = cosineDistance(query, nodes.get(startId).vector);

        boolean improved = true;
        while (improved) {
            improved = false;
            Node bestNode = nodes.get(bestId);
            if (bestNode == null || layer > bestNode.level) break;
            List<String> conns = bestNode.connections.get(layer);
            for (String neighborId : conns) {
                Node neighbor = nodes.get(neighborId);
                if (neighbor == null) continue;
                double dist = cosineDistance(query, neighbor.vector);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestId = neighborId;
                    improved = true;
                }
            }
        }
        return bestId;
    }

    /**
     * Beam search at a given layer. Returns candidate IDs sorted by distance (closest first).
     */
    private List<String> searchLayer(String startId, double[] query, int ef, int layer) {
        Set<String> visited = new HashSet<>();
        double startDist = cosineDistance(query, nodes.get(startId).vector);
        visited.add(startId);

        TreeMap<Double, List<String>> candidateMap = new TreeMap<>();
        TreeMap<Double, List<String>> resultMap = new TreeMap<>();
        addToMap(candidateMap, startDist, startId);
        addToMap(resultMap, startDist, startId);

        while (!candidateMap.isEmpty()) {
            // Get closest candidate
            Map.Entry<Double, List<String>> closest = candidateMap.firstEntry();
            double cDist = closest.getKey();
            String cId = closest.getValue().remove(0);
            if (closest.getValue().isEmpty()) candidateMap.pollFirstEntry();

            // Get farthest result
            double farthestResultDist = resultMap.lastKey();
            if (cDist > farthestResultDist) break;

            Node cNode = nodes.get(cId);
            if (cNode == null || layer > cNode.level) continue;

            for (String neighborId : cNode.connections.get(layer)) {
                if (visited.contains(neighborId)) continue;
                visited.add(neighborId);

                Node neighbor = nodes.get(neighborId);
                if (neighbor == null) continue;
                double nDist = cosineDistance(query, neighbor.vector);
                farthestResultDist = resultMap.lastKey();
                if (nDist < farthestResultDist || countMap(resultMap) < ef) {
                    addToMap(candidateMap, nDist, neighborId);
                    addToMap(resultMap, nDist, neighborId);

                    // Trim results to ef
                    while (countMap(resultMap) > ef) {
                        Map.Entry<Double, List<String>> last = resultMap.lastEntry();
                        last.getValue().remove(last.getValue().size() - 1);
                        if (last.getValue().isEmpty()) resultMap.pollLastEntry();
                    }
                }
            }
        }

        // Collect results sorted by distance
        List<String> sortedResults = new ArrayList<>();
        for (Map.Entry<Double, List<String>> entry : resultMap.entrySet()) {
            sortedResults.addAll(entry.getValue());
        }
        return sortedResults;
    }

    private void addToMap(TreeMap<Double, List<String>> map, double dist, String id) {
        map.computeIfAbsent(dist, k -> new ArrayList<>()).add(id);
    }

    private int countMap(TreeMap<Double, List<String>> map) {
        int count = 0;
        for (List<String> v : map.values()) count += v.size();
        return count;
    }

    /**
     * Select up to maxConn nearest neighbors from candidates.
     */
    private List<String> selectNeighbors(double[] query, List<String> candidates, int maxConn) {
        List<Map.Entry<String, Double>> scored = new ArrayList<>();
        for (String id : candidates) {
            Node node = nodes.get(id);
            if (node != null) {
                scored.add(Map.entry(id, cosineDistance(query, node.vector)));
            }
        }
        scored.sort(Comparator.comparingDouble(Map.Entry::getValue));
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < Math.min(maxConn, scored.size()); i++) {
            selected.add(scored.get(i).getKey());
        }
        return selected;
    }

    /**
     * Prune connections of a node at a given layer to keep only M closest.
     */
    private void pruneConnections(Node node, int layer) {
        List<String> conns = node.connections.get(layer);
        List<Map.Entry<String, Double>> scored = new ArrayList<>();
        for (String neighborId : conns) {
            Node neighbor = nodes.get(neighborId);
            if (neighbor != null) {
                scored.add(Map.entry(neighborId, cosineDistance(node.vector, neighbor.vector)));
            }
        }
        scored.sort(Comparator.comparingDouble(Map.Entry::getValue));
        List<String> pruned = new ArrayList<>();
        for (int i = 0; i < Math.min(m, scored.size()); i++) {
            pruned.add(scored.get(i).getKey());
        }
        conns.clear();
        conns.addAll(pruned);
    }

    private int randomLevel() {
        return (int) Math.floor(-Math.log(ThreadLocalRandom.current().nextDouble()) * mL);
    }

    static double cosineDistance(double[] a, double[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 1.0;
        return 1.0 - dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
