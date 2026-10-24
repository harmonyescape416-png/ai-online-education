package com.kzai.common.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 统一 JSON 序列化：Long 输出为字符串。
 * <p>
 * 雪花 ID（19 位）超出 JavaScript Number 安全整数范围（2^53-1），
 * 若按数字返回，前端 JSON.parse 会静默丢失精度，导致按 ID 编辑/提交时命中错误记录。
 * 因此所有 Long 一律以字符串返回，前端按不透明标识处理。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> {
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);
        };
    }
}
