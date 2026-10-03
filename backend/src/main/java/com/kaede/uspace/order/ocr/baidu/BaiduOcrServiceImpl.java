package com.kaede.uspace.order.ocr.baidu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaede.uspace.order.ocr.OcrProperties;
import com.kaede.uspace.order.ocr.OcrResult;
import com.kaede.uspace.order.ocr.OcrService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 文字识别的真实实现：百度智能云 OCR。
 *
 * <p>对应 {@code uspace.ocr.provider=baidu}。<b>接口路径、参数名、返回字段都照百度
 * 开放平台的真实规格</b>，与 {@code LockService} 对通通锁、{@code PaymentGateway}
 * 对微信 / 支付宝是同一种做法 —— 那段说明见设计文档第七章。
 *
 * <h3>两个接口</h3>
 *
 * <ol>
 *   <li><b>鉴权</b>：{@code POST /oauth/2.0/token}，
 *       {@code grant_type=client_credentials} + {@code client_id} + {@code client_secret}，
 *       换回 {@code access_token}。<b>有效期 30 天</b>（{@code expires_in=2592000}）</li>
 *   <li><b>识别</b>：{@code POST /rest/2.0/ocr/v1/accurate_basic}，
 *       form-urlencoded，图片以 Base64 放在 {@code image} 参数里，
 *       返回 {@code words_result} 数组，每项一个 {@code words} 字段</li>
 * </ol>
 *
 * <h3>token 必须缓存复用</h3>
 *
 * <p>百度的 access_token 有 30 天有效期，而<b>换取接口本身也有每日调用次数限制</b>
 * （超了会返回错误码 18）。所以这里用一个字段把它缓存住，只在过期前才重换 ——
 * 与通通锁那边「token 需服务端缓存复用，不要每次调用都重新换取」是同一条纪律。
 *
 * <p>提前 {@value #TOKEN_REFRESH_AHEAD_SECONDS} 秒就刷新，不去卡那个精确的到期时刻：
 * 差一秒就可能拿着一个刚失效的 token 去识别，而失败的表现是「这次没识别出」——
 * 静默的、没人会去查的失败。
 *
 * <h3>配置写错要在启动时就炸掉</h3>
 *
 * <p>配了 {@code provider=baidu} 却没填 key，构造器直接抛异常、<b>应用起不来</b>。
 * 这与 {@code JWT_SECRET} 那条是同一个道理：<b>「以为配了其实没配」不会报任何错，
 * 只会静默地永远识别不出</b>，而这件事没有任何人会注意到 ——
 * 管理员看到后台识别栏是空的，只会以为自己传的那张图不清晰。
 *
 * <h3>失败一律走返回值</h3>
 *
 * <p>网络超时、DNS 解析不了、token 换不到、响应不是 JSON —— 全部包成
 * {@link OcrResult#fail}。理由见 {@link OcrService}：调用方要的是「能不能继续」，
 * 而异常会把「百度挂了」与「我们自己有 bug」混成同一类东西，在日志里分不开。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "uspace.ocr", name = "provider", havingValue = "baidu")
public class BaiduOcrServiceImpl implements OcrService {

    /** 换取 access_token 的路径 */
    private static final String TOKEN_PATH = "/oauth/2.0/token";

    /** 通用文字识别（高精度版）的路径。高精度版对长数字串的识别明显好于标准版 */
    private static final String RECOGNIZE_PATH = "/rest/2.0/ocr/v1/accurate_basic";

    /**
     * token 到期前多久就重换（秒）。
     *
     * <p>一小时。取得太短会在边界上撞见「刚过期」的失败，
     * 取得太长则每次识别都可能白换一次 —— 而换取接口本身有限额。
     */
    private static final long TOKEN_REFRESH_AHEAD_SECONDS = 3600;

    /** 识别语言类型。付款截图上既有中文也有数字，用中英混合 */
    private static final String LANGUAGE_TYPE = "CHN_ENG";

    private final OcrProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /**
     * 换 token 时的互斥锁。
     *
     * <p>只在<b>需要换 token 时</b>才争它 —— 正常路径（缓存命中）完全不进同步块，
     * 所以不会成为识别请求的瓶颈。
     */
    private final Object tokenLock = new Object();

    /** 缓存的 access_token，未获取过时为 null */
    private volatile String accessToken;

    /** 缓存 token 的到期时刻（epoch 秒），与 {@link #accessToken} 同时更新 */
    private volatile long tokenExpiresAtSecond;

    /**
     * 构造器注入，并在这里做配置校验。
     *
     * <p>收的是<b>已经造好的客户端</b>而不是 {@code RestClient.Builder}：
     * baseUrl 与超时怎么配是 {@link BaiduOcrConfig} 的事，
     * 而这里只管怎么调那两个接口。这个分工不是洁癖 —— 服务若自己去动 builder，
     * 就会把单测里 {@code MockRestServiceServer} 设下的拦截工厂覆盖掉，
     * 让测试静默地打真网络（详见那个配置类的注释）。
     *
     * @param properties   OCR 配置，读 {@code uspace.ocr.baidu.*}
     * @param restClient   {@link BaiduOcrConfig} 造好的客户端，已带地址与超时
     * @param objectMapper 解析 JSON 响应
     * @throws IllegalStateException 配了 {@code baidu} 却没填凭据时抛出，让应用启动失败
     */
    public BaiduOcrServiceImpl(OcrProperties properties,
                               RestClient restClient,
                               ObjectMapper objectMapper) {
        OcrProperties.Baidu baidu = properties.getBaidu();
        if (isBlank(baidu.getApiKey()) || isBlank(baidu.getSecretKey())) {
            // ⚠️ 这条异常是刻意让应用起不来的，不要改成只打 WARN ——
            // 见类注释「配置写错要在启动时就炸掉」
            throw new IllegalStateException(
                    "uspace.ocr.provider=baidu 但未配置凭据，请设置 uspace.ocr.baidu.api-key "
                            + "与 uspace.ocr.baidu.secret-key（走环境变量注入，不要写进配置文件）；"
                            + "若暂时不想接入识别服务，把 provider 改回 mock");
        }
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;

        log.info("[OCR] 已启用百度文字识别（provider=baidu），域名={} 超时={}ms",
                BaiduOcrConfig.BASE_URL, properties.getTimeoutMillis());
    }

    /**
     * 识别一张图片。
     *
     * <p>顺序是「先要 token，再识别」—— 两次都可能失败，各自返回自己的失败原因。
     *
     * @param imageBytes 图片字节，调用方已保证非空
     * @return 识别结果；任何一步失败都返回 {@code success=false}
     */
    @Override
    public OcrResult recognize(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return OcrResult.fail(null, "图片内容为空");
        }

        String token = accessToken();
        if (token == null) {
            return OcrResult.fail(null, "获取 access_token 失败，详见日志");
        }

        OcrResult result = callRecognize(token, imageBytes);

        if (isTokenRejected(result)) {
            /*
             * ⚠️ 这一段是这条链路的「防静默失效」阀。
             *
             * access_token 名义上有 30 天有效期，但它可能被【提前】作废 ——
             * 在百度控制台轮换了密钥、或历史上在别处换过一次 token，都会这样。
             * 那时每个请求都稳定地返回 110/111，而按本类的纪律，
             * 「识别失败」的表现与「这张图没字」完全一样：后台那一栏是空的。
             *
             * 于是 OCR 会从某一天起永久失效，而没有任何人能看出来 ——
             * 管理员只会觉得「最近传的截图都不太清晰」。
             *
             * 清掉缓存重换一次就能自愈，代价是这一次多一个来回。
             */
            log.info("[OCR] access_token 被拒（errcode={}），清掉缓存重试一次", result.getErrcode());
            invalidateToken();
            String fresh = accessToken();
            if (fresh != null) {
                result = callRecognize(fresh, imageBytes);
            }
        }
        return result;
    }

    /**
     * 调一次识别接口。
     *
     * @param token      可用的 access_token
     * @param imageBytes 图片字节
     * @return 识别结果；网络层失败也包成 {@code success=false}
     */
    private OcrResult callRecognize(String token, byte[] imageBytes) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("image", Base64.getEncoder().encodeToString(imageBytes));
        form.add("language_type", LANGUAGE_TYPE);
        // 不做朝向检测：付款截图永远是正的，开着它只会多花时间
        form.add("detect_direction", "false");

        String body;
        try {
            body = restClient.post()
                    .uri(uriBuilder -> uriBuilder.path(RECOGNIZE_PATH)
                            .queryParam("access_token", token).build())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            // 宽泛捕获是刻意的：这里是外部服务的边界，网络超时、连接被拒、
            // 非 2xx 抛出的 RestClientResponseException 都该被挡在这里，
            // 变成一句「这次没识别出」，而不是把上传接口带崩
            log.warn("[OCR] 调用百度文字识别失败：{}", e.toString());
            return OcrResult.fail(null, "调用文字识别服务失败：" + e.getMessage());
        }

        return parseResponse(body);
    }

    /**
     * 这次失败是不是「token 不认了」。
     *
     * <p>百度对这两种情形分别给 110（失效）与 111（过期）。
     * 只在这两个码上重试：换成配额超限（17）或 QPS 超限（18）去重试，
     * 只是白花一次调用、结果仍然一样。
     *
     * @param result 识别结果
     * @return 是 token 类错误返回 true
     */
    private static boolean isTokenRejected(OcrResult result) {
        if (result.isSuccess()) {
            return false;
        }
        Integer code = result.getErrcode();
        return Integer.valueOf(110).equals(code) || Integer.valueOf(111).equals(code);
    }

    /**
     * 作废缓存的 token，让下一次调用重新去换。
     *
     * <p>与 {@link #accessToken()} 争同一把锁，避免「重试刚清掉、
     * 另一个线程又把它写回来」这种反效果。
     */
    private void invalidateToken() {
        synchronized (tokenLock) {
            this.accessToken = null;
            this.tokenExpiresAtSecond = 0;
        }
    }

    // ==================================================================
    // access_token
    // ==================================================================

    /**
     * 取一个可用的 access_token，缓存命中就直接用。
     *
     * <p>两次检查（同步块外一次、块内一次）是标准的双重检查：外层的便宜检查
     * 挡住绝大多数请求，内层的那次防止「两个线程同时发现过期、各换一个 token」。
     *
     * @return 可用的 token；换取失败时返回 null（调用方据此返回失败结果）
     */
    private String accessToken() {
        String cached = accessToken;
        if (isUsable(cached)) {
            return cached;
        }

        synchronized (tokenLock) {
            if (isUsable(accessToken)) {
                return accessToken;
            }
            return refreshToken();
        }
    }

    /**
     * 缓存的 token 现在还能用吗。
     *
     * @param token 缓存值，可为 null
     * @return 未过期（且留足了提前量）返回 true
     */
    private boolean isUsable(String token) {
        return token != null && nowSecond() < tokenExpiresAtSecond - TOKEN_REFRESH_AHEAD_SECONDS;
    }

    /**
     * 调百度鉴权接口换一个新的 token 并写入缓存。
     *
     * <p><b>调用方必须持有 {@link #tokenLock}</b>。
     *
     * @return 新的 token；失败时返回 null
     */
    private String refreshToken() {
        try {
            String body = restClient.post()
                    .uri(uriBuilder -> uriBuilder.path(TOKEN_PATH)
                            .queryParam("grant_type", "client_credentials")
                            .queryParam("client_id", properties.getBaidu().getApiKey())
                            .queryParam("client_secret", properties.getBaidu().getSecretKey())
                            .build())
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(body);
            String token = root.path("access_token").asText(null);
            if (isBlank(token)) {
                // 百度把失败原因放在 error / error_description 里，
                // 最常见的是 invalid_client（key 填错）
                log.warn("[OCR] 换取 access_token 被拒绝：error={} description={}",
                        root.path("error").asText(""), root.path("error_description").asText(""));
                return null;
            }

            long expiresIn = root.path("expires_in").asLong(0);
            this.accessToken = token;
            this.tokenExpiresAtSecond = nowSecond() + expiresIn;
            log.info("[OCR] access_token 已刷新，有效期 {} 秒", expiresIn);
            return token;
        } catch (Exception e) {
            log.warn("[OCR] 换取 access_token 失败：{}", e.toString());
            return null;
        }
    }

    // ==================================================================
    // 响应解析
    // ==================================================================

    /**
     * 把识别接口的响应解析成 {@link OcrResult}。
     *
     * <p>百度把失败也放在 HTTP 200 的响应体里（{@code error_code} 字段），
     * 所以这里不能只看状态码，必须读 body。
     *
     * @param body 响应体，可能为 null（服务端返回了空体）
     * @return 识别结果
     */
    private OcrResult parseResponse(String body) {
        if (body == null || body.isBlank()) {
            log.warn("[OCR] 百度文字识别返回了空响应体");
            return OcrResult.fail(null, "文字识别服务返回空响应");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            // 把 body 截断后记进日志：正常时它是 JSON，不该出现解析不了的情况，
            // 记一段原文有助于判断是不是被网关拦了（返回了一个 HTML 错误页）
            log.warn("[OCR] 解析百度文字识别响应失败：{}，响应开头={}",
                    e.toString(), abbreviate(body));
            return OcrResult.fail(null, "文字识别服务返回了无法解析的内容");
        }

        if (root.hasNonNull("error_code")) {
            int code = root.path("error_code").asInt();
            String message = root.path("error_msg").asText("");
            // 常见的几个：17 日调用量超限、18 QPS 超限、110/111 token 失效或过期。
            // 无论哪个，对用户都只是「这次没识别出」，但日志里要留下真实原因 ——
            // 配额耗尽与「这张图没字」在排查时是完全不同的两件事
            log.warn("[OCR] 百度文字识别返回错误 code={} msg={}", code, message);
            return OcrResult.fail(code, message);
        }

        List<String> lines = new ArrayList<>();
        for (JsonNode item : root.path("words_result")) {
            String words = item.path("words").asText("");
            if (!words.isBlank()) {
                lines.add(words);
            }
        }
        return OcrResult.ok(lines);
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /**
     * 当前时刻的 epoch 秒。
     *
     * @return 秒级时间戳
     */
    private static long nowSecond() {
        return System.currentTimeMillis() / 1000;
    }

    /**
     * 判断字符串是否为空或全空白。
     *
     * @param value 待判断的值，可为 null
     * @return 空或全空白返回 true
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 截断一段文本用于日志。
     *
     * @param text 原文
     * @return 最多 200 个字符的片段
     */
    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}
