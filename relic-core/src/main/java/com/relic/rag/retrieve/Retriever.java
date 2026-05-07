package com.relic.rag.retrieve;

import com.relic.rag.model.RetrievalResult;

import java.util.List;
import java.util.Set;

/**
 * 检索抽象接口。
 *
 * <p>用于将用户 query 映射到相关文档分块列表，供后续 prompt 注入。
 */
public interface Retriever {

    /**
     * 执行检索。
     *
     * @param query 用户查询
     * @param topK 返回条数
     * @param sourceIds 限定检索来源；为空时表示全量检索
     */
    List<RetrievalResult> retrieve(String query, int topK, Set<String> sourceIds);
}
