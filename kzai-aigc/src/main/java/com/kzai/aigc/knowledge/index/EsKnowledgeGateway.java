package com.kzai.aigc.knowledge.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kzai.aigc.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch 8 HTTP 网关（M9，设计说明书 3.4：aigc → Elasticsearch HTTP）。
 * <p>
 * 版本化索引 + 别名原子切换（原始约束「重建索引期间旧索引继续可用」）：
 * <pre>
 * 物理索引：kzai-knowledge-v{N}（N 为索引版本，维度来自真实 Embedding）
 * 别名：    kzai-knowledge-alias（读写均走别名；切换 = 原子 remove+add 一次请求）
 * </pre>
 * 切片文档字段：docId/chunkId(=docId_chunkIndex)/chunkIndex/indexVersion/docTitle/
 * courseId/accessLevel/reviewStatus/text/vector(dense_vector, cosine)。
 * 归一化相似度口径：similarity=cosine 时 ES _score = (1 + cosine) / 2 ∈ [0,1]，
 * 阈值 0.6 直接与 _score 比较（等价 cosine ≥ 0.2），写入与查询同口径。
 */
@Slf4j
@Component
public class EsKnowledgeGateway {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String alias;
    private final String indexPrefix;

    public EsKnowledgeGateway(AiProperties props, ObjectMapper objectMapper) {
        // M15 REAL 受控故障注入发现：ES 挂起（无响应）时无超时保护会导致对话请求挂死
        // （90s 无降级）。加连接/读取超时，ES 故障快速失败 → Retriever degraded（fail-closed）。
        var rf = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(2_000);
        rf.setReadTimeout(5_000);
        this.restClient = RestClient.builder()
                .baseUrl(props.getKnowledgeEsUrl())
                .requestFactory(rf)
                .build();
        this.objectMapper = objectMapper;
        this.alias = props.getKnowledgeIndexAlias();
        this.indexPrefix = props.getKnowledgeIndexPrefix();
    }

    // ------------------------------------------------------------------
    // 索引与别名
    // ------------------------------------------------------------------

    /** 创建版本化物理索引（已存在视为成功——幂等；维度 = 真实 Embedding 返回维度） */
    public boolean createVersionedIndex(int version, int dimensions) {
        String index = physicalIndex(version);
        Map<String, Object> mapping = buildMapping(dimensions);
        try {
            return restClient.put()
                    .uri("/{index}", index)
                    .header("Content-Type", "application/json")
                    .body(utf8(writeJson(mapping)))
                    .retrieve()
                    .toEntity(String.class)
                    .getStatusCode().is2xxSuccessful();
        } catch (org.springframework.web.client.HttpClientErrorException.Conflict ex) {
            return true; // resource_already_exists（409）：幂等
        } catch (org.springframework.web.client.HttpClientErrorException.BadRequest ex) {
            // ES 8 对 PUT 已存在索引返回 400 resource_already_exists——同样视为幂等成功
            if (String.valueOf(ex.getResponseBodyAsString()).contains("resource_already_exists")) {
                return true;
            }
            log.warn("[knowledge.es] 创建索引失败 {}: {}", physicalIndex(version), ex.getMessage());
            return false;
        } catch (RuntimeException ex) {
            log.warn("[knowledge.es] 创建索引失败 {}: {}", index, ex.getMessage());
            return false;
        }
    }

    /** 别名当前指向的物理索引（无别名返回 null） */
    public String currentAliasedIndex() {
        try {
            String body = restClient.get()
                    .uri("/_alias/{alias}", alias)
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                return null;
            }
            JsonNode node = objectMapper.readTree(body);
            java.util.Iterator<String> it = node.fieldNames();
            return it.hasNext() ? it.next() : null;
        } catch (RuntimeException ex) {
            log.warn("[knowledge.es] 查询别名失败: {}", ex.getMessage());
            return null;
        } catch (Exception ex) {
            log.warn("[knowledge.es] 别名解析失败: {}", ex.getMessage());
            return null;
        }
    }

    /** 原子切换别名到新索引（单次 POST；remove/add 为两个独立动作条目） */
    public boolean swapAlias(int newVersion, String oldIndex) {
        List<Map<String, Object>> actions = new ArrayList<>();
        if (oldIndex != null) {
            actions.add(Map.of("remove", Map.of("index", oldIndex, "alias", alias)));
        }
        actions.add(Map.of("add", Map.of("index", physicalIndex(newVersion), "alias", alias)));
        Map<String, Object> body = Map.of("actions", actions);
        try {
            Boolean ok = restClient.post()
                    .uri("/_aliases")
                    .header("Content-Type", "application/json")
                    .body(utf8(writeJson(body)))
                    .retrieve()
                    .toEntity(String.class)
                    .getStatusCode().is2xxSuccessful();
            return Boolean.TRUE.equals(ok);
        } catch (RuntimeException ex) {
            log.warn("[knowledge.es] 别名切换失败: {}", ex.getMessage());
            return false;
        }
    }

    /** 删除物理索引（清理旧版本用） */
    public boolean deletePhysicalIndex(int version) {
        try {
            restClient.delete().uri("/{index}", physicalIndex(version))
                    .retrieve().toEntity(String.class);
            return true;
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound ex) {
            return true;
        } catch (RuntimeException ex) {
            log.warn("[knowledge.es] 删除索引失败 v{}: {}", version, ex.getMessage());
            return false;
        }
    }

    /** 集群/别名可用性探测（降级状态依据） */
    public boolean available() {
        try {
            restClient.get().uri("/").retrieve().toEntity(String.class);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    /**
     * 批量写入切片（bulk；indexVersion 随文档携带，供版本过滤与旧索引清理）。
     * 返回成功写入条数；任何失败项计为失败（调用方据实落库，不虚报成功）。
     */
    public int bulkIndex(int version, List<Map<String, Object>> chunkDocs) {
        if (chunkDocs == null || chunkDocs.isEmpty()) {
            return 0;
        }
        StringBuilder ndjson = new StringBuilder();
        for (Map<String, Object> doc : chunkDocs) {
            String docId = String.valueOf(doc.get("chunkId"));
            ndjson.append("{\"index\":{\"_index\":\"").append(physicalIndex(version))
                    .append("\",\"_id\":\"").append(docId).append("\"}}\n");
            ndjson.append(writeJson(doc)).append('\n');
        }
        try {
            String resp = restClient.post()
                    .uri("/_bulk?refresh=wait_for")
                    .header("Content-Type", "application/x-ndjson")
                    .body(utf8(ndjson.toString()))
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(resp);
            int errors = node.path("errors").asBoolean(false) ? 1 : 0;
            if (errors == 1) {
                // 记录首个失败项的具体原因（维度不匹配/映射冲突等），避免只报数量
                for (JsonNode item : node.path("items")) {
                    JsonNode err = item.path("index").path("error");
                    if (!err.isMissingNode() && !err.isNull()) {
                        log.warn("[knowledge.es] bulk 失败项: id={} type={} reason={}",
                                item.path("index").path("_id").asText(),
                                err.path("type").asText(),
                                err.path("reason").asText(""));
                        // 打印对应原始写入行（元数据行的下一行为文档体；截断防日志爆炸）
                        String failedId = item.path("index").path("_id").asText();
                        String[] lines = ndjson.toString().split("\n");
                        for (int i = 0; i < lines.length - 1; i++) {
                            if (lines[i].contains(failedId)) {
                                log.warn("[knowledge.es] 失败项文档体: {}", truncateForLog(lines[i + 1], 500));
                                break;
                            }
                        }
                        break;
                    }
                }
                log.warn("[knowledge.es] bulk 存在失败项");
            }
            return node.path("items").size() - countFailedItems(node);
        } catch (RuntimeException ex) {
            log.warn("[knowledge.es] bulk 写入失败: {}", ex.getMessage());
            return -1;
        } catch (Exception ex) {
            log.warn("[knowledge.es] bulk 响应解析失败: {}", ex.getMessage());
            return -1;
        }
    }

    /** 按文档删除全部切片（delete_by_query，refresh 立即可见） */
    public long deleteByDocId(long docId) {
        Map<String, Object> query = Map.of("term", Map.of("docId", docId));
        return deleteByQuery(query);
    }

    /**
     * 统计某文档<b>指定发布版本</b>的切片数（M11 重建原子性核验：新版本写全后才切换发布版本）。
     */
    public long countByDocIdVersion(long docId, int indexVersion) {
        try {
            String resp = restClient.post()
                    .uri("/{alias}/_count", alias)
                    .header("Content-Type", "application/json")
                    .body(utf8(writeJson(Map.of("query", Map.of("bool", Map.of("filter", List.of(
                            Map.of("term", Map.of("docId", docId)),
                            Map.of("term", Map.of("indexVersion", indexVersion)))))))))
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(resp);
            return node.path("count").asLong(-1);
        } catch (RuntimeException ex) {
            log.warn("[knowledge.es] countByDocIdVersion 失败: {}", ex.getMessage());
            return -1;
        } catch (Exception ex) {
            log.warn("[knowledge.es] countByDocIdVersion 响应解析失败: {}", ex.getMessage());
            return -1;
        }
    }

    /**
     * 删除该文档<b>非指定发布版本</b>的切片（M11）：
     * <ul>
     *   <li>重建开始前调用：清理上次中断留下的未发布孤儿切片；</li>
     *   <li>发布切换后调用：删除旧版本切片（发布动作之后才允许破坏旧版本）。</li>
     * </ul>
     * 失败返回 -1（调用方必须视为失败而非成功）。
     */
    public long deleteByDocIdVersionNeq(long docId, int keepVersion) {
        Map<String, Object> query = Map.of("bool", Map.of("filter", List.of(
                Map.of("term", Map.of("docId", docId)),
                Map.of("bool", Map.of("must_not", List.of(
                        Map.of("term", Map.of("indexVersion", keepVersion))))))));
        return deleteByQuery(query);
    }

    /**
     * 逐文档重建收尾：删除该文档 chunkIndex ≥ min 的残留旧切片
     * （新切片按 _id=chunkId 覆盖写后，若新切片数变少，多余的旧切片由此清理）。
     */
    public long deleteByDocIdChunkIndexGte(long docId, int minChunkIndex) {
        Map<String, Object> query = Map.of("bool", Map.of("filter", List.of(
                Map.of("term", Map.of("docId", docId)),
                Map.of("range", Map.of("chunkIndex", Map.of("gte", minChunkIndex))))));
        return deleteByQuery(query);
    }

    /** 统计某文档当前可见切片数（_count；失败返回 -1） */
    public long countByDocId(long docId) {
        try {
            String resp = restClient.post()
                    .uri("/{alias}/_count", alias)
                    .header("Content-Type", "application/json")
                    .body(utf8(writeJson(Map.of("query", Map.of("term", Map.of("docId", docId))))))
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(resp);
            return node.path("count").asLong(-1);
        } catch (RuntimeException ex) {
            log.warn("[knowledge.es] count 失败: {}", ex.getMessage());
            return -1;
        } catch (Exception ex) {
            log.warn("[knowledge.es] count 响应解析失败: {}", ex.getMessage());
            return -1;
        }
    }

    private long deleteByQuery(Map<String, Object> query) {
        try {
            String resp = restClient.post()
                    .uri("/{alias}/_delete_by_query?refresh=true&conflicts=proceed", alias)
                    .header("Content-Type", "application/json")
                    .body(utf8(writeJson(Map.of("query", query))))
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(resp);
            return node.path("deleted").asLong(0);
        } catch (RuntimeException ex) {
            log.warn("[knowledge.es] delete_by_query 失败: {}", ex.getMessage());
            return -1;
        } catch (Exception ex) {
            log.warn("[knowledge.es] delete_by_query 响应解析失败: {}", ex.getMessage());
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // 检索（knn）
    // ------------------------------------------------------------------

    /**
     * kNN 向量检索（带过滤条件）。
     *
     * @param filter accessLevel 白名单 / courseId 白名单 / reviewStatus=1 / indexVersion 白名单
     * @return 命中列表（_score 为 [0,1] 归一化相似度，可直接与阈值 0.6 比较）
     */
    public List<Map<String, Object>> knnSearch(float[] vector, int topK,
                                               Map<String, Object> filter) {
        return knnSearch(vector, topK, filter, null);
    }

    /**
     * kNN 向量检索（M12 补充：支持额外 bool.should 子句做<b>发布版本预过滤</b>）。
     * <p>
     * {@code versionPairs} 为 MySQL 权威 (docId, indexVersion) 白名单子句（每项形如
     * {@code {"bool":{"filter":[{"term":{"docId":1}},{"term":{"indexVersion":2}}]}}}），
     * 至少命中一项的切片才进入 kNN 候选窗口——未发布/过期版本的切片<b>不占用</b>
     * TopK 候选位，消除「无效版本挤占有效结果」造成的漏召回。null 表示不过滤（回退旧口径）。
     */
    public List<Map<String, Object>> knnSearch(float[] vector, int topK,
                                               Map<String, Object> filter,
                                               List<Map<String, Object>> versionPairs) {
        Map<String, Object> bool = new LinkedHashMap<>();
        List<Map<String, Object>> must = new ArrayList<>();
        filter.forEach((field, value) -> {
            if (value instanceof List<?> list) {
                must.add(Map.of("terms", Map.of(field, list)));
            } else {
                must.add(Map.of("term", Map.of(field, value)));
            }
        });
        bool.put("filter", must);
        if (versionPairs != null && !versionPairs.isEmpty()) {
            bool.put("should", versionPairs);
            bool.put("minimum_should_match", 1);
        }
        Map<String, Object> knn = new LinkedHashMap<>();
        knn.put("field", "vector");
        knn.put("query_vector", toList(vector));
        knn.put("k", topK);
        knn.put("num_candidates", Math.max(topK * 5, 50));
        knn.put("filter", Map.of("bool", bool));
        Map<String, Object> body = Map.of("knn", knn, "size", topK);
        try {
            String resp = restClient.post()
                    .uri("/{alias}/_search", alias)
                    .header("Content-Type", "application/json")
                    .body(utf8(writeJson(body)))
                    .retrieve()
                    .body(String.class);
            return parseHits(resp);
        } catch (RuntimeException ex) {
            log.warn("[knowledge.es] knn 检索失败: {}", ex.getMessage());
            return null; // null = ES 不可用（与空结果区分，调用方降级）
        } catch (Exception ex) {
            log.warn("[knowledge.es] 检索响应解析失败: {}", ex.getMessage());
            return null;
        }
    }

    private List<Map<String, Object>> parseHits(String resp) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        JsonNode hits = objectMapper.readTree(resp).path("hits").path("hits");
        for (JsonNode hit : hits) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("score", hit.path("_score").asDouble(0));
            JsonNode src = hit.path("_source");
            m.put("docId", src.path("docId").asText(""));
            m.put("chunkId", src.path("chunkId").asText(""));
            m.put("chunkIndex", src.path("chunkIndex").asInt(-1));
            // M11：切片发布版本随命中返回，供检索侧按 MySQL 权威版本过滤未发布切片
            m.put("indexVersion", src.path("indexVersion").isNumber()
                    ? src.path("indexVersion").asInt() : null);
            m.put("docTitle", src.path("docTitle").asText(""));
            m.put("courseId", src.path("courseId") == null || src.path("courseId").isNull()
                    ? null : src.path("courseId").asText());
            m.put("accessLevel", src.path("accessLevel").asText(""));
            m.put("text", src.path("text").asText(""));
            out.add(m);
        }
        return out;
    }

    private static List<Float> toList(float[] vector) {
        List<Float> out = new ArrayList<>(vector.length);
        for (float v : vector) {
            out.add(v);
        }
        return out;
    }

    /** UTF-8 字节 body（修复 StringHttpMessageConverter 默认 ISO-8859-1 损坏中文：M12 验收发现的 M9 既有缺陷） */
    private static byte[] utf8(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String truncateForLog(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…(" + s.length() + ")";
    }

    private int countFailedItems(JsonNode node) {
        int failed = 0;
        for (JsonNode item : node.path("items")) {
            if (item.path("index").path("status").asInt(500) >= 300) {
                failed++;
            }
        }
        return failed;
    }

    private Map<String, Object> buildMapping(int dimensions) {
        Map<String, Object> vector = new LinkedHashMap<>();
        vector.put("type", "dense_vector");
        vector.put("dims", dimensions);
        vector.put("index", true);
        vector.put("similarity", "cosine");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("docId", Map.of("type", "long"));
        properties.put("chunkId", Map.of("type", "keyword"));
        properties.put("chunkIndex", Map.of("type", "integer"));
        properties.put("indexVersion", Map.of("type", "integer"));
        properties.put("docTitle", Map.of("type", "keyword"));
        properties.put("courseId", Map.of("type", "long"));
        properties.put("accessLevel", Map.of("type", "keyword"));
        properties.put("reviewStatus", Map.of("type", "integer"));
        properties.put("text", Map.of("type", "text"));
        properties.put("vector", vector);
        return Map.of("settings", Map.of("number_of_shards", 1, "number_of_replicas", 0),
                "mappings", Map.of("properties", properties));
    }

    private String physicalIndex(int version) {
        return indexPrefix + "-v" + version;
    }

    /**
     * M15 REAL：读取指定索引 mapping 中 vector 字段的 dims。
     * 供 RealIndexGuard 校验「向量维度与索引匹配」（null=读取失败或字段缺失）。
     */
    public Integer vectorDimsOf(String index) {
        try {
            String resp = restClient.get()
                    .uri("/{index}/_mapping", index)
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(resp);
            JsonNode props = node.path(index).path("mappings").path("properties").path("vector");
            return props.isMissingNode() ? null : (props.path("dims").isInt() ? props.path("dims").asInt() : null);
        } catch (Exception ex) {
            log.warn("[knowledge.es] 读取索引 mapping 失败 index={}: {}", index, ex.getMessage());
            return null;
        }
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            throw new IllegalStateException("JSON 序列化失败", ex);
        }
    }
}
