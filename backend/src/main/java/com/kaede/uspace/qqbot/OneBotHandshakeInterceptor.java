package com.kaede.uspace.qqbot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * 反向 WebSocket 的握手鉴权（模块 11）。
 *
 * <p><b>这是本模块唯一的一道门。</b> 端点本身在 {@code SecurityConfig} 里是匿名放行的
 * （NapCat 是服务端到服务端的连接，不带本系统的 JWT），所以「谁连上来了」
 * 完全由这里判定 —— 令牌不对就拒绝握手，连接根本建立不起来。
 *
 * <p>不设这道门会怎样：任何能访问本服务端口的人都能连上来冒充 NapCat，
 * 然后往群里发任意消息、或者调 Action 把本店的数据读个遍。
 * 所以 {@link QqbotProperties#enabled} 打开而令牌为空时，
 * {@link QqbotConfig} 会让应用<b>直接拒绝启动</b>，而不是放一个没锁的门在那儿。
 *
 * <h3>令牌从哪来</h3>
 *
 * <p>OneBot 规范的约定是 {@code Authorization: Bearer <access_token>} 请求头；
 * 若实现无法设置该头，则退而用 URL 的 {@code access_token} 查询参数。
 * <b>两种都支持</b>：优先请求头，请求头没有才看查询参数。
 *
 * <p>⚠️ <b>比对时两边的空白字符一律不裁剪</b> —— 规范原文写着「这里
 * {@code <access_token>} 不需要对两边的空白字符进行裁剪」。
 * 顺手加个 {@code trim()} 看着无害，实则会让「NapCat 里填的口令首尾带了个空格」
 * 变成永远连不上，而排查方向会一路歪到网络、防火墙和反向代理上去。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "uspace.qqbot.enabled", havingValue = "true")
public class OneBotHandshakeInterceptor implements HandshakeInterceptor {

    /** 规范约定的请求头前缀。注意 HTTP 头字段的<b>值</b>大小写敏感，按规范用 {@code Bearer} */
    private static final String BEARER_PREFIX = "Bearer ";

    /** 规范给的兜底查询参数名 */
    private static final String QUERY_PARAM_TOKEN = "access_token";

    /** 期望的令牌，来自配置 */
    private final String expectedToken;

    /**
     * 构造器注入。
     *
     * @param properties QQ 机器人配置。调用方保证此时令牌非空（见 {@link QqbotConfig} 的启动校验）
     */
    public OneBotHandshakeInterceptor(QqbotProperties properties) {
        this.expectedToken = properties.getAccessToken();
    }

    /**
     * 握手前校验令牌。
     *
     * <p>返回 false 时连接不会建立，且响应被置为 401。NapCat 那边看到的只是
     * 「连不上」，所以被拒绝时这里一定要留下日志 —— 否则排查的人会以为是网络问题。
     *
     * @param request    握手请求（此时还是普通 HTTP 请求）
     * @param response   握手响应。校验失败时置 401
     * @param wsHandler  目标处理器，本方法不用
     * @param attributes 会话属性，本方法不用
     * @return 令牌正确返回 true
     */
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!tokenMatches(extractToken(request))) {
            log.warn("[QQ机器人] 握手被拒：令牌不匹配 remote={}", request.getRemoteAddress());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        return true;
    }

    /**
     * 握手完成后什么都不做。
     *
     * <p>连接建立这件事由 {@link OneBotClient#attach} 记 —— 它才是持有连接状态的地方，
     * 在那里记能顺便把「顶替了旧连接」这件事一起说出来。
     *
     * @param request   握手请求
     * @param response  握手响应
     * @param wsHandler 目标处理器
     * @param exception 握手过程中的异常，可为 null
     */
    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 有意为空
    }

    /**
     * 从请求里取出令牌：优先请求头，其次查询参数。
     *
     * @param request 握手请求
     * @return 令牌；两种途径都没有时返回 null
     */
    private String extractToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            // 刻意不 trim，见类注释
            return header.substring(BEARER_PREFIX.length());
        }
        return extractTokenFromQuery(request.getURI());
    }

    /**
     * 从 URL 查询串里取 {@code access_token}。
     *
     * <p>手写解析而不引 Spring 的 {@code UriComponentsBuilder}：那个类的
     * {@code build()} 与 {@code build(true)} 在「要不要解码」上的行为差别很微妙，
     * 用错一次就会让带特殊字符的令牌静默取错，而这里只有一种参数、几行就写完了。
     *
     * @param uri 握手请求的 URI
     * @return 解码后的令牌；查询串里没有这个参数时返回 null
     */
    private String extractTokenFromQuery(URI uri) {
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0 && QUERY_PARAM_TOKEN.equals(pair.substring(0, separator))) {
                return URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /**
     * 定长比较令牌。
     *
     * <p>时序攻击在本场景下并不现实（攻击者要靠海量请求的耗时差别逐字节猜口令，
     * 而这里连错一次就直接断开），但这行代码的成本是零，所以顺手做对 ——
     * 与模块 1 的 {@code QqVerifyService#constantTimeEquals} 同一个态度。
     *
     * @param presented 请求带来的令牌，可为 null
     * @return 与配置中的令牌一致时返回 true
     */
    private boolean tokenMatches(String presented) {
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
