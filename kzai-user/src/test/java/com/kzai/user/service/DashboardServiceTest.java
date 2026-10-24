package com.kzai.user.service;

import com.kzai.common.result.R;
import com.kzai.user.client.AigcStatsClient;
import com.kzai.user.client.CourseStatsClient;
import com.kzai.user.client.LearningStatsClient;
import com.kzai.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.cloud.openfeign.FeignClientProperties;
import org.springframework.core.io.ClassPathResource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DashboardServiceTest {
    @Test
    void learningTimeoutPreservesOtherMetricsAndDoesNotReportZero() {
        UserMapper users = mock(UserMapper.class);
        CourseStatsClient courses = mock(CourseStatsClient.class);
        AigcStatsClient aigc = mock(AigcStatsClient.class);
        LearningStatsClient learning = mock(LearningStatsClient.class);
        when(users.selectCount(any())).thenReturn(12L);
        when(courses.courseCount()).thenReturn(R.ok(3L));
        when(aigc.overview(7)).thenReturn(R.ok(Map.of("todaySessions", 2L, "modelCallTotal", 0)));
        when(learning.questions(7)).thenThrow(new RuntimeException("Read timed out"));

        Map<String, Object> result = new DashboardService(users, courses, learning, aigc).overview(7);
        assertEquals(12L, result.get("userCount"));
        assertEquals(3L, result.get("courseCount"));
        assertEquals(2L, result.get("todaySessions"));
        assertEquals(true, result.get("aigcAvailable"));
        assertEquals(false, result.get("learningAvailable"));
        assertNull(result.get("totalQuestions"));
        assertNull(result.get("pendingQuestions"));
    }

    @Test
    void allDashboardClientsHaveBoundedTimeoutsWithinBrowserBudget() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Map<String, Object> properties = new HashMap<>();
        yaml.getObject().forEach((key, value) -> properties.put(String.valueOf(key), value));
        FeignClientProperties clients = new Binder(new MapConfigurationPropertySource(properties))
                .bind("spring.cloud.openfeign.client", FeignClientProperties.class).get();
        int total = 0;
        for (String name : new String[]{"CourseStatsClient", "LearningStatsClient", "AigcStatsClient"}) {
            var config = clients.getConfig().get(name);
            assertNotNull(config, "Missing timeout for " + name);
            assertTrue(config.getConnectTimeout() > 0 && config.getConnectTimeout() <= 1000);
            assertTrue(config.getReadTimeout() > 0 && config.getReadTimeout() <= 3000);
            total += config.getConnectTimeout() + config.getReadTimeout();
        }
        assertTrue(total < 30000);
    }
}
