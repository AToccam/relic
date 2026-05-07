package com.relic.rag.embedding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Embedding 抽象接口。
 *
 * <p>用于将文本转换为向量，向量维度由具体实现决定。
 */
public interface EmbeddingProvider {

    /**
     * @return provider 唯一名称，用于日志与配置识别
     */
    String getName();

    /**
     * @return 当前 provider 输出的向量维度
     */
    int dimensions();

    /**
     * 将单条文本编码为向量。
     */
    List<Double> embed(String text);

    /**
     * 批量向量化默认实现，逐条调用 {@link #embed(String)}。
     */
    default List<List<Double>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        List<List<Double>> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embed(text));
        }
        return vectors;
    }
}
