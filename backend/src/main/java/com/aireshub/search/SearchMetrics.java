package com.aireshub.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 检索评测指标。只做计算，不访问数据库。
 * Recall@K 按问题平均：命中的标准文档数 / 该问题的标准文档数。
 * MRR 是第一个命中文档排名的倒数，没命中记 0。
 */
public final class SearchMetrics {

    public record QueryCase(String query, List<UUID> expected, List<UUID> retrieved) {
    }

    public record QueryScore(String query, int relevant, int found, Integer firstRank) {
    }

    public record Summary(int queries, int hits, double recallAtK, double mrr, List<QueryScore> details) {
    }

    private SearchMetrics() {
    }

    public static Summary score(List<QueryCase> cases) {
        if (cases.isEmpty()) {
            return new Summary(0, 0, 0, 0, List.of());
        }
        double recallSum = 0;
        double mrrSum = 0;
        int hits = 0;
        List<QueryScore> details = new ArrayList<>();
        for (QueryCase one : cases) {
            Set<UUID> expected = new LinkedHashSet<>(one.expected());
            List<UUID> retrieved = dedupe(one.retrieved());
            int found = 0;
            Integer firstRank = null;
            for (int i = 0; i < retrieved.size(); i++) {
                if (expected.contains(retrieved.get(i))) {
                    found++;
                    if (firstRank == null) {
                        firstRank = i + 1;
                    }
                }
            }
            if (found > 0) {
                hits++;
            }
            recallSum += expected.isEmpty() ? 0 : (double) found / expected.size();
            mrrSum += firstRank == null ? 0 : 1.0 / firstRank;
            details.add(new QueryScore(one.query(), expected.size(), found, firstRank));
        }
        int n = cases.size();
        return new Summary(n, hits, round(recallSum / n), round(mrrSum / n), details);
    }

    private static List<UUID> dedupe(List<UUID> ids) {
        Set<UUID> seen = new LinkedHashSet<>();
        if (ids != null) {
            seen.addAll(ids);
        }
        return List.copyOf(seen);
    }

    private static double round(double value) {
        return Math.round(value * 10000) / 10000.0;
    }
}
