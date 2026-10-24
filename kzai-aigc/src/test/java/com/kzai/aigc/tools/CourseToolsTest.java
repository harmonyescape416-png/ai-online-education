package com.kzai.aigc.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kzai.aigc.client.CourseAiItem;
import com.kzai.aigc.client.CourseClient;
import com.kzai.common.result.R;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

/** 课程工具单元测试（M7）：真实契约来自 Feign 桩；失败不冒充成功。 */
class CourseToolsTest {

    private CourseClient courseClient;
    private CourseTools tools;
    private ToolResultHolder round;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        courseClient = Mockito.mock(CourseClient.class);
        tools = new CourseTools(courseClient, new ToolAudit(), new ObjectMapper());
        round = new ToolResultHolder(3L, "s1", "req-1");
        ctx = new ToolContext(Map.of(
                ToolContextKeys.USER_ID, 3L,
                ToolContextKeys.ROLES, List.of("STUDENT"),
                ToolContextKeys.REQUEST_ID, "req-1",
                ToolContextKeys.SESSION_ID, "s1",
                ToolContextKeys.ROUND, round));
    }

    private static CourseAiItem course(String id, String name, int priceCents, boolean purchasable) {
        CourseAiItem c = new CourseAiItem();
        c.setId(id);
        c.setName(name);
        c.setPrice(priceCents);
        c.setPriceYuan(String.format("%.2f", priceCents / 100.0));
        c.setChapterCount(8L);
        c.setCoverUrl("http://files/course.png");
        c.setPurchasable(purchasable);
        c.setReason(purchasable ? null : "课程未上架");
        return c;
    }

    @Test
    void callbacks_registered() {
        List<String> names = tools.callbacks().stream().map(cb -> cb.getName()).toList();
        assertTrue(names.contains("searchCourses"));
        assertTrue(names.contains("getCourseDetail"));
    }

    @Test
    void search_ok_emitsCardsAndModelSummary() {
        Mockito.when(courseClient.aiSearch(anyString(), any()))
                .thenReturn(R.ok(List.of(course("101", "Java 基础", 19900, true))));

        CourseSearchRequest req = new CourseSearchRequest();
        req.setKeyword("Java");
        String out = tools.searchCourses(req, ctx);

        JsonNode json = parse(out);
        assertEquals("OK", json.get("status").asText());
        assertEquals(1, json.withArray("courses").size());
        assertEquals("199.00", json.withArray("courses").get(0).get("priceYuan").asText());
        // 1003 卡片已入暂存，且字段与真实服务返回一致
        assertEquals(1, round.cards().size());
        Map<String, Object> card = round.cards().get(0);
        assertEquals("COURSE_CARD", card.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) card.get("payload");
        assertEquals("101", payload.get("courseId"));
        assertEquals(19900, payload.get("price"));
        assertEquals("199.00", payload.get("priceYuan"));
    }

    @Test
    void search_empty_tellsNotFound_noCard() {
        Mockito.when(courseClient.aiSearch(anyString(), any())).thenReturn(R.ok(List.of()));
        CourseSearchRequest req = new CourseSearchRequest();
        req.setKeyword("不存在的课");
        JsonNode json = parse(tools.searchCourses(req, ctx));
        assertEquals("NOT_FOUND", json.get("status").asText());
        assertTrue(round.cards().isEmpty(), "空结果不得伪造卡片");
    }

    @Test
    void search_serviceDown_controlledError() {
        Mockito.when(courseClient.aiSearch(anyString(), any()))
                .thenThrow(new RuntimeException("connect timeout"));
        CourseSearchRequest req = new CourseSearchRequest();
        req.setKeyword("Java");
        JsonNode json = parse(tools.searchCourses(req, ctx));
        assertEquals("COURSE_SERVICE_UNAVAILABLE", json.get("status").asText());
        assertTrue(round.cards().isEmpty(), "服务不可用不得发送假成功卡片");
    }

    @Test
    void search_blankKeyword_controlled() {
        CourseSearchRequest req = new CourseSearchRequest();
        req.setKeyword(" ");
        JsonNode json = parse(tools.searchCourses(req, ctx));
        assertEquals("EMPTY_KEYWORD", json.get("status").asText());
    }

    @Test
    void detail_notVisible_returnsNotFound() {
        Mockito.when(courseClient.aiDetail(anyLong())).thenReturn(R.ok(null));
        CourseDetailRequest req = new CourseDetailRequest();
        req.setCourseId("101");
        JsonNode json = parse(tools.getCourseDetail(req, ctx));
        assertEquals("NOT_FOUND", json.get("status").asText());
    }

    @Test
    void detail_badCourseId_rejected() {
        CourseDetailRequest req = new CourseDetailRequest();
        req.setCourseId("1; DROP TABLE course");
        JsonNode json = parse(tools.getCourseDetail(req, ctx));
        assertEquals("BAD_REQUEST", json.get("status").asText());
    }

    @Test
    void detail_ok_cardCarriesChapterCountAndIntroduce() {
        CourseAiItem c = course("102", "Spring Boot 实战", 29900, true);
        c.setIntroduce("从零到一掌握 Spring Boot");
        Mockito.when(courseClient.aiDetail(anyLong())).thenReturn(R.ok(c));

        CourseDetailRequest req = new CourseDetailRequest();
        req.setCourseId("102");
        JsonNode json = parse(tools.getCourseDetail(req, ctx));
        assertEquals("OK", json.get("status").asText());
        assertEquals(8L, json.get("chapterCount").asLong());
        // 卡片与工具结果一致（测试 B-12）
        assertEquals(1, round.cards().size());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) round.cards().get(0).get("payload");
        assertEquals("Spring Boot 实战", payload.get("name"));
        assertEquals(29900, payload.get("price"));
        assertEquals("299.00", payload.get("priceYuan"));
        // 只读验证：课程工具从未调用非查询接口
        Mockito.verify(courseClient, Mockito.only()).aiDetail(102L);
    }

    @Test
    void detail_withChapters_passesRealTitlesThrough() {
        // M15 REAL 防编造：真实章节标题透传（模型唯一合法的章节目录来源）
        CourseAiItem c = course("102", "Spring Boot 实战", 29900, true);
        c.setChapters(List.of(
                Map.of("name", "第一章 快速入门", "order", 1),
                Map.of("name", "第二章 Web 开发", "order", 2)));
        Mockito.when(courseClient.aiDetail(anyLong())).thenReturn(R.ok(c));

        CourseDetailRequest req = new CourseDetailRequest();
        req.setCourseId("102");
        JsonNode json = parse(tools.getCourseDetail(req, ctx));
        assertEquals("OK", json.get("status").asText());
        assertTrue(json.has("chapters"), "真实章节应透传给模型");
        assertEquals(2, json.get("chapters").size());
        assertEquals("第一章 快速入门", json.get("chapters").get(0).get("name").asText());
        assertTrue(json.get("note").asText().contains("chapters"));
    }

    @Test
    void detail_withoutChapters_modelMustNotInvent() {
        // M15 REAL 防编造：工具未返回章节标题时，模型只看得到 chapterCount——
        // 结果中不得出现 chapters 字段（约束模型不得自行编写章节目录）
        CourseAiItem c = course("102", "Spring Boot 实战", 29900, true);
        c.setChapters(null); // 课程服务未返回章节标题
        Mockito.when(courseClient.aiDetail(anyLong())).thenReturn(R.ok(c));

        CourseDetailRequest req = new CourseDetailRequest();
        req.setCourseId("102");
        JsonNode json = parse(tools.getCourseDetail(req, ctx));
        assertEquals("OK", json.get("status").asText());
        assertEquals(8L, json.get("chapterCount").asLong());
        assertTrue(!json.has("chapters"), "无真实章节时不得输出 chapters（防编造）");
    }

    private JsonNode parse(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
