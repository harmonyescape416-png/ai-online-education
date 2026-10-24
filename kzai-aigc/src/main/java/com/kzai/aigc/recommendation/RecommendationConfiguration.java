package com.kzai.aigc.recommendation;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.beans.factory.annotation.Value;
import com.kzai.aigc.knowledge.embedding.*;
@Configuration
public class RecommendationConfiguration {
    /** 不注册第二个 KnowledgeEmbeddingService bean，避免影响教师资料知识库的注入/模型。 */
    public record RecommendationEmbedding(KnowledgeEmbeddingService service, String model) { }
    @Bean
    public RecommendationEmbedding recommendationEmbedding(
            @Value("${kzai.aigc.recommendation-embedding-provider:mock}") String provider,
            @Value("${kzai.aigc.recommendation-embedding-model:text-embedding-v4}") String model,
            @Value("${spring.ai.dashscope.chat.api-key:}") String key,
            @Value("${spring.ai.dashscope.chat.base-url:https://dashscope.aliyuncs.com}") String baseUrl) {
        KnowledgeEmbeddingService service = switch(provider.toLowerCase(java.util.Locale.ROOT)) {
            case "mock" -> new MockKnowledgeEmbeddingService();
            case "dashscope" -> new DashScopeKnowledgeEmbeddingService(key,baseUrl,model);
            default -> throw new IllegalArgumentException("未知课程推荐 Embedding 提供方");
        };
        return new RecommendationEmbedding(service, model);
    }
    @Bean(name="recommendationScheduler")
    public ThreadPoolTaskScheduler scheduler() {
        var pool=new ThreadPoolTaskScheduler();pool.setPoolSize(2);pool.setThreadNamePrefix("course-recommend-");
        pool.setWaitForTasksToCompleteOnShutdown(false);return pool;
    }
}
