package com.kaede.uspace.order.ocr.baidu;

import com.kaede.uspace.order.ocr.OcrProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 百度文字识别所用的 HTTP 客户端配置。
 *
 * <p><b>为什么把 RestClient 的构造单独拎出来</b>，而不是让
 * {@link BaiduOcrServiceImpl} 在自己的构造器里配：
 *
 * <ul>
 *   <li><b>职责</b>：baseUrl 与超时是「这个 HTTP 客户端怎么连」，
 *       而服务类管的是「怎么调那两个接口、怎么解析响应」。两件事分开之后，
 *       服务类不再需要 {@code RestClient.Builder}，注入一个现成的客户端即可</li>
 *   <li><b>可测性 —— 这条是被实实在在的坑逼出来的</b>：单测用
 *       {@code MockRestServiceServer} 把请求拦下来，而它的拦截手段正是
 *       <b>给 builder 设一个假的 RequestFactory</b>。服务若在自己的构造器里
 *       再调一次 {@code builder.requestFactory(真工厂)}，就会把它覆盖掉 ——
 *       于是测试<b>静默地打起真网络</b>，而按本项目的纪律「识别失败一律当作
 *       没识别出」，它照样全绿。这类假绿比红色难查得多</li>
 * </ul>
 *
 * <p>条件装配与 {@link BaiduOcrServiceImpl} 一致：{@code provider=baidu} 时才有这个 bean。
 */
@Configuration
@ConditionalOnProperty(prefix = "uspace.ocr", name = "provider", havingValue = "baidu")
public class BaiduOcrConfig {

    /**
     * 百度开放平台的接入点。
     *
     * <p>百度国内只有一个接入点，不像通通锁还分国内与欧盟两个，
     * 所以不做成配置项 —— 少一个能配错的地方。
     */
    static final String BASE_URL = "https://aip.baidubce.com";

    /**
     * 造一个专供文字识别使用的客户端。
     *
     * <p><b>超时是这条链路最要紧的参数</b>：调用发生在用户上传截图的等待路径上，
     * 百度那边要是卡住了，没有超时就会一直挂着，把上传线程一个个占满。
     * 连接与读取用同一个值，来自 {@code uspace.ocr.timeout-millis}。
     *
     * @param builder    Spring Boot 自动配置提供的构造器（prototype，改动不会外溢）
     * @param properties OCR 配置，读超时值
     * @return 配好地址与超时的客户端
     */
    @Bean
    RestClient baiduOcrRestClient(RestClient.Builder builder, OcrProperties properties) {
        Duration timeout = Duration.ofMillis(properties.getTimeoutMillis());

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);

        return builder.baseUrl(BASE_URL).requestFactory(factory).build();
    }
}
