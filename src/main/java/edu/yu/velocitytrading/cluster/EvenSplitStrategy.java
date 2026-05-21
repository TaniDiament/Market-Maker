package edu.yu.velocitytrading.cluster;

import java.util.*;

/**
 * Pure assignment policy: distribute symbols across workers as evenly as
 * possible, deterministically. Stateless and side-effect-free so it unit-tests
 * without ZK or Spring; the Coordinator wraps it in I/O.
 *
 * "Even split" means round-robin dealing across sorted-by-id workers —
 * assignments differ in size by at most one, and the mapping is a
 * deterministic function of the inputs.
 */
public final class EvenSplitStrategy {

    /** Utility class — not instantiable. */
    private EvenSplitStrategy() {}

    /**
     * Distribute {@code symbols} across {@code workers} evenly. Inputs are
     * sorted, so output is deterministic regardless of iteration order.
     *
     * @param workers worker ids eligible for assignment (leader excluded by caller)
     * @param symbols symbols to assign
     * @return map from worker id to assigned symbols; every worker gets an
     *         entry (possibly empty). Empty map if {@code workers} is empty.
     */
    public static Map<String, List<String>> split(Collection<String> workers, Collection<String> symbols) {
        List<String> sortedWorkers = new ArrayList<>(new TreeSet<>(workers));
        List<String> sortedSymbols = new ArrayList<>(new TreeSet<>(symbols));

        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String w : sortedWorkers) {
            out.put(w, new ArrayList<>());
        }
        if (sortedWorkers.isEmpty() || sortedSymbols.isEmpty()) {
            return out;
        }
        for (int i = 0; i < sortedSymbols.size(); i++) {
            String worker = sortedWorkers.get(i % sortedWorkers.size());
            out.get(worker).add(sortedSymbols.get(i));
        }
        return out;
    }

    /**
     * Identify workers whose assignment is identical between two snapshots.
     * The Coordinator uses this to skip writing znodes whose contents didn't
     * change, avoiding spurious watch fires on every worker.
     *
     * @param previous prior assignments (missing workers = no symbols)
     * @param next     new assignments
     * @return worker ids whose list did not change
     */
    public static Set<String> unchangedWorkers(Map<String, List<String>> previous, Map<String, List<String>> next) {
        Set<String> unchanged = new TreeSet<>();
        for (Map.Entry<String, List<String>> e : next.entrySet()) {
            List<String> prev = previous.getOrDefault(e.getKey(), Collections.emptyList());
            if (prev.equals(e.getValue())) {
                unchanged.add(e.getKey());
            }
        }
        return unchanged;
    }
}
