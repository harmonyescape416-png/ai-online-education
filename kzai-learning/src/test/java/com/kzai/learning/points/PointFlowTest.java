package com.kzai.learning.points;

import com.kzai.common.result.R;
import com.kzai.learning.client.UserClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:learning_points;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "spring.sql.init.data-locations=classpath:data-test.sql",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.task.scheduling.enabled=false"
})
class PointFlowTest {

    @Autowired
    private PointService points;
    @Autowired
    private PointClock clock;
    @MockBean
    private UserClient userClient;
    @MockBean
    private StringRedisTemplate redis;

    @Test
    void concurrentCheckinAwardsOnceAndStreakBreaksAtMidnight() throws Exception {
        clock.use(Clock.fixed(Instant.parse("2026-10-23T15:30:00Z"), ZoneId.of("Asia/Shanghai")));
        when(userClient.names(anyList())).thenReturn(R.ok(Map.of(3L, "13800138000", 4L, "student04")));
        AtomicInteger first = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<?> a = pool.submit(() -> once(3L, first, ready, go));
        Future<?> b = pool.submit(() -> once(3L, first, ready, go));
        ready.await();
        go.countDown();
        a.get();
        b.get();
        pool.shutdown();
        assertEquals(1, first.get());
        assertEquals(0, points.award(3L, "CHECKIN", "CHECKIN:3:2026-10-23", clock.today()));

        clock.use(Clock.fixed(Instant.parse("2026-10-24T15:30:00Z"), ZoneId.of("Asia/Shanghai")));
        Map<String, Object> second = points.checkin(3L);
        assertEquals(2, second.get("streak"));
        clock.use(Clock.fixed(Instant.parse("2026-10-26T15:30:00Z"), ZoneId.of("Asia/Shanghai")));
        Map<String, Object> broken = points.checkin(3L);
        assertEquals(1, broken.get("streak"));

        for (int i = 0; i < 6; i++) {
            points.award(3L, "LEARN", "LEARN:3:1:" + i, clock.today());
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) points.ledger(3L, 1, 50).get("records");
        int learn = rows.stream()
                .filter(row -> "LEARN".equals(row.get("source")))
                .mapToInt(row -> (Integer) row.get("points")).sum();
        assertEquals(10, learn, String.valueOf(rows));

        clock.use(Clock.fixed(Instant.parse("2026-10-26T15:30:01Z"), ZoneId.of("Asia/Shanghai")));
        points.award(4L, "LEARN", "LEARN:4:1:0", clock.today());
        Map<String, Object> board = points.rank("2026-10", 1, 10, 3L);
        assertEquals("DATABASE", board.get("source"));
        List<Map<String, Object>> records = (List<Map<String, Object>>) board.get("records");
        assertFalse(records.isEmpty());
        assertTrue(String.valueOf(records.get(0).get("name")).contains("****")
                || "student04".equals(records.get(0).get("name"))
                || String.valueOf(records.get(0).get("name")).startsWith("学员"));
        Map<String, Object> settled = points.settle("2026-10");
        Map<String, Object> again = points.settle("2026-10");
        assertEquals(((Number) settled.get("count")).intValue(),
                ((Number) points.rank("2026-10", 1, 50, 3L).get("total")).intValue());
        assertEquals(true, again.get("repeated"));
    }

    private int checkinPoints() {
        return ((List<Map<String, Object>>) points.ledger(3L, 1, 20).get("records")).stream()
                .filter(row -> "CHECKIN".equals(row.get("source")) || "STREAK".equals(row.get("source")))
                .mapToInt(row -> (Integer) row.get("points")).sum();
    }

    private void once(long userId, AtomicInteger first, CountDownLatch ready, CountDownLatch go) {
        ready.countDown();
        try {
            go.await();
            Map<String, Object> result = points.checkin(userId);
            if (!Boolean.TRUE.equals(result.get("already"))) {
                first.incrementAndGet();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
