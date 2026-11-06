package com.gym.web.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Long 序列化成字符串，避免雪花 ID 超过 JS Number.MAX_SAFE_INTEGER（2^53-1）后精度丢失。
 *
 * <p>典型故障：后端返回 sessionId=1892…789，前端 Number 截断后再回传，
 * selectById 查不到 → 「训练会话不存在」。CDN Vue 没有 axios transform，只能在服务端兜底。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longAsStringCustomizer() {
        return builder -> {
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);
        };
    }
}
