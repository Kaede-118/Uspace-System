package com.kaede.uspace.order;

import com.kaede.uspace.order.dto.PaymentCreateCommand;
import com.kaede.uspace.order.dto.PaymentCreateResult;
import com.kaede.uspace.order.dto.PaymentNotifyRequest;
import com.kaede.uspace.order.dto.PaymentNotifyResult;
import com.kaede.uspace.order.dto.PaymentQueryResult;
import com.kaede.uspace.order.dto.RefundCommand;
import com.kaede.uspace.order.dto.RefundResult;

/**
 * 支付网关接口。
 *
 * <p><b>接口按微信支付 API v3 与支付宝开放平台的真实规格设计，当前下落在模拟实现。</b>
 * 这与门锁模块（{@link com.kaede.uspace.lock.LockService}）是同一套思路：
 * 方法名、入参、返回字段都照着真实接口的形状来，将来切成真实调用时，
 * 只替换实现类，Service、Controller、DTO、数据模型都不动。
 * 由配置项 {@code uspace.payment.provider}（{@code mock} / {@code real}）切换。
 *
 * <p><b>与门锁不同的是，这里的开关只有两档</b>，不像门锁那样按通道各配一个 ——
 * 通道（JSAPI / H5 / WAP）是运行时按用户所处的浏览器环境决定的，
 * 不是部署时的配置项，同一个部署要同时支持三条。
 *
 * <p><b>所有方法都不抛业务异常，失败一律用返回对象表达</b>（{@code success=false}
 * 加错误码与描述）。理由与门锁模块相同：调用方要的是「能不能继续」，
 * 而异常会把「支付平台拒绝」与「我们自己代码有 bug」混成同一类东西，
 * 在日志里分不开。
 *
 * <p><b>金额单位统一为「元」</b>（{@link java.math.BigDecimal}），
 * 与分之间的换算由实现类内部完成 —— 微信与支付宝的接口都收「分」，
 * 但让 {@code × 100} 只在一处发生，比让每个调用方各自记得乘要可靠得多。
 */
public interface PaymentGateway {

    /**
     * 向支付平台下单。
     *
     * <p>对应真实的三个接口：微信 {@code POST /v3/pay/transactions/jsapi}、
     * {@code POST /v3/pay/transactions/h5}、支付宝 {@code alipay.trade.wap.pay}。
     * 用哪个由 {@code command.channel} 决定。
     *
     * <p><b>调用时机是「用户点去支付时」，不是「订单创建时」</b>：
     * JSAPI 的 {@code prepay_id} 有效期只有 2 小时、H5 的 {@code h5_url} 更是只有
     * 5 分钟，提前下单只会让它在用户还没决定付款时就白白过期。
     *
     * @param command 下单参数
     * @return 下单结果。成功时按通道在 {@code prepayId} / {@code h5Url} /
     *         {@code formHtml} 之一返回可用凭据
     */
    PaymentCreateResult createPayment(PaymentCreateCommand command);

    /**
     * 验签并解析微信回调。
     *
     * <p>真实的微信 API v3 回调要做两件事：用平台证书对
     * {@code Wechatpay-Signature} 等请求头做 <b>RSA 验签</b>，
     * 再用 API v3 密钥对报文里的 {@code resource} 做 <b>AES-GCM 解密</b>
     * （报文是加密的，不是明文 JSON）。签名针对的是<b>原始报文串</b>，
     * 所以入参必须未经反序列化处理，见 {@code PaymentNotifyRequest} 的说明。
     *
     * <p>微信的 JSAPI 与 H5 共用这一个方法，也共用一个回调端点 ——
     * 它们是同一个 API v3 协议，报文结构完全一致。
     *
     * @param request 原始回调报文
     * @return 验签结果。{@code success=false} 时一律拒绝处理
     */
    PaymentNotifyResult verifyAndParseWxpay(PaymentNotifyRequest request);

    /**
     * 验签并解析支付宝异步通知。
     *
     * <p>真实的支付宝回调是表单形式（{@code application/x-www-form-urlencoded}），
     * 验签要<b>排除 {@code sign} 与 {@code sign_type} 两个字段</b>后，
     * 把其余字段按字典序拼接再用支付宝公钥做 RSA2 验签。
     *
     * <p>协议与微信完全不同，所以回调端点也不能合并成一个。
     */
    PaymentNotifyResult verifyAndParseAlipay(PaymentNotifyRequest request);

    /**
     * 发起退款（原路退回）。
     *
     * <p>对应微信 {@code POST /v3/refund/domestic/refunds} 与支付宝
     * {@code alipay.trade.refund}：钱沿着用户当初付款的那条通道退回去。
     *
     * <p><b>幂等由「商户退款单号」保证</b>：同一个 {@code refundNo} 重复请求，
     * 平台只会退一次。这是这道防线里唯一可靠的一层 —— 本地的状态守卫
     * （{@code WHERE status = 'PAID'}）只能保证「同一场包场不会被我们自己的代码退两次」，
     * 管不到「上一次请求其实成功了、只是响应没收到」这种情形。
     *
     * <p><b>调用时机</b>：管理员在后台点「撤销并退款」时。
     * 与下单一样是<b>用户动作触发</b>的，不做定时任务、不做自动退款 ——
     * 退钱这件事必须有人负责，让它自己发生比让它晚一点发生危险得多。
     *
     * @param command 退款参数
     * @return 退款结果。{@code success=false} 时本地应当保持原状（仍是已付款），
     *         宁可退不成，也不能记成退成了
     */
    RefundResult refund(RefundCommand command);

    /**
     * 主动查单。
     *
     * <p>对应微信 {@code GET /v3/pay/transactions/out-trade-no/{out_trade_no}}
     * 与支付宝 {@code alipay.trade.query}。
     *
     * <p><b>它的用途是补偿回调丢失</b>，不是常规查询 —— 见
     * {@code PaymentQueryResult} 的说明。设计上刻意不做定时轮询：
     * 只在用户点「我付过了」或管理员核对时触发一次。
     *
     * @param outTradeNo 商户订单号
     * @param channel    通道，用于决定查哪个平台
     * @return 查询结果。{@code success=false} 表示这次查询本身失败（网络、签名等），
     *         与「查到了但没付款」是两回事
     */
    PaymentQueryResult queryPayment(String outTradeNo, PaymentChannel channel);
}
