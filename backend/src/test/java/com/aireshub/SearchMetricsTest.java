package com.aireshub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.aireshub.search.SearchMetrics;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SearchMetricsTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @Test
    void averagesRecallAndReciprocalRank() {
        SearchMetrics.Summary summary = SearchMetrics.score(List.of(
                new SearchMetrics.QueryCase("命中第一", List.of(A), List.of(A, B)),
                new SearchMetrics.QueryCase("排在第三", List.of(B), List.of(C, A, B)),
                new SearchMetrics.QueryCase("没命中", List.of(A), List.of(C)),
                new SearchMetrics.QueryCase("两篇里中一篇", List.of(A, B), List.of(B, C))));

        assertEquals(4, summary.queries());
        assertEquals(3, summary.hits());
        assertEquals(0.625, summary.recallAtK());
        assertEquals(0.5833, summary.mrr());
        assertEquals(1, summary.details().get(0).firstRank());
        assertEquals(3, summary.details().get(1).firstRank());
        assertNull(summary.details().get(2).firstRank());
        assertEquals(1, summary.details().get(3).found());
    }

    @Test
    void emptySetIsZero() {
        SearchMetrics.Summary summary = SearchMetrics.score(List.of());
        assertEquals(0, summary.queries());
        assertEquals(0, summary.recallAtK());
    }
}
