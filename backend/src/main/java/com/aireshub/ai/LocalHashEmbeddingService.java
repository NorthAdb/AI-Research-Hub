package com.aireshub.ai;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本地特征哈希嵌入(演示级):token → [0,dims) 桶 + 符号,L2 归一化。
 * 确定性、零外部依赖;相似度本质是词面重合度,非语义相似。
 * 真实语义请切换 openai 兼容 API(见接口注释)。
 */
@Component
@ConditionalOnProperty(name = "app.embedding.provider", havingValue = "local", matchIfMissing = true)
public class LocalHashEmbeddingService implements EmbeddingService {

    private final int dimensions;

    public LocalHashEmbeddingService(@Value("${app.embedding.dims:768}") int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[dimensions];
        List<String> tokens = TextTokenizer.tokenize(text);
        for (String token : tokens) {
            int h = token.hashCode();
            h ^= (h >>> 16);
            int index = Math.floorMod(h * 0x9E3779B9, dimensions);
            float sign = (h & 1) == 0 ? 1f : -1f;
            vector[index] += sign;
        }
        float norm = 0f;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dimensions; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }
}
