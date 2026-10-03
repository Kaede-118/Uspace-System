package com.kaede.uspace.order;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.security.UserPrincipal;
import com.kaede.uspace.order.dto.CreatePaymentRequest;
import com.kaede.uspace.order.dto.PayChannelVo;
import com.kaede.uspace.order.dto.PayQrVo;
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

import java.util.List;

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
    private final PaymentChannelService paymentChannelService;
    private final PayQrService payQrService;

    public PaymentController(PaymentService paymentService,
                             PaymentChannelService paymentChannelService,
                             PayQrService payQrService) {
        this.paymentService = paymentService;
        this.paymentChannelService = paymentChannelService;
        this.payQrService = payQrService;
    }

    /**
     * 列出当前门店启用中的收款码，供扫码转账的收银台展示。
     *
     * <p><b>与 {@link #channels} 是两个接口而不是一个</b>：通道是「店收不收这种钱」，
     * 收款码是「钱扫到哪张图上」，两者的更新时机完全不同 ——
     * 通道由部署配置决定（改一次配置重启），收款码由运营在后台随时改。
     * 合并成一个接口会让前者的缓存策略被后者拖累。
     *
     * <p><b>需登录</b>：收款码贴在店里谁都能看见，但没必要给爬虫抓 ——
     * 而它出现的场景（结账页、订单详情页）本来就都在登录态之后。
     *
     * <p>门店一张码都没配时返回<b>空列表</b>：那是真实的运营状态
     * （刚部署完还没配），前端要提示「请联系管理员配置收款方式」而不是白屏。
     *
     * @return 启用中的收款码，按 sort 升序
     */
    @GetMapping("/qr")
    public ResponseEntity<ApiResult<List<PayQrVo>>> payQrs() {
        return ResponseEntity.ok(ApiResult.ok(payQrService.listEnabled()));
    }

    /**
     * 列出某类收款当前可用的支付通道。
     *
     * <p><b>收银台的选项由这里给，前端不要写死</b>：哪些通道开放是部署配置
     * （{@code uspace.payment.enabled-channels}），哪类收款受理哪些通道是业务规则 ——
     * 两者都只有服务端知道。前端自己维护一份清单，就会出现「配置改了页面没跟着变」
     * 以及「选项在那里、点了却报错」。
     *
     * <p><b>路径与 {@link #query} 的 {@code /{outTradeNo}} 同前缀，这是安全的</b>：
     * Spring 的路径匹配里字面量优先于模板变量，所以 {@code /channels} 不会
     * 被当成一个叫「channels」的订单号。反过来说，若把本方法改到别的路径上，
     * 就得同时确认没有别的东西依赖这个优先级 —— 保持现状最省事。
     *
     * @param targetType 收款类型（订单 / 包场 / 月卡 / 商品），决定过滤掉哪些不受理的通道
     * @return 可用通道列表，可为空列表
     */
    @GetMapping("/channels")
    public ResponseEntity<ApiResult<List<PayChannelVo>>> channels(@RequestParam PaymentTargetType targetType) {
        return ResponseEntity.ok(ApiResult.ok(paymentChannelService.listFor(targetType)));
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
