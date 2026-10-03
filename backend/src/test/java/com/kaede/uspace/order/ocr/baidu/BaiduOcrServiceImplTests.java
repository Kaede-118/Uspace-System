package com.kaede.uspace.order.ocr.baidu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaede.uspace.order.ocr.OcrProperties;
import com.kaede.uspace.order.ocr.OcrResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link BaiduOcrServiceImpl} 的单元测试。
 *
 * <p><b>不起 Spring、不打真网络</b>：{@code MockRestServiceServer} 把 HTTP 层拦下来，
 * 按预设的顺序回应一段段 JSON，于是「请求长什么样、响应怎么解析」都能被钉住。
 *
 * <p>⚠️ <b>这里有一个必须守住的顺序</b>：{@code bindTo(builder)} 要发生在
 * {@code builder.build()} <b>之前</b> —— 拦截手段就是给 builder 换一个假的
 * RequestFactory，而 RestClient 在 build 那一刻就把它固化了。
 * 顺序反了的话，测试会静默地打真网络，而按「识别失败一律当作没识别出」的纪律，
 * 它<b>照样全绿</b>。{@link #setUp()} 里的两行就是这件事。
 *
 * <p>另一处刻意为之：本类<b>不复用 {@link BaiduOcrConfig}</b>，而是自己造一个
 * 不带 baseUrl 之外的任何配置的 RestClient。因为服务类若在构造器里动 builder，
 * 就会覆盖掉这里设下的拦截工厂 —— 那个坑已经被配置类的分工堵死了，
 * 而这个测试正是守门的那个。
 */
class BaiduOcrServiceImplTests {

    /** 换取 token 的正常响应（字段照百度真实返回体） */
    private static final String TOKEN_JSON = """
            {"access_token":"24.first-token","expires_in":2592000,
             "scope":"public","session_key":"sk","session_secret":"ss"}
            """;

    /** 第二次换取的 token，值不同，用来验证「重试时真的换了新的」 */
    private static final String SECOND_TOKEN_JSON = """
            {"access_token":"24.second-token","expires_in":2592000,
             "scope":"public","session_key":"sk","session_secret":"ss"}
            """;

    /** 识别成功的响应。形状取自百度 {@code accurate_basic} 的真实返回体 */
    private static final String WORDS_JSON = """
            {"words_result":[{"words":"支付成功"},{"words":"¥8.00"},
                             {"words":"交易单号"},{"words":"4200001234202609301234567890"}],
             "words_result_num":4,"log_id":1964920102406200600}
            """;

    /** 识别接口返回「token 不认了」。百度对失效与过期分别给 110 与 111 */
    private static final String INVALID_TOKEN_JSON = """
            {"error_code":110,"error_msg":"Access token invalid or no longer valid"}
            """;

    private static final String TOKEN_URL = BaiduOcrConfig.BASE_URL + "/oauth/2.0/token";
    private static final String RECOGNIZE_URL = BaiduOcrConfig.BASE_URL + "/rest/2.0/ocr/v1/accurate_basic";

    private final OcrProperties properties = new OcrProperties();

    private MockRestServiceServer server;
    private BaiduOcrServiceImpl service;

    /**
     * 每个用例前重新装配：造 builder → 挂拦截 → 构建客户端 → 构造服务。
     *
     * <p>这个顺序不能变，理由见类注释。
     */
    @BeforeEach
    void setUp() {
        properties.getBaidu().setApiKey("test-ak");
        properties.getBaidu().setSecretKey("test-sk");

        RestClient.Builder builder = RestClient.builder().baseUrl(BaiduOcrConfig.BASE_URL);
        // ⚠️ 必须在 build() 之前 bind
        server = MockRestServiceServer.bindTo(builder).build();

        service = new BaiduOcrServiceImpl(properties, builder.build(), new ObjectMapper());
    }

    // ==================================================================
    // 正常路径
    // ==================================================================

    @Test
    @DisplayName("识别成功 → 逐行取出 words_result 里的文字")
    void recognize_returnsWordLines() {
        expectToken(TOKEN_JSON);
        server.expect(requestTo(startsWith(RECOGNIZE_URL)))
                .andRespond(withSuccess(WORDS_JSON, MediaType.APPLICATION_JSON));

        OcrResult result = service.recognize(image());

        assertTrue(result.isSuccess(), "正常响应应当解析成功");
        assertEquals(4, result.getLines().size(), "words_result 有几项就该有几行");
        assertEquals("¥8.00", result.getLines().get(1), "顺序要原样保留 —— 解析规则依赖它");
        server.verify();
    }

    @Test
    @DisplayName("识别 → token 缓存在服务端，两次识别只换一次")
    void recognize_reusesCachedToken() {
        /*
         * 只 expect 一次 token 请求。若服务把 token 用一次就丢，
         * 第二次识别会再发一次 token 请求，而 MockRestServiceServer
         * 会因为「没有更多预期的请求」直接让用例失败 —— 这正是这条断言的意义。
         */
        expectToken(TOKEN_JSON);
        server.expect(requestTo(startsWith(RECOGNIZE_URL)))
                .andRespond(withSuccess(WORDS_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(RECOGNIZE_URL)))
                .andRespond(withSuccess(WORDS_JSON, MediaType.APPLICATION_JSON));

        service.recognize(image());
        service.recognize(image());

        server.verify();
    }

    @Test
    @DisplayName("换 token → 请求里带上 grant_type 与两个凭据")
    void refreshToken_sendsClientCredentials() {
        server.expect(requestTo(containsString(TOKEN_URL)))
                .andExpect(request -> {
                    String uri = request.getURI().toString();
                    assertTrue(uri.contains("grant_type=client_credentials"), "百度鉴权要求固定这个值");
                    assertTrue(uri.contains("client_id=test-ak"), "API Key 要作为 client_id 传");
                    assertTrue(uri.contains("client_secret=test-sk"), "Secret Key 要作为 client_secret 传");
                })
                .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(RECOGNIZE_URL)))
                .andRespond(withSuccess(WORDS_JSON, MediaType.APPLICATION_JSON));

        service.recognize(image());

        server.verify();
    }

    // ==================================================================
    // 失败路径 —— 全部要变成「没识别出」，不许冒泡
    // ==================================================================

    @Test
    @DisplayName("识别接口返回错误码 → 失败结果里带上真实的 code 与描述")
    void recognize_reportsErrorCode() {
        expectToken(TOKEN_JSON);
        server.expect(requestTo(startsWith(RECOGNIZE_URL)))
                .andRespond(withSuccess("{\"error_code\":17,"
                                + "\"error_msg\":\"Open api daily request limit reached\"}",
                        MediaType.APPLICATION_JSON));

        OcrResult result = service.recognize(image());

        assertFalse(result.isSuccess(), "带 error_code 的响应必须当作失败");
        assertEquals(17, result.getErrcode(), "配额耗尽与「这张图没字」在排查时是两回事，"
                + "日志里要能看出是哪一种");
        server.verify();
    }

    @Test
    @DisplayName("服务端 5xx → 失败，不抛异常")
    void recognize_survivesServerError() {
        expectToken(TOKEN_JSON);
        server.expect(requestTo(startsWith(RECOGNIZE_URL))).andRespond(withServerError());

        OcrResult result = service.recognize(image());

        assertFalse(result.isSuccess(), "上游 5xx 要变成一次「没识别出」，而不是让上传接口崩掉");
        server.verify();
    }

    @Test
    @DisplayName("响应不是 JSON → 失败，不抛异常")
    void recognize_survivesMalformedResponse() {
        expectToken(TOKEN_JSON);
        server.expect(requestTo(startsWith(RECOGNIZE_URL)))
                .andRespond(withSuccess("<html>502 Bad Gateway</html>", MediaType.TEXT_HTML));

        OcrResult result = service.recognize(image());

        assertFalse(result.isSuccess(), "被网关拦下时返回的是一段 HTML，解析不了也不能抛");
        server.verify();
    }

    @Test
    @DisplayName("token 换不到 → 失败，且不去调识别接口")
    void recognize_failsWhenTokenUnavailable() {
        server.expect(requestTo(containsString(TOKEN_URL)))
                .andRespond(withSuccess("{\"error\":\"invalid_client\","
                        + "\"error_description\":\"unknown client id\"}", MediaType.APPLICATION_JSON));

        OcrResult result = service.recognize(image());

        assertFalse(result.isSuccess(), "凭据填错时要失败");
        server.verify();
    }

    @Test
    @DisplayName("图片为空 → 直接失败，一个请求都不发")
    void recognize_rejectsEmptyImage() {
        OcrResult result = service.recognize(new byte[0]);

        assertFalse(result.isSuccess(), "空图片没有识别的意义");
        server.verify();
    }

    // ==================================================================
    // token 被提前作废时的自愈
    // ==================================================================

    @Test
    @DisplayName("识别返回 110 → 清缓存、换新 token、重试一次")
    void recognize_retriesOnceAfterTokenRejected() {
        /*
         * 这是「OCR 从某天起永久静默失效」的那条防线。
         * access_token 名义上有 30 天，但控制台轮换密钥会让它提前作废，
         * 此后每个请求都稳定返回 110 —— 而表现只是「识别不出」。
         * 不重试的话，没有任何人能看出 OCR 已经死了。
         */
        expectToken(TOKEN_JSON);
        server.expect(requestTo(startsWith(RECOGNIZE_URL)))
                .andRespond(withSuccess(INVALID_TOKEN_JSON, MediaType.APPLICATION_JSON));
        // 重试前应当【换一个新的】token，而不是拿旧的再试一次
        server.expect(requestTo(containsString(TOKEN_URL)))
                .andRespond(withSuccess(SECOND_TOKEN_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(RECOGNIZE_URL)))
                .andRespond(withSuccess(WORDS_JSON, MediaType.APPLICATION_JSON));

        OcrResult result = service.recognize(image());

        assertTrue(result.isSuccess(), "换掉失效的 token 之后这次识别应当成功");
        assertEquals(4, result.getLines().size(), "重试拿到的结果要正常返回");
        server.verify();
    }

    @Test
    @DisplayName("识别返回 17（配额）→ 不重试，直接失败")
    void recognize_doesNotRetryOnQuotaError() {
        /*
         * 只有 token 类错误（110/111）才值得重试。配额耗尽时重试只是
         * 白花一次调用、结果一模一样 —— 而 MockRestServiceServer 会因为
         * 多出一次没人预期的请求而让用例失败。
         */
        expectToken(TOKEN_JSON);
        server.expect(requestTo(startsWith(RECOGNIZE_URL)))
                .andRespond(withSuccess("{\"error_code\":17,\"error_msg\":\"daily limit\"}",
                        MediaType.APPLICATION_JSON));

        OcrResult result = service.recognize(image());

        assertFalse(result.isSuccess(), "配额耗尽要如实失败");
        assertEquals(17, result.getErrcode(), "错误码要原样带出来");
        server.verify();
    }

    // ==================================================================
    // 配置校验
    // ==================================================================

    @Test
    @DisplayName("配了 baidu 却没填凭据 → 构造时直接抛异常")
    void constructor_failsFastWithoutCredentials() {
        OcrProperties blank = new OcrProperties();
        blank.setProvider("baidu");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new BaiduOcrServiceImpl(blank, RestClient.create(), new ObjectMapper()),
                "「以为配了其实没配」不会报任何错，只会静默地永远识别不出 —— "
                        + "必须在启动时就炸掉，让配置问题在部署那一刻暴露");

        assertTrue(e.getMessage().contains("uspace.ocr.baidu.api-key"),
                "异常消息要告诉运维该配哪个项，否则他只能去翻代码");
    }

    @Test
    @DisplayName("只填了一半凭据 → 同样抛异常")
    void constructor_failsFastWithPartialCredentials() {
        OcrProperties partial = new OcrProperties();
        partial.getBaidu().setApiKey("only-key");

        assertThrows(IllegalStateException.class,
                () -> new BaiduOcrServiceImpl(partial, RestClient.create(), new ObjectMapper()),
                "少一个 secret 也换不到 token，同样是「配了但没配全」");
    }

    // ==================================================================
    // 测试数据构造
    // ==================================================================

    /** 一段合法的 PNG 文件头，作为待识别的图片 */
    private static byte[] image() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52};
    }

    /**
     * 排一次「换 token」的预期。
     *
     * @param tokenJson 该次要返回的 token 响应
     */
    private void expectToken(String tokenJson) {
        server.expect(requestTo(containsString(TOKEN_URL)))
                .andRespond(withSuccess(tokenJson, MediaType.APPLICATION_JSON));
    }
}
