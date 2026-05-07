package com.relic.rag.embedding;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 一期默认 Embedding 实现：Hashing Vector。
 *
 * <p>该实现不依赖外部模型服务，目标是低成本打通 RAG 链路。
 * 后续可通过 EmbeddingProvider 接口替换为真实 embedding API。
 */
@Slf4j
public class HashingEmbeddingProvider implements EmbeddingProvider {

    @Value("${relic.rag.embedding.dimensions:256}")
    private int dimensions;

    @PostConstruct
    public void init() {
        if (dimensions < 64) {
            dimensions = 64;
        }
        log.info("【RAG】EmbeddingProvider 已启用: {}, dimensions={}", getName(), dimensions);
    }

    @Override
    public String getName() {
        return "hashing";
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public List<Double> embed(String text) {
        // 使用简单哈希桶聚合 token 频次，并做 L2 归一化。
        double[] buckets = new double[dimensions];
        if (text == null || text.isBlank()) {
            return toList(buckets);
        }

        String normalized = text.toLowerCase(Locale.ROOT);
        String[] tokens = normalized.split("[^\\p{IsAlphabetic}\\p{IsDigit}]+");
        int tokenCount = 0;
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            int idx = Math.floorMod(token.hashCode(), dimensions);
            buckets[idx] += 1.0;
            tokenCount++;
        }

        // 对极短文本补充字符级信号，避免全部分到空向量。
        if (tokenCount == 0) {
            for (char c : normalized.toCharArray()) {
                int idx = Math.floorMod(Character.hashCode(c), dimensions);
                buckets[idx] += 1.0;
            }
        }

        normalizeInPlace(buckets);
        return toList(buckets);
    }

    private void normalizeInPlace(double[] vector) {
        double norm = 0;
        for (double v : vector) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        if (norm <= 0) {
            return;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / norm;
        }
    }

    private List<Double> toList(double[] values) {
        List<Double> list = new ArrayList<>(values.length);
        for (double value : values) {
            list.add(value);
        }
        return list;
    }
}
