package com.kzai.aigc.recommendation;

import com.fasterxml.jackson.databind.*;
import com.kzai.aigc.config.AiProperties;
import com.kzai.aigc.knowledge.embedding.KnowledgeEmbeddingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 课程专用索引，模型/提供方/维度均参与命名。永不访问 knowledge 别名。 */
@Component
public class RecommendationIndex {
    public record Space(String name, int dimensions, String provider, String model, boolean mock) { }
    public record Hit(String courseId, double score) { }
    private final RestClient http;
    private final ObjectMapper json;
    private final KnowledgeEmbeddingService embedding;
    private final String prefix;
    private final String model;
    private volatile Space space;

    @org.springframework.beans.factory.annotation.Autowired
    public RecommendationIndex(AiProperties props, ObjectMapper json,
            RecommendationConfiguration.RecommendationEmbedding embedding,
            @Value("${kzai.aigc.recommendation-index-prefix:kzai-course-recommend}") String prefix) {
        this(props,json,embedding.service(),prefix,embedding.model());
    }
    public RecommendationIndex(AiProperties props, ObjectMapper json, KnowledgeEmbeddingService embedding,
            String prefix, String model) {
        if (!prefix.matches("kzai-course-recommend(?:-[a-z0-9-]+)?"))
            throw new IllegalArgumentException("课程推荐索引必须使用独立 kzai-course-recommend 前缀");
        var rf = new SimpleClientHttpRequestFactory(); rf.setConnectTimeout(1000); rf.setReadTimeout(2500);
        this.http = RestClient.builder().baseUrl(props.getKnowledgeEsUrl()).requestFactory(rf).build();
        this.json = json; this.embedding = embedding; this.prefix = prefix; this.model = model;
    }
    public boolean mock() { return embedding.mock(); }
    public Space space() {
        Space current=space;
        if(current!=null)return current;
        // 网络调用不持锁；前台查询直接用自己的查询向量创建命名空间，冷启动不多发一次请求。
        return spaceFor(embedding.embedQuery("课程检索索引维度校验").length);
    }
    private synchronized Space spaceFor(int dims) {
        if (space == null) {
            if (dims < 1 || dims > 4096) throw new IllegalStateException("非法向量维度");
            String key = embedding.provider()+"|"+model+"|"+dims+"|public-course-v1";
            space = new Space(prefix+"-"+hash(key).substring(0,16), dims, embedding.provider(), model, embedding.mock());
        }
        if(space.dimensions()!=dims)throw new IllegalStateException("Embedding 维度改变，请重建课程索引");
        return space;
    }
    public void ensure(Space s) {
        try {
            JsonNode mapping = http.get().uri("/"+s.name()+"/_mapping").retrieve().body(JsonNode.class);
            if (mapping == null || mapping.path(s.name()).path("mappings").path("properties").path("vector").path("dims").asInt() != s.dimensions())
                throw new IllegalStateException("课程索引维度不兼容，必须使用新命名空间重建");
        } catch (HttpClientErrorException.NotFound absent) {
            Map<String,Object> fields = new LinkedHashMap<>();
            fields.put("courseId", Map.of("type","keyword")); fields.put("visible",Map.of("type","boolean"));
            fields.put("text",Map.of("type","text"));
            fields.put("vector",Map.of("type","dense_vector","dims",s.dimensions(),"index",true,"similarity","cosine"));
            try { http.put().uri("/"+s.name()).body(Map.of("mappings",Map.of("dynamic",false,"properties",fields)))
                    .retrieve().toBodilessEntity(); }
            catch (HttpClientErrorException.BadRequest raced) {
                // 并发创建时也必须检查真实 mapping，不能吞掉其他 400。
                JsonNode m = http.get().uri("/"+s.name()+"/_mapping").retrieve().body(JsonNode.class);
                if (m == null || m.path(s.name()).path("mappings").path("properties").path("vector").path("dims").asInt()!=s.dimensions()) throw raced;
            }
        }
    }
    /** external 严格版本：旧任务/重复重试无法覆盖新版本。不可见文档保留墓碑，避免 delete GC 后复活。 */
    public void write(Space s, long id, long revision, Map<String,Object> source) {
        Map<String,Object> doc = new LinkedHashMap<>();
        doc.put("courseId",String.valueOf(id)); doc.put("visible",Boolean.TRUE.equals(source.get("visible")));
        if (Boolean.TRUE.equals(source.get("visible"))) {
            String text = sourceText(source);
            float[] vector = embedding.embed(text);
            if (vector.length != s.dimensions()) throw new IllegalStateException("Embedding 维度改变，请重建课程索引");
            doc.put("text",text); doc.put("vector",vector);
        }
        try { http.put().uri("/"+s.name()+"/_doc/"+id+"?version="+revision+"&version_type=external&refresh=wait_for")
                .body(doc).retrieve().toBodilessEntity(); }
        catch (HttpClientErrorException.Conflict staleOrRepeated) { /* 已有同版/更新版，幂等成功 */ }
    }
    public List<Hit> search(String query, int limit, double minScore) {
        float[] vector=embedding.embedQuery(query); Space s=spaceFor(vector.length);
        if (vector.length!=s.dimensions()) throw new IllegalStateException("查询向量与课程索引维度不兼容");
        JsonNode result=http.post().uri("/"+s.name()+"/_search").body(Map.of("size",limit,
                "_source",List.of("courseId"),"knn",Map.of("field","vector","query_vector",vector,
                "k",limit,"num_candidates",Math.max(100,limit*5),"filter",Map.of("term",Map.of("visible",true)))))
                .retrieve().body(JsonNode.class);
        List<Hit> out=new ArrayList<>();
        if(result!=null) for(JsonNode h:result.path("hits").path("hits")) {
            if(h.path("_score").asDouble()>=minScore) out.add(new Hit(h.path("_source").path("courseId").asText(),h.path("_score").asDouble()));
        }
        return out;
    }
    public static String sourceText(Map<String,Object> source) {
        Object raw=source.get("course");
        if (!(raw instanceof Map<?,?> c)) return "";
        // 明确白名单；不含附件、章节正文、内部路径或用户记录。
        StringBuilder text=new StringBuilder();
        for(String key:List.of("name","introduce","usePeople","chapters")) {
            if(c.get(key)!=null) text.append(key).append(": ").append(c.get(key)).append('\n');
        }
        return text.substring(0,Math.min(text.length(),6000));
    }
    public static String hash(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
