package com.kaede.uspace.order;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.security.UserPrincipal;
import com.kaede.uspace.order.dto.CreatePaymentRequest;
import com.kaede.uspace.order.dto.PaymentCreateVo;
import com.kaede.uspace.order.dto.PaymentStatusVo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付接口（模块 8）。
 *
 * <p><b>这是一个统一入口，不对订单或包场分别开口</b>：钱从哪张表来由
 * {@link PaymentTargetType} 区分。这样加一类收款（如模块 9 的月卡）
 * 不必新增路径，也避免了「包场付款挂 space 包、而 space 又要依赖 order」的包级循环。
 *
 * <p>回调端点不在这里 —— 它们在 {@code PaymentNotifyController}，
 * 且是匿名可达的（支付平台不带 JWT）。
 */
@RestController
@RequestMapping("/api/payments")
@Validated
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * 发起支付。
     *
     * <p>返回体里按通道给出对应的支付参数：JSAPI 给 {@code prepayId}、
     * H5 给 {@code h5Url}、WAP 给一段自动提交的表单 HTML。
     * 模拟模式下还会多一个非空的 {@code mockPayUrl}，前端据此弹出模拟收银台。
     *
     * <p><b>应当在用户点「去支付」时才调</b>，不要在订单创建时就调 ——
     * JSAPI 的凭据有效期 2 小时、H5 的链接更是只有 5 分钟，
     * 提前下单只会让它在用户还没决定付款时就过期。
     *
     * @param request     支付目标与通道
     * @param me          当前登录用户
     * @param httpRequest 原始请求，用于取用户 IP（微信 H5 支付的风控参数）
     * @return 各通道的支付参数
     */
    @PostMapping
    public ResponseEntity<ApiResult<PaymentCreateVo>> create(@Valid @RequestBody CreatePaymentRequest request,
                                                              @AuthenticationPrincipal UserPrincipal me,
                                                              HttpServletRequest httpRequest) {
        return ApiResult.of(paymentService.createPayment(me.id(), request, clientIpOf(httpRequest)));
    }

    /**
     * 查询支付状态，必要时向支付平台补偿。
     *
     * <p>用于「我付过了但订单还是待支付」—— 支付成功而回调丢失在生产环境
     * 是必然事件，这个接口是它的兜底。查到平台已收款而本地未更新时，
     * 会走与回调完全相同的处理逻辑补上，所以调一次就能修好。
     *
     * <p>只能查自己的支付单；查不到与不属于自己返回同一个 404，
     * 避免被用来枚举别人的单号。
     *
     * @param outTradeNo 商户订单号（订单号或包场单号）
     * @param channel    当时使用的通道，决定查哪个平台
     * @param me         当前登录用户
     * @return 支付状态
     */
    @GetMapping("/{outTradeNo}")
    public ResponseEntity<ApiResult<PaymentStatusVo>> query(@PathVariable String outTradeNo,
                                                            @RequestParam(required = false) PaymentChannel channel,
                                                            @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(paymentService.queryPayment(me.id(), outTradeNo, channel));
    }

    /**
     * 取用户端 IP。
     *
     * <p>优先读 {@code X-Forwarded-For}：生产环境前面会有 Nginx 一类的反向代理，
     * 此时 {@code getRemoteAddr()} 拿到的是代理的地址，不是用户的。
     * 该头可能是「客户端, 代理1, 代理2」的链式值，取第一段。
     *
     * <p>注意这个头是客户端可以伪造的 —— 它只用于支付平台的风控参考，
     * 不能作为任何安全判断的依据。
     *
     * @param request 原始请求
     * @return 用户端 IP
     */
    private static String clientIpOf(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
