package com.kaede.uspace.order;

import com.kaede.uspace.order.dto.PaymentNotifyRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 支付回调端点（模块 8）。
 *
 * <p><b>这两个端点是匿名可访问的</b>（见 {@code SecurityConfig} 的公开路径列表）：
 * 支付平台的服务器发起回调时当然不带本系统的 JWT。但鉴权并没有被取消，
 * 而是<b>换了一种</b> —— 改由支付平台的<b>签名验证</b>承担，
 * 验签不过一律拒绝处理。
 *
 * <p><b>两个端点不能合并成一个</b>：微信的 API v3 是
 * 「RSA 验签 + AES-GCM 解密」，支付宝是「表单参数 + RSA2 验签」，
 * 协议完全不同。但验签通过之后，两者走的是 {@code PaymentService} 里
 * 同一段处理逻辑（幂等 → 金额核对 → 状态流转 → 累加用户消费额）。
 *
 * <p><b>应答必须是平台自己的协议格式，不能用本系统的 {@code ApiResult}</b> ——
 * 平台只认自己那套（微信 {@code {"code":"SUCCESS"}}、支付宝纯文本 {@code success}），
 * 给它一个 {@code {"code":0,...}} 它只会当作处理失败并一直重推。
 * 所以这里的两个方法体<b>不是「一行 return」的标准写法</b>，
 * 而是各自 try/catch 把异常翻译成平台协议 —— 与 {@code MockOpenController}
 * 方法体不是一行是同一类理由。
 */
@Slf4j
@RestController
@RequestMapping("/api/payments/notify")
public class PaymentNotifyController {

    /** 微信回调成功应答。字段名与取值由微信 API v3 规定，不能改 */
    private static final String WXPAY_ACK_SUCCESS = "{\"code\":\"SUCCESS\",\"message\":\"成功\"}";

    /** 微信回调失败应答。返回非 2xx 会让微信继续重推 */
    private static final String WXPAY_ACK_FAIL = "{\"code\":\"FAIL\",\"message\":\"处理失败\"}";

    /** 支付宝回调成功应答。是一个纯文本单词，不是 JSON */
    private static final String ALIPAY_ACK_SUCCESS = "success";

    /** 支付宝回调失败应答 */
    private static final String ALIPAY_ACK_FAIL = "failure";

    private final PaymentService paymentService;

    public PaymentNotifyController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * 接收微信支付回调（JSAPI 与 H5 共用这一个端点）。
     *
     * <p>报文体用 {@code String} 接而不是 DTO —— <b>验签必须用未经处理的原始报文串</b>。
     * 一旦先反序列化成对象再重新序列化，字段顺序与空白字符都可能变，
     * 签名就对不上了，而这类问题表现为「回调一直验签失败」且看不出原因。
     *
     * @param body    原始报文体
     * @param headers 全部请求头，从中取签名相关的四个
     * @return 微信协议格式的应答
     */
    @PostMapping("/wxpay")
    public ResponseEntity<String> wxpay(@RequestBody String body,
                                        @RequestHeader Map<String, String> headers) {
        try {
            PaymentNotifyRequest request = new PaymentNotifyRequest();
            request.setBody(body);
            request.setHeaders(headers);
            // channel 不在这里设：实际是 JSAPI 还是 H5 由报文里的 trade_type 判定

            boolean ok = paymentService.handleWxpayNotify(request);
            return ok ? ResponseEntity.ok(WXPAY_ACK_SUCCESS)
                    : ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(WXPAY_ACK_FAIL);
        } catch (Exception e) {
            // 异常绝不能落到 GlobalExceptionHandler —— 那会返回 ApiResult 结构，
            // 微信无法解析，只会当作失败并一直重推，而重推依然会失败
            log.error("[支付] 微信回调处理异常", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(WXPAY_ACK_FAIL);
        }
    }

    /**
     * 接收支付宝异步通知。
     *
     * <p>支付宝发的是表单（{@code application/x-www-form-urlencoded}），
     * 所以用 {@code @RequestParam Map} 接，保持原始键值 ——
     * 验签要把除 {@code sign} 与 {@code sign_type} 之外的字段按字典序拼接，
     * 少一个字段、多一个字段、顺序错了都会失败。
     *
     * <p><b>最终支付结果以这个异步通知为准</b>，不看 {@code return_url} 的前台回跳 ——
     * iOS 上从支付宝 App 返回时可能根本不跳转，官方文档也明确了这一点。
     *
     * @param params 表单参数
     * @return 支付宝协议格式的应答（纯文本）
     */
    @PostMapping("/alipay")
    public ResponseEntity<String> alipay(@RequestParam Map<String, String> params) {
        try {
            PaymentNotifyRequest request = new PaymentNotifyRequest();
            request.setParams(params);

            boolean ok = paymentService.handleAlipayNotify(request);
            return ok ? ResponseEntity.ok(ALIPAY_ACK_SUCCESS)
                    : ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ALIPAY_ACK_FAIL);
        } catch (Exception e) {
            log.error("[支付] 支付宝回调处理异常", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ALIPAY_ACK_FAIL);
        }
    }
}
