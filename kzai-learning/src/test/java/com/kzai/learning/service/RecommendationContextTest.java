package com.kzai.learning.service;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.AuthException;
import com.kzai.learning.controller.RecommendationContextController;
import com.kzai.learning.mapper.LearningLessonMapper;
import com.kzai.learning.entity.LearningLesson;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class RecommendationContextTest {
    @AfterEach void clear(){UserContext.clear();}
    @Test void missingIdentityIsRejectedBeforeQuery(){
        var mapper=mock(LearningLessonMapper.class);
        assertThrows(AuthException.class,()->new RecommendationContextController(mapper).current());verifyNoInteractions(mapper);
    }
    @Test @SuppressWarnings("unchecked") void queryUsesOnlyTrustedCurrentUser(){
        var mapper=mock(LearningLessonMapper.class);
        // 初始化 MyBatis lambda 元数据（本测试不连接业务数据库）。
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
            new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),"test"),LearningLesson.class);
        UserContext.set(3L,"student",2,List.of("STUDENT"),"test");
        var row=new LearningLesson();row.setCourseId(11L);row.setStatus(2);
        when(mapper.selectList(any(Wrapper.class))).thenAnswer(i->{
            var query=(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LearningLesson>)i.getArgument(0);
            assertTrue(query.getSqlSegment().contains("user_id"));assertEquals(List.of(3L),new ArrayList<>(query.getParamNameValuePairs().values()));
            return List.of(row);
        });
        var result=new RecommendationContextController(mapper).current().getData();
        assertEquals(List.of("11"),result.get("completed"));assertEquals(List.of("11"),result.get("enrolled"));
    }
}
