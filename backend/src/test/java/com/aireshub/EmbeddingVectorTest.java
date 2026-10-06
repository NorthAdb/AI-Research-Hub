package com.aireshub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aireshub.ai.OpenAiCompatibleEmbeddingService;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmbeddingVectorTest {

    @Test
    void acceptsMatchingDimension() {
        float[] vector = OpenAiCompatibleEmbeddingService.toVector(List.of(0.25, 1, -0.5), 3);
        assertEquals(3, vector.length);
        assertEquals(0.25f, vector[0]);
        assertEquals(1f, vector[1]);
    }

    @Test
    void rejectsDimensionMismatch() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> OpenAiCompatibleEmbeddingService.toVector(List.of(0.1, 0.2), 768));
        assertEquals(true, ex.getMessage().contains("vector(768)"));
    }
}
