package com.kaede.uspace.order.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaede.uspace.order.PaymentChannel;
import com.kaede.uspace.order.PaymentGateway;
import com.kaede.uspace.order.PaymentProperties;
import com.kaede.uspace.order.dto.PaymentCreateCommand;
import com.kaede.uspace.order.dto.PaymentCreateResult;
import com.kaede.uspace.order.dto.PaymentNotifyRequest;
import com.kaede.uspace.order.dto.PaymentNotifyResult;
import com.kaede.uspace.order.dto.PaymentQueryResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 支付网关的<b>模拟实现</b>，不访问外网、不依赖任何支付平台的账号。
 *
 * <p>设计目标与门锁的模拟实现一致：让「发起支付 → 模拟用户在收银台付款 →
 * 回调后端 → 订单转已支付」这条链路可以脱离外网跑通，用于开发与答辩演示。
 * 同时<b>保留真实接口的语义与约束</b>，使得将来换成真实实现时上层代码无需改动。
 *
 * <p>刻意保留的真实约束：
 * <ol>
 *   <li><b>三条通道的产物各不相同</b> —— JSAPI 给 {@code prepayId}、
 *       H5 给 {@code h5Url}、WAP 给一段自动提交的表单 HTML</li>
 *   <li><b>金额按「分」传输</b> —— 微信与支付宝的接口都是如此，
 *       元与分的换算只在 {@link #toCents} / {@link #toYuan} 两处发生</li>
 *   <li><b>回调要验签</b> —— 且签名针对的是原始报文串，不是解析后的对象</li>
 *   <li><b>网络调用可能失败</b> —— 用 {@code uspace.payment.mock.failure-rate} 注入</li>
 *   <li><b>回调可能丢失</b> —— 用 {@code uspace.payment.mock.notify-drop-rate} 注入，
 *       否则「主动查单补偿」这条路径在演示时永远走不到</li>
 * </ol>
 *
 * <p><b>与真实实现的一处重要差异（必须在论文与答辩中说清）</b>：本类用
 * <b>HMAC-SHA256 对称密钥</b>做签名与验签，而真实的微信支付是
 * <b>RSA 私钥签名 + 平台公钥验签</b>、支付宝是 <b>RSA2</b>，
 * 且微信的报文还要用 API v3 密钥做 <b>AES-GCM 解密</b>。
 * 这里用对称密钥是因为模拟环境没有证书体系 ——
 * 但<b>「先签名、后验签、验不过一律拒绝」这个流程是完全照着真实链路走的</b>，
 * 换成真实实现时只替换本类的四个方法，{@code PaymentService} 一行不用改。
 *
 * <p>支付记录仅保存在内存中，进程重启即清空。
 *
 * @see PaymentGateway
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "uspace.payment", name = "provider",
        havingValue = "mock", matchIfMissing = true)
public class MockPaymentGatewayImpl implements PaymentGateway {

    /** 模拟签名用的对称密钥。真实实现中是商户私钥与平台公钥，不会出现在代码里 */
    private static final String SIGN_KEY = "uspace-mock-payment-sign-key";

    /** 支付宝回调用到的时间格式 */
    private static final DateTimeFormatter ALIPAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 模拟产生的支付单，key 为商户订单号 */
    private final Map<String, MockPayment> payments = new ConcurrentHashMap<>();

    /** 随机源。用 SecureRandom 与真实场景保持一致的安全习惯 */
    private final SecureRandom random = new SecureRandom();

    private final PaymentProperties properties;
    private final ObjectMapper objectMapper;

    public MockPaymentGatewayImpl(PaymentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        log.info("[Mock支付] 模拟实现已启用（provider=mock），不会访问真实支付平台");
    }

    // ==================================================================
    // 下单
    // ==================================================================

    /**
     * {@inheritDoc}
     *
     * <p>模拟实现：把支付单记进内存，按通道产出对应的支付凭据，不产生任何网络调用。
     */
    @Override
    public PaymentCreateResult createPayment(PaymentCreateCommand command) {
        if (!simulateNetwork("支付下单")) {
            return PaymentCreateResult.fail(-1, "模拟网络异常：支付下单失败");
        }
        if (!PaymentChannel.isOnline(command.getChannel().name())) {
            return PaymentCreateResult.fail(-1, "该通道不支持线上支付：" + command.getChannel());
        }

        payments.put(command.getOutTradeNo(), new MockPayment(
                command.getOutTradeNo(), command.getAmount(), command.getChannel(), LocalDateTime.now()));

        // 模拟模式下所有通道都指向同一个支付接口。前端识别到 mockPayUrl 非空
        // 就知道该走模拟收银台，不会真的去跳转 h5Url 或提交表单
        String payUrl = properties.getMock().getPayPath();

        PaymentCreateResult result = PaymentCreateResult.ok();
        switch (command.getChannel()) {
            case WXPAY_JSAPI -> result.setPrepayId("mock_prepay_" + command.getOutTradeNo());
            case WXPAY_H5 -> result.setH5Url(payUrl);
            case ALIPAY_WAP -> result.setFormHtml(alipayForm(payUrl, command.getOutTradeNo()));
            case QR_UPLOAD -> {
                // 上面已挡掉，这里只是让 switch 穷举完整
            }
        }
        result.setMockPayUrl(payUrl);

        log.info("[Mock支付] 下单 outTradeNo={} 通道={} 金额={} 元",
                command.getOutTradeNo(), command.getChannel(), command.getAmount());
        return result;
    }

    // ==================================================================
    // 查单
    // ==================================================================

    /**
     * {@inheritDoc}
     *
     * <p>模拟实现：从内存里读这笔支付单的状态。
     */
    @Override
    public PaymentQueryResult queryPayment(String outTradeNo, PaymentChannel channel) {
        if (!simulateNetwork("支付查单")) {
            return PaymentQueryResult.fail("模拟网络异常：支付查单失败");
        }
        MockPayment payment = payments.get(outTradeNo);
        if (payment == null) {
            return PaymentQueryResult.fail("商户订单号不存在：" + outTradeNo);
        }

        PaymentQueryResult result = new PaymentQueryResult();
        result.setSuccess(true);
        result.setOutTradeNo(outTradeNo);
        result.setAmount(payment.amount());
        result.setPaid(payment.paid());
        result.setTransactionNo(payment.transactionNo());
        result.setPaidAt(payment.paidAt());
        return result;
    }

    // ==================================================================
    // 回调：验签与解析
    // ==================================================================

    /**
     * {@inheritDoc}
     *
     * <p>模拟实现的验签流程与真实一致（用原始报文串重算签名再比对），
     * 只是把 RSA 换成了 HMAC。真实实现还需要对 {@code resource} 做 AES-GCM 解密，
     * 模拟实现直接传明文。
     */
    @Override
    public PaymentNotifyResult verifyAndParseWxpay(PaymentNotifyRequest request) {
        String timestamp = headerOf(request, "Wechatpay-Timestamp");
        String nonce = headerOf(request, "Wechatpay-Nonce");
        String signature = headerOf(request, "Wechatpay-Signature");
        String body = request.getBody();

        if (timestamp == null || nonce == null || signature == null || body == null) {
            return failure("微信回调缺少签名请求头或报文主体");
        }
        if (!signatureMatches(sign(timestamp + "\n" + nonce + "\n" + body + "\n"), signature)) {
            return failure("微信回调验签不通过");
        }

        try {
            JsonNode resource = objectMapper.readTree(body).path("resource");
            PaymentNotifyResult result = new PaymentNotifyResult();
            result.setSuccess(true);
            result.setOutTradeNo(resource.path("out_trade_no").asText(null));
            result.setTransactionNo(resource.path("transaction_id").asText(null));
            result.setAmount(toYuan(resource.path("amount").path("total").asLong()));
            result.setChannel(channelOfTradeType(resource.path("trade_type").asText("")));
            result.setPaid("SUCCESS".equals(resource.path("trade_state").asText()));
            result.setPaidAt(parseIsoTime(resource.path("success_time").asText(null)));
            return result;
        } catch (Exception e) {
            log.warn("[Mock支付] 微信回调报文解析失败", e);
            return failure("微信回调报文格式不正确");
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>模拟实现同样按真实规则拼接待验签串：<b>排除 {@code sign} 与
     * {@code sign_type}</b>，其余字段按字典序拼成 {@code k=v&k=v}。
     * 真实实现要把 HMAC 换成 RSA2。
     */
    @Override
    public PaymentNotifyResult verifyAndParseAlipay(PaymentNotifyRequest request) {
        Map<String, String> params = request.getParams();
        if (params == null || params.isEmpty()) {
            return failure("支付宝回调缺少表单参数");
        }
        String signature = params.get("sign");
        if (signature == null || signature.isBlank()) {
            return failure("支付宝回调缺少签名");
        }
        if (!signatureMatches(sign(signContentOf(params)), signature)) {
            return failure("支付宝回调验签不通过");
        }

        PaymentNotifyResult result = new PaymentNotifyResult();
        result.setSuccess(true);
        result.setOutTradeNo(params.get("out_trade_no"));
        result.setTransactionNo(params.get("trade_no"));
        result.setAmount(parseYuan(params.get("total_amount")));
        result.setChannel(PaymentChannel.ALIPAY_WAP);
        result.setPaid("TRADE_SUCCESS".equals(params.get("trade_status"))
                || "TRADE_FINISHED".equals(params.get("trade_status")));
        result.setPaidAt(parseAlipayTime(params.get("gmt_payment")));
        result.setAttach(params.get("passback_params"));
        return result;
    }

    // ==================================================================
    // 以下为模拟实现专有方法，不属于 PaymentGateway 接口
    // ==================================================================

    /**
     * 【仅模拟实现】模拟用户在收银台完成支付，返回一条可用于回调的报文。
     *
     * <p>真实场景下这个动作发生在微信或支付宝的页面上，然后由平台服务器
     * 回调本系统。模拟实现没有那个页面，所以提供本方法供收银台与测试触发 ——
     * 但它<b>返回的是报文而不是直接改状态</b>，调用方必须把它喂给
     * {@code PaymentService} 的回调处理方法，走完整的验签与幂等流程。
     * 这一点是刻意的：若这里直接改订单状态，演示时回调链路上的分支就永远走不到。
     *
     * <p>注意本方法<b>不在 {@link PaymentGateway} 接口中</b>，
     * 业务代码不应依赖它，否则将来切换真实实现会编译失败。仅供演示与测试使用。
     *
     * @param outTradeNo 商户订单号
     * @return 一条已签名的回调报文，可直接交给回调处理方法
     * @throws IllegalArgumentException 支付单不存在或已完成支付时抛出
     */
    public PaymentNotifyRequest simulatePay(String outTradeNo) {
        MockPayment payment = payments.get(outTradeNo);
        if (payment == null) {
            throw new IllegalArgumentException("模拟支付失败：商户订单号不存在 " + outTradeNo);
        }
        if (payment.paid()) {
            throw new IllegalArgumentException("该支付单已完成支付：" + outTradeNo);
        }

        String transactionNo = "MOCK" + System.currentTimeMillis() + random.nextInt(1000);
        LocalDateTime paidAt = LocalDateTime.now();
        MockPayment paid = payment.paid(transactionNo, paidAt);
        payments.put(outTradeNo, paid);

        log.info("[Mock支付] 模拟用户完成支付 outTradeNo={} 交易号={} 金额={} 元",
                outTradeNo, transactionNo, payment.amount());

        return payment.channel() == PaymentChannel.ALIPAY_WAP
                ? buildAlipayNotify(paid)
                : buildWxpayNotify(paid);
    }

    /**
     * 【仅模拟实现】判断本次回调是否应当被丢弃。
     *
     * <p>用于演示「钱付了、回调没到」—— 这是生产环境的必然事件
     * （网络抖动、平台重试耗尽、服务重启窗口）。设为必定丢弃后，
     * 支付成功但订单仍停在待支付，点一下「查询支付状态」就能补上。
     *
     * @return true 表示本次回调应当被丢弃
     */
    public boolean shouldDropNotify() {
        double rate = properties.getMock().getNotifyDropRate();
        return rate > 0 && random.nextDouble() < rate;
    }

    // ==================================================================
    // 报文构造
    // ==================================================================

    /**
     * 构造一条微信支付成功回调报文。
     *
     * <p>结构照着微信 API v3 的 {@code TRANSACTION.SUCCESS} 通知来：
     * 外层是事件类型，{@code resource} 里是交易详情。真实实现中
     * {@code resource} 是 AES-GCM 加密的密文，这里直接给明文。
     *
     * @param payment 已完成的支付单
     * @return 带签名的回调报文
     */
    private PaymentNotifyRequest buildWxpayNotify(MockPayment payment) {
        long cents = toCents(payment.amount());
        String body = """
                {"event_type":"TRANSACTION.SUCCESS","resource_type":"encrypt-resource","resource":{\
                "out_trade_no":"%s","transaction_id":"%s","trade_state":"SUCCESS","trade_type":"%s",\
                "success_time":"%s","amount":{"total":%d,"payer_total":%d}}}"""
                .formatted(payment.outTradeNo(), payment.transactionNo(),
                        tradeTypeOf(payment.channel()),
                        payment.paidAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                        cents, cents);

        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = randomHex();

        PaymentNotifyRequest request = new PaymentNotifyRequest();
        request.setChannel(payment.channel());
        request.setBody(body);
        request.setHeaders(Map.of(
                "Wechatpay-Timestamp", timestamp,
                "Wechatpay-Nonce", nonce,
                "Wechatpay-Signature", sign(timestamp + "\n" + nonce + "\n" + body + "\n"),
                "Wechatpay-Serial", "MOCK_CERT_SERIAL"));
        return request;
    }

    /**
     * 构造一条支付宝交易成功通知。
     *
     * <p>结构照着支付宝异步通知的表单字段来。真实的支付宝用 RSA2 签名，
     * 且 {@code passback_params} <b>只在异步通知里回传</b>（同步通知不返回）——
     * 要往回调里带自定义参数就得走这个字段，而不是拼到 {@code return_url} 上。
     *
     * @param payment 已完成的支付单
     * @return 带签名的回调报文
     */
    private PaymentNotifyRequest buildAlipayNotify(MockPayment payment) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("out_trade_no", payment.outTradeNo());
        params.put("trade_no", payment.transactionNo());
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("total_amount", payment.amount().toPlainString());
        params.put("gmt_payment", payment.paidAt().format(ALIPAY_TIME));
        params.put("passback_params", "");
        params.put("sign_type", "RSA2");
        params.put("sign", sign(signContentOf(params)));

        PaymentNotifyRequest request = new PaymentNotifyRequest();
        request.setChannel(PaymentChannel.ALIPAY_WAP);
        request.setParams(params);
        return request;
    }

    /**
     * 构造模拟支付宝的自动提交表单。
     *
     * <p>支付宝的手机网站支付不给链接，给的是一整段带签名的表单 HTML ——
     * 前端把它插入页面并提交，浏览器就会带着表单去拉起支付宝 App。
     *
     * @param actionUrl  表单提交地址（模拟实现下即本系统的模拟支付接口）
     * @param outTradeNo 商户订单号
     * @return 自动提交的表单 HTML
     */
    private static String alipayForm(String actionUrl, String outTradeNo) {
        return "<form id=\"alipay-mock-form\" action=\"" + actionUrl + "\" method=\"post\">"
                + "<input type=\"hidden\" name=\"out_trade_no\" value=\"" + outTradeNo + "\"/>"
                + "</form><script>document.forms['alipay-mock-form'].submit();</script>";
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /**
     * 模拟一次网络调用：按配置注入延迟与随机失败。
     *
     * @param action 操作名称，仅用于日志
     * @return true 表示本次调用成功
     */
    private boolean simulateNetwork(String action) {
        PaymentProperties.Mock mock = properties.getMock();

        if (mock.getLatencyMillis() > 0) {
            try {
                Thread.sleep(mock.getLatencyMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[Mock支付] {}被中断", action);
                return false;
            }
        }

        if (mock.getFailureRate() > 0 && random.nextDouble() < mock.getFailureRate()) {
            log.warn("[Mock支付] 模拟网络异常，{}失败", action);
            return false;
        }
        return true;
    }

    /**
     * 计算待签名字符串的签名。
     *
     * <p>真实实现里微信是 RSA 私钥签名、支付宝是 RSA2 签名，模拟实现用 HMAC-SHA256 代替。
     *
     * @param content 待签名内容
     * @return 十六进制小写的签名
     */
    private static String sign(String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SIGN_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // HMAC-SHA256 是 JDK 必备算法，走到这里说明运行环境有问题
            throw new IllegalStateException("模拟签名失败", e);
        }
    }

    /**
     * 常量时间比对签名。
     *
     * @param expected 本地重算的签名
     * @param actual   报文里携带的签名
     * @return 一致返回 true
     */
    private static boolean signatureMatches(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 取支付宝的待验签串：排除 {@code sign} 与 {@code sign_type}，
     * 其余字段按<b>字典序</b>拼成 {@code k=v&k=v}（末尾不带 {@code &}）。
     *
     * <p>这几条规则都不是可选的：顺序错了、多带一个字段、
     * 末尾多一个 {@code &}，验签都会失败，而失败信息不会告诉你错在哪。
     *
     * @param params 表单参数
     * @return 待验签串
     */
    private static String signContentOf(Map<String, String> params) {
        return params.entrySet().stream()
                .filter(e -> !"sign".equals(e.getKey()) && !"sign_type".equals(e.getKey()))
                .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + "&" + b)
                .orElse("");
    }

    /**
     * 元换算为分。
     *
     * <p>微信与支付宝的接口都以「分」为单位收金额，而本系统全程用「元」的
     * {@link BigDecimal}。换算只在这一处发生 —— 让每个调用方各自记得乘 100
     * 是不可靠的，忘了就是少收 100 倍的钱，而且不报任何错。
     *
     * @param yuan 金额（元）
     * @return 金额（分）
     */
    private static long toCents(BigDecimal yuan) {
        return yuan.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * 分换算为元。
     *
     * @param cents 金额（分）
     * @return 金额（元），标度 2
     */
    private static BigDecimal toYuan(long cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2);
    }

    /**
     * 解析元金额字符串。解析不了时返回 null —— 让调用方按「金额对不上」处理，
     * 这比给一个默认值 0 要安全得多（0 元会被当成「免费」）。
     *
     * @param text 金额文本
     * @return 金额；解析失败返回 null
     */
    private static BigDecimal parseYuan(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 取请求头，不区分大小写。
     *
     * @param request 回调报文
     * @param name    头名称
     * @return 头值；不存在时返回 null
     */
    private static String headerOf(PaymentNotifyRequest request, String name) {
        if (request.getHeaders() == null) {
            return null;
        }
        return request.getHeaders().entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    /**
     * 把微信的 {@code trade_type} 映射为本系统的通道。
     *
     * @param tradeType 微信的交易类型
     * @return 通道；认不出时按 H5 处理（都是微信侧的通道）
     */
    private static PaymentChannel channelOfTradeType(String tradeType) {
        return "JSAPI".equalsIgnoreCase(tradeType)
                ? PaymentChannel.WXPAY_JSAPI
                : PaymentChannel.WXPAY_H5;
    }

    /**
     * 把通道映射为微信的 {@code trade_type}。
     *
     * @param channel 通道
     * @return 微信的交易类型
     */
    private static String tradeTypeOf(PaymentChannel channel) {
        return channel == PaymentChannel.WXPAY_JSAPI ? "JSAPI" : "MWEB";
    }

    /**
     * 解析 ISO 格式的时间。
     *
     * @param text 时间文本
     * @return 时间；解析失败返回 null
     */
    private static LocalDateTime parseIsoTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析支付宝格式的时间。
     *
     * @param text 时间文本
     * @return 时间；解析失败返回 null
     */
    private static LocalDateTime parseAlipayTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text, ALIPAY_TIME);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 生成一个随机十六进制串，用作回调的 nonce。
     *
     * @return 16 位十六进制串
     */
    private String randomHex() {
        byte[] bytes = new byte[8];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * 构造一个验签失败的结果。
     *
     * @param errmsg 失败原因
     * @return 失败的结果对象
     */
    private static PaymentNotifyResult failure(String errmsg) {
        PaymentNotifyResult result = new PaymentNotifyResult();
        result.setSuccess(false);
        result.setErrmsg(errmsg);
        return result;
    }

    /**
     * 模拟实现中记录的一笔支付单。
     *
     * <p>用不可变 record：状态变化通过 {@link #paid} 产生新实例再放回 Map，
     * 避免多线程下读到「一半更新」的对象。
     *
     * @param outTradeNo    商户订单号
     * @param amount        金额（元）
     * @param channel       通道
     * @param createdAt     下单时刻
     * @param paid          是否已支付
     * @param transactionNo 平台交易号，未支付时为 null
     * @param paidAt        支付完成时刻，未支付时为 null
     */
    private record MockPayment(String outTradeNo, BigDecimal amount, PaymentChannel channel,
                               LocalDateTime createdAt, boolean paid, String transactionNo,
                               LocalDateTime paidAt) {

        /**
         * 新建一笔待支付的单。
         *
         * @param outTradeNo 商户订单号
         * @param amount     金额（元）
         * @param channel    通道
         * @param createdAt  下单时刻
         */
        MockPayment(String outTradeNo, BigDecimal amount, PaymentChannel channel,
                    LocalDateTime createdAt) {
            this(outTradeNo, amount, channel, createdAt, false, null, null);
        }

        /**
         * 返回一个「已支付」的新实例。
         *
         * @param transactionNo 平台交易号
         * @param paidAt        支付完成时刻
         * @return 新的支付单记录
         */
        MockPayment paid(String transactionNo, LocalDateTime paidAt) {
            return new MockPayment(outTradeNo, amount, channel, createdAt, true, transactionNo, paidAt);
        }
    }
}
