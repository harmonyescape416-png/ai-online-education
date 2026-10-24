package com.kzai.learning.note;

import com.kzai.common.exception.BizException;
import com.kzai.common.result.R;
import com.kzai.learning.client.CourseClient;
import com.kzai.learning.client.UserClient;
import com.kzai.learning.dto.CourseOutlineDTO;
import com.kzai.learning.dto.EntitlementDecision;
import com.kzai.learning.dto.LearnChapterDTO;
import com.kzai.learning.points.PointService;
import com.kzai.learning.service.EntitlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:learning_notes;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "spring.sql.init.data-locations=classpath:data-test.sql",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.task.scheduling.enabled=false"
})
class NoteFlowTest {

    private static final String SECRET = "付费章节正文不要泄露";

    @Autowired
    private NoteService notes;
    @Autowired
    private PointService points;
    @MockBean
    private CourseClient courses;
    @MockBean
    private EntitlementService entitlement;
    @MockBean
    private UserClient users;
    @MockBean
    private StringRedisTemplate redis;

    @BeforeEach
    void outline() {
        CourseOutlineDTO outline = new CourseOutlineDTO();
        outline.setId(100L);
        outline.setStudentVisible(true);
        outline.setFree(0);
        outline.setPrice(100);
        LearnChapterDTO paid = chapter(10L, 120, false);
        LearnChapterDTO preview = chapter(11L, 30, true);
        outline.setChapters(List.of(paid, preview));
        when(courses.learnOutline(100L)).thenReturn(R.ok(outline));
        when(entitlement.resolve(anyLong(), any())).thenReturn(EntitlementDecision.deny("未购买或权益已失效"));
        when(entitlement.resolve(eq(1L), any())).thenReturn(EntitlementDecision.grant("PAID", null));
        when(entitlement.resolve(eq(4L), any())).thenReturn(EntitlementDecision.grant("PAID", null));
        when(users.names(anyList())).thenReturn(R.ok(Map.of(1L, "13800138000")));
        when(users.writeOperationLog(any())).thenReturn(R.ok(true));
    }

    @Test
    void notesStayPrivateUntilAccessAndLikesAreIdempotent() throws Exception {
        BizException noChapter = assertThrows(BizException.class,
                () -> notes.create(1L, 100L, 99L, "标题", "正文", 1, "PRIVATE"));
        assertEquals("章节不属于该课程", noChapter.getMessage());
        BizException tooLate = assertThrows(BizException.class,
                () -> notes.create(1L, 100L, 10L, "标题", "正文", 121, "PRIVATE"));
        assertEquals("播放时间点超出章节范围", tooLate.getMessage());
        assertThrows(BizException.class, () -> notes.create(2L, 100L, 10L, "标题", SECRET, 1, "PUBLIC"));

        Map<String, Object> created = notes.create(1L, 100L, 10L, "听课记录", SECRET, 12, null);
        long id = Long.parseLong(String.valueOf(created.get("id")));
        assertEquals("PRIVATE", created.get("visibility"));
        assertEquals("138****8000", created.get("authorName"));
        assertEquals(SECRET, notes.detail(1L, id).get("content"));

        BizException stolen = assertThrows(BizException.class, () -> notes.update(2L, id, 10L, "改", "改", 1, true, "PUBLIC"));
        assertEquals("只能编辑自己的笔记", stolen.getMessage());
        assertFalse(stolen.getMessage().contains(SECRET));
        BizException hidden = assertThrows(BizException.class, () -> notes.detail(2L, id));
        assertEquals("笔记不存在", hidden.getMessage());
        assertFalse(String.valueOf(notes.publicList(2L, 100L, null, "time", 1, 10)).contains(SECRET));

        notes.update(1L, id, 10L, null, null, null, false, "PUBLIC");
        Map<String, Object> locked = notes.detail(2L, id);
        assertEquals(true, locked.get("locked"));
        assertNull(locked.get("content"));
        assertFalse(String.valueOf(locked).contains(SECRET));
        assertThrows(BizException.class, () -> notes.comments(2L, id, 1, 10));

        when(entitlement.resolve(eq(2L), any())).thenReturn(EntitlementDecision.grant("PAID", null));
        assertEquals(SECRET, notes.detail(2L, id).get("content"));
        notes.comment(2L, id, "<script>alert(1)</script>");
        notes.update(1L, id, 10L, null, null, null, false, "PRIVATE");
        assertThrows(BizException.class, () -> notes.detail(2L, id));
        assertThrows(BizException.class, () -> notes.comments(2L, id, 1, 10));

        notes.update(1L, id, 10L, "听课记录", SECRET + " <script>alert(1)</script>", 12, true, "PUBLIC");
        Map<String, Object> injected = notes.create(1L, 100L, 10L, "另一条", "正常正文", null, "PRIVATE");
        Map<String, Object> searched = notes.mine(1L, 100L, "' OR 1=1 --", 1, 10);
        assertEquals(0L, ((Number) searched.get("total")).longValue());
        assertEquals(SECRET + " <script>alert(1)</script>", notes.detail(1L, id).get("content"));
        assertFalse(String.valueOf(searched).contains("正常正文"));
        assertTrue(notes.detail(1L, Long.parseLong(String.valueOf(injected.get("id")))).get("content").equals("正常正文"));

        Map<String, Object> preview = notes.create(2L, 100L, 11L, "试看", "试看笔记", 3, "PUBLIC");
        when(entitlement.resolve(eq(2L), any())).thenReturn(EntitlementDecision.deny("已退款"));
        assertEquals("试看笔记", notes.detail(2L, Long.parseLong(String.valueOf(preview.get("id")))).get("content"));
        assertEquals(true, notes.detail(2L, id).get("locked"));

        when(entitlement.resolve(eq(2L), any())).thenReturn(EntitlementDecision.grant("PAID", null));
        AtomicInteger first = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<?> one = pool.submit(() -> likeOnce(4L, id, first, ready, go));
        Future<?> two = pool.submit(() -> likeOnce(4L, id, first, ready, go));
        ready.await();
        go.countDown();
        one.get();
        two.get();
        pool.shutdown();
        assertEquals(1, first.get());
        assertEquals(1, ((Number) notes.detail(1L, id).get("likeCount")).intValue());
        int awarded = ((List<Map<String, Object>>) points.ledger(1L, 1, 20).get("records")).stream()
                .filter(row -> "NOTE_LIKE".equals(row.get("source")))
                .mapToInt(row -> ((Number) row.get("points")).intValue()).sum();
        assertEquals(1, awarded);
        notes.unlike(4L, id);
        notes.like(4L, id);
        int still = ((List<Map<String, Object>>) points.ledger(1L, 1, 20).get("records")).stream()
                .filter(row -> "NOTE_LIKE".equals(row.get("source")))
                .mapToInt(row -> ((Number) row.get("points")).intValue()).sum();
        assertEquals(1, still);
        notes.like(1L, id);
        int self = ((List<Map<String, Object>>) points.ledger(1L, 1, 20).get("records")).stream()
                .filter(row -> "NOTE_LIKE".equals(row.get("source")))
                .mapToInt(row -> ((Number) row.get("points")).intValue()).sum();
        assertEquals(1, self);

        notes.remove(1L, id);
        assertThrows(BizException.class, () -> notes.detail(1L, id));
        assertThrows(BizException.class, () -> notes.comments(4L, id, 1, 10));
        assertFalse(String.valueOf(notes.publicList(4L, 100L, null, "hot", 1, 10)).contains(SECRET));
        assertThrows(BizException.class, () -> notes.adminDelete(9L, id, " "));
    }

    private void likeOnce(long userId, long noteId, AtomicInteger first, CountDownLatch ready, CountDownLatch go) {
        ready.countDown();
        try {
            go.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return;
        }
        Map<String, Object> result = notes.like(userId, noteId);
        if (!Boolean.TRUE.equals(result.get("repeated"))) {
            first.incrementAndGet();
        }
    }

    private static LearnChapterDTO chapter(long id, int duration, boolean preview) {
        LearnChapterDTO chapter = new LearnChapterDTO();
        chapter.setId(id);
        chapter.setCourseId(100L);
        chapter.setVideoDurationSec(duration);
        chapter.setFreePreview(preview);
        return chapter;
    }
}
