package com.kaede.uspace.qqbot;

import com.kaede.uspace.common.config.UploadProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 从 OneBot 给的地址把图片取回来（模块 11）。
 *
 * <p>群消息里的图片段带着一个可下载的 {@code url}，它是 NapCat 在<b>本机</b>起的
 * 一个小服务 —— 后端与它同机，取图是局域网内的 HTTP，正常情况下是毫秒级。
 *
 * <p><b>失败一律返回 null，绝不抛异常</b>：调用方（群内的凭证受理）据此回一句
 * 「图没取到」，而不是让一次网络抖动把整条链路炸掉。这与其他几处
 * 「与外部打交道的地方不抛异常」是同一条纪律（{@code OneBotClient}、
 * {@code OcrService} 都如此）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "uspace.qqbot.enabled", havingValue = "true")
public class OneBotImageFetcher {

    /** 连接超时。地址在本机，连不上就是 NapCat 那边出了问题，没必要等 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /** 读取超时。图片可能几 MB，给宽一点 */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    /** 单张图的字节上限（与网页上传那条路是同一个配置，避免两处标准不一） */
    private final long maxBytes;

    private final RestClient restClient;

    /**
     * 构造器注入配置并装配 HTTP 客户端。
     *
     * <p>⚠️ <b>超时必须显式设</b>：Spring 的默认请求工厂<b>不设超时</b>，
     * 而这里的调用发生在「用户刚发完图等着回话」的路径上 ——
     * 不设超时的话，NapCat 那个小服务若卡住，处理线程会一直挂着。
     *
     * @param uploadProperties 上传配置（取图片大小上限）
     */
    public OneBotImageFetcher(UploadProperties uploadProperties) {
        this.maxBytes = uploadProperties.getMaxImageBytes();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 取一张图。
     *
     * @param url OneBot 图片段里的地址
     * @return 图片字节；地址为空、请求失败、响应不是 2xx、或超出大小上限时返回 null
     */
    public byte[] fetch(String url) {
        if (url == null || url.isBlank()) {
            // 段里没有 url 是可能的（协议端版本不同），这不算异常
            return null;
        }
        try {
            byte[] body = restClient.get().uri(url).retrieve().body(byte[].class);
            if (body == null || body.length == 0) {
                log.warn("[QQ机器人] 取图得到空响应 url={}", url);
                return null;
            }
            if (body.length > maxBytes) {
                // 超限的图不下载到底 —— 但仍然要回一句话给用户，不能静默
                log.warn("[QQ机器人] 取到的图超过上限（{} > {} 字节）url={}", body.length, maxBytes, url);
                return null;
            }
            return body;
        } catch (Exception e) {
            log.warn("[QQ机器人] 取图失败 url={} err={}", url, e.getMessage());
            return null;
        }
    }
}
