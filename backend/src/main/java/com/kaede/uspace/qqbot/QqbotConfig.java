package com.kaede.uspace.qqbot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * QQ 机器人的 WebSocket 装配（模块 11）。
 *
 * <p>只做三件事：注册端点、设置握手拦截器、调大单帧上限。
 *
 * <h3>整块挂在开关上</h3>
 *
 * <p>{@code @ConditionalOnProperty} 加在<b>这一层</b>（也就是 {@code @EnableWebSocket} 所在的类），
 * 不是加在 handler 上 —— 这样 {@code enabled=false} 时<b>端点根本不存在</b>，
 * 那条被 {@code SecurityConfig.PUBLIC_PATHS} 永久放行的路径返回的是 404。
 * 反过来「路径存在但模块关着」是一个不设防的入口，而没有人会记得去检查它。
 *
 * <h3>端点路径为什么两处硬编码</h3>
 *
 * <p>这里用的是 {@code uspace.qqbot.ws-path}，而 {@code SecurityConfig.PUBLIC_PATHS}
 * 里写的是<b>字面量</b> —— 那一处在 {@code auth} 包，按「其他模块禁止 import qqbot」
 * 这条单向依赖规则，它读不到 {@link QqbotProperties}。
 * 所以改路径必须同时改两处，两边都写了交叉注释。
 * 这与 {@code uspace.upload.url-prefix} 和 {@code /uploads/**} 是同一类耦合，
 * 项目里已有一处先例。
 */
@Slf4j
@Configuration
@EnableWebSocket
@ConditionalOnProperty(name = "uspace.qqbot.enabled", havingValue = "true")
public class QqbotConfig implements WebSocketConfigurer {

    /**
     * 单帧文本的上限，1MB。
     *
     * <p>⚠️ <b>必须显式调大，而且不能依赖 NapCat 那边的配置</b>：
     * 容器的默认上限是 8KB，而群消息里的<b>图片在 OneBot 的 array 格式下是内联的
     * base64</b>，单帧几百 KB 很常见。超限的后果是<b>容器直接关掉这条连接</b> ——
     * 表现是「机器人隔一会儿就掉一次线」，而后端日志里没有任何我们自己的错误，
     * 排查方向会一路歪到网络上去。
     *
     * <p>我们只读 {@code raw_message} 并不能免掉这一条：Jackson 得先把整个帧收下来，
     * 才谈得上跳过不认识的字段。
     */
    private static final int MAX_TEXT_MESSAGE_BYTES = 1024 * 1024;

    private final QqbotProperties properties;

    private final OneBotWebSocketHandler handler;

    private final OneBotHandshakeInterceptor handshakeInterceptor;

    /**
     * 构造器注入，顺带做启动校验。
     *
     * <p>⚠️ <b>启用而令牌为空时抛异常，让应用拒绝启动</b>，与 {@code JwtService}
     * 校验空密钥是同一族做法。理由：令牌为空而端点照常注册，等于<b>任何能访问
     * 本服务端口的人都能连上来冒充 NapCat</b>，把在店人数与消费金额查个遍。
     * 这是安全事故，不是功能故障 —— 宁可起不来。
     *
     * <p>判断用 {@code isBlank} 而不是 {@code isEmpty}：一个全是空格的令牌
     * 与空串一样等于没有鉴权。
     *
     * @param properties          配置
     * @param handler             消息处理器
     * @param handshakeInterceptor 握手鉴权拦截器
     * @throws IllegalStateException 启用了但没有配置访问令牌时
     */
    public QqbotConfig(QqbotProperties properties,
                       OneBotWebSocketHandler handler,
                       OneBotHandshakeInterceptor handshakeInterceptor) {
        if (!properties.hasUsableToken()) {
            throw new IllegalStateException(
                    "[QQ机器人] uspace.qqbot.enabled=true 但 uspace.qqbot.access-token 为空。"
                            + "没有令牌就没有鉴权，端点一旦注册，任何能访问本端口的人"
                            + "都能连上来读在店名单与消费金额。"
                            + "请设置环境变量 QQBOT_ACCESS_TOKEN，或把 enabled 改回 false。");
        }
        this.properties = properties;
        this.handler = handler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    /**
     * 注册反向 WebSocket 端点。
     *
     * <p>⚠️ <b>{@code setAllowedOrigins("*")} 不能省</b>：Spring 的握手拦截器在配置了
     * 非通配的来源白名单时，会<b>拒绝没有 {@code Origin} 头的握手</b>，
     * 而 NapCat 是个服务端程序，它不发这个头。漏了这一句的症状是
     * 「NapCat 怎么都连不上，服务端日志里只有一行 403」，很难联想到来源校验上去。
     *
     * <p>放开通配不算松掉防线：真正的门是 {@link OneBotHandshakeInterceptor} 里的令牌，
     * Origin 只防浏览器发起的跨站连接，而这里连的是一个常驻进程。
     *
     * @param registry 端点注册表
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, properties.getWsPath())
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins("*");
    }

    /**
     * 调大 WebSocket 容器的单帧上限。
     *
     * <p>理由见 {@link #MAX_TEXT_MESSAGE_BYTES}。
     *
     * @return 容器配置
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_TEXT_MESSAGE_BYTES);
        container.setMaxBinaryMessageBufferSize(MAX_TEXT_MESSAGE_BYTES);
        return container;
    }
}
