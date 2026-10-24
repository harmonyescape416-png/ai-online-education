package com.kzai.aigc.recommendation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kzai.aigc.config.AiProperties;
import com.kzai.aigc.knowledge.embedding.KnowledgeEmbeddingService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 ES 协议测试；固定向量不证明语义质量。仅创建/删除 UUID 独立索引。 */
@EnabledIfEnvironmentVariable(named="KZAI_ES_TEST",matches="true")
class RecommendationEsTest {
    final RestClient http=RestClient.create("http://127.0.0.1:9200");
    final List<String> created=new ArrayList<>();
    KnowledgeEmbeddingService embedding=new KnowledgeEmbeddingService(){
        public String provider(){return "MOCK";}public boolean mock(){return true;}public int dimensions(){return 2;}
        public float[] embed(String text){return new float[]{1,0};} public List<float[]> embedAll(List<String> t){return t.stream().map(this::embed).toList();}
    };
    RecommendationIndex build(String prefix,String model) {
        var index=new RecommendationIndex(new AiProperties(),new ObjectMapper(),embedding,prefix,model);
        created.add(index.space().name());index.ensure(index.space());return index;
    }
    @AfterEach void cleanup(){for(String name:created)http.delete().uri("/"+name).retrieve().toBodilessEntity();}
    @Test void oldWritesCannotReviveTombstoneAndModelNamespacesAreIsolated() {
        String prefix="kzai-course-recommend-it-"+UUID.randomUUID().toString().replace("-","");
        var first=build(prefix,"model-a");var second=build(prefix,"model-b");
        assertNotEquals(first.space().name(),second.space().name());
        var source=Map.<String,Object>of("visible",true,"course",Map.of("name","public fixture"));
        first.write(first.space(),11,1,source);assertEquals(1,first.search("fixture",5,0).size());
        first.write(first.space(),11,3,Map.of("visible",false));
        first.write(first.space(),11,1,source);first.write(first.space(),11,3,source);
        assertTrue(first.search("fixture",5,0).isEmpty());assertTrue(second.search("fixture",5,0).isEmpty());
    }
}
