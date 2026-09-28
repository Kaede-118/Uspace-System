package com.kaede.uspace.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 认证与授权失败时的统一响应输出。
 *
 * <p>认证过滤器、401 出口、403 出口三处都要往响应里写 JSON。
 * 把写响应这件事收在这一处，是为了保证<b>格式只有一份定义</b> ——
 * 将来给 {@code ApiResult} 加字段、改结构，只需要动这里，
 * 不会出现「Controller 的响应变了、认证失败的响应还是旧格式」这种情况。
 *
 * <p>这也是为什么失败信息要经由请求属性传递、而不是在过滤器里直接写响应：
 * 让过滤器只负责「判定」，输出统一收口。
 */
@Component
class AuthErrorWriter {

    private final ObjectMapper objectMapper;

    AuthErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 把错误码按统一格式写入响应。
     *
     * <p>注意设置字符集：错误消息是中文，不指定编码时 Servlet 容器可能
     * 按 ISO-8859-1 输出，前端看到的就是乱码。
     *
     * @param response 当前响应
     * @param error    错误码，决定 HTTP 状态码与响应体内容
     * @throws IOException 写响应失败时抛出
     */
    void write(HttpServletResponse response, ErrorCode error) throws IOException {
        response.setStatus(error.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResult.failure(error, null));
    }
}
