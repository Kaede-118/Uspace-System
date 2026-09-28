package com.kaede.uspace.common.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * JSON 序列化配置。
 *
 * <p>统一 {@link LocalDateTime} 的格式为 {@code yyyy-MM-dd HH:mm:ss}。
 *
 * <p><b>为什么必须显式配</b>：Spring Boot 默认把 {@code LocalDateTime} 序列化成
 * ISO-8601 数组或带 {@code T} 的字符串（{@code 2026-09-28T10:46:06}），
 * 前端做时间展示时得自己处理这个格式；Java 8 的日期类型在未配置时
 * 反序列化还会直接失败。前后端约定一个固定格式，两边都省事。
 *
 * <p><b>序列化与反序列化必须成对配置</b> —— 只配一边的话，后端返回的时间
 * 前端能显示，但前端把同一个值传回来时后端解析不了，这种「只坏一半」的问题
 * 排查起来格外费时。
 *
 * <p>用 {@code Jackson2ObjectMapperBuilderCustomizer} 而不是自己定义
 * {@code ObjectMapper} Bean：后者会覆盖 Spring Boot 的自动配置，
 * 丢掉它内置的一系列合理默认值（如忽略未知字段、Java 时间模块注册等）。
 */
@Configuration
public class JacksonConfig {

    /** 统一的时间格式。前端展示与后端解析共用这一份约定 */
    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /**
     * 定制 Jackson 的日期时间处理。
     *
     * @return 定制器，由 Spring Boot 应用到全局 ObjectMapper
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer localDateTimeCustomizer() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
        return builder -> builder
                .serializerByType(LocalDateTime.class, new LocalDateTimeSerializer(formatter))
                .deserializerByType(LocalDateTime.class, new LocalDateTimeDeserializer(formatter));
    }
}
