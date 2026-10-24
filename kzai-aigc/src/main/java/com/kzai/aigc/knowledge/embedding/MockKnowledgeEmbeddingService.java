package com.kzai.aigc.knowledge.embedding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Mock 向量化（M9，显著标识）：确定性哈希投影向量，维度固定 256。
 * 仅用于 ES 索引/检索协议链路测试——相同文本得到相同向量、不同文本向量不同，
 * 但<b>不含语义信息</b>，不能计为真实语义检索或真实 RAG 验收。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "kzai.aigc", name = "embedding-provider",
        havingValue = "mock", matchIfMissing = true)
public class MockKnowledgeEmbeddingService implements KnowledgeEmbeddingService {

    public static final int DIMENSIONS = 256;

    @Override
    public String provider() {
        return "MOCK";
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("embedding 入参文本为空");
        }
        float[] vec = new float[DIMENSIONS];
        // 多轮 SHA-256 哈希折叠到固定维度：确定性、分布尚可、无语义
        int seedRound = 0;
        int filled = 0;
        while (filled < DIMENSIONS) {
            byte[] digest = sha256(text + "#" + (seedRound++));
            for (int i = 0; i < digest.length && filled < DIMENSIONS; i += 4) {
                int word = ((digest[i] & 0xFF) << 24) | ((digest[i + 1] & 0xFF) << 16)
                        | ((digest[i + 2] & 0xFF) << 8) | (digest[i + 3] & 0xFF);
                vec[filled++] = (word / (float) Integer.MAX_VALUE);
            }
        }
        return normalize(vec);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String t : texts) {
            out.add(embed(t));
        }
        return out;
    }

    @Override
    public boolean mock() {
        return true;
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /** L2 归一化：配合 ES cosine 相似度得到 [0,1] 归一化口径 */
    private static float[] normalize(float[] vec) {
        double sum = 0;
        for (float v : vec) {
            sum += (double) v * v;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0) {
            return vec;
        }
        float[] out = new float[vec.length];
        for (int i = 0; i < vec.length; i++) {
            out[i] = (float) (vec[i] / norm);
        }
        return out;
    }
}
