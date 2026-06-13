package pt.ulisboa.tecnico.cnv.loadbalancer.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

public class ConcurrentProximityCache {

    // Key is Long, Value is Float (V, C)
    private final ConcurrentSkipListMap<Long, Float> cache = new ConcurrentSkipListMap<>();
    private final long proximityThreshold;

    public ConcurrentProximityCache(long proximityThreshold) {
        this.proximityThreshold = proximityThreshold;
    }

    public void put(long key, float value) {
        cache.put(key, value);
    }

    public List<Map.Entry<Long, Float>> getExactOrClosestValues(long key) {
        List<Map.Entry<Long, Float>> results = new ArrayList<>();

        // 1. Check for exact match
        Float exactValue = cache.get(key);
        if (exactValue != null) {
            results.add(Map.entry(key, exactValue));
            return results;
        }

        // 2. Gather candidates from below and above
        List<Long> candidates = new ArrayList<>();

        // Get up to 2 keys below (ordered closest to furthest)
        // headMap(key, false) returns keys strictly less than 'key'
        int belowCount = 0;
        for (Long lowerKey : cache.headMap(key, false).descendingKeySet()) {
            if (belowCount >= 2) break;
            candidates.add(lowerKey);
            belowCount++;
        }

        // Get up to 2 keys above (ordered closest to furthest)
        // tailMap(key, false) returns keys strictly greater than 'key'
        int aboveCount = 0;
        for (Long higherKey : cache.tailMap(key, false).keySet()) {
            if (aboveCount >= 2) break;
            candidates.add(higherKey);
            aboveCount++;
        }

        // 3. Sort candidates by absolute distance to the target key,
        // filter by proximity threshold, and take the top 2
        candidates.stream()
                .map(k -> Map.entry(k, Math.abs(key - k)))
                .filter(entry -> entry.getValue() <= proximityThreshold)
                .sorted(Map.Entry.comparingByValue()) // Sorts by distance ascending
                .limit(2)
                .forEach(entry -> {
                    Long candidateKey = entry.getKey();
                    Float value = cache.get(candidateKey);
                    if (value != null) {
                        results.add(Map.entry(candidateKey, value));
                    }
                });

        return results;
    }

    public void renewCacheValues(Map<Long,Float> newValues){
        cache.clear();
        cache.putAll(newValues);
        System.out.println("Refreshed Cache");
    }
}
