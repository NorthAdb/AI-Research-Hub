package com.aireshub.ai;

/**
 * 嵌入服务(路线图 原则 5:可替换接口)。
 *
 * <p>默认实现 {@link LocalHashEmbeddingService} 是**演示级**的词面哈希向量
 * (不含真实语义);配置 app.embedding.provider=openai 后切换为
 * OpenAI 兼容 API(DeepSeek/OpenAI/硅基流动等),业务代码零改动。</p>
 */
public interface EmbeddingService {

    float[] embed(String text);

    int dimensions();
}
