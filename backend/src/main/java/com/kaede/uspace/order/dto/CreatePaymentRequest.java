package com.kaede.uspace.order.dto;

import com.kaede.uspace.order.PaymentChannel;
import com.kaede.uspace.order.PaymentTargetType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 发起支付的请求体。
 *
 * <p><b>为什么是一个统一入口而不是 {@code /api/orders/{id}/payment}</b>：
 * 本系统要收钱的东西不止订单一种（还有包场，将来还有月卡），它们走同一套支付通道
 * 与回调链路。把入口收在「支付」这个动作上，加一类收款不必新开一条路径；
 * 更重要的是避开包级循环依赖 —— 若包场付款挂在 {@code space} 包的接口上，
 * 而模块 8 的支付服务又依赖 {@code space}（准入校验要用它），两个包就成环了。
 *
 * <p>类型与通道都用<b>枚举</b>接收，值不合法时 Jackson 反序列化直接失败，
 * 由全局异常处理器转成 400 —— 不必在 Service 里再写一遍字符串校验。
 */
@Data
public class CreatePaymentRequest {

    /**
     * 支付目标的类型：订单还是包场。
     *
     * <p>前端不必自己判断，实体视图里带着这个信息，原样回传即可。
     */
    @NotNull(message = "支付目标类型不能为空")
    private PaymentTargetType targetType;

    /** 支付目标的主键（订单 ID 或包场 ID） */
    @NotNull(message = "支付目标不能为空")
    private Long targetId;

    /**
     * 支付通道。
     *
     * <p><b>由前端探测浏览器环境后自动选择，不让用户自己选</b> ——
     * 微信内打不开支付宝、支付宝内打不开微信，让用户选必然出现
     * 「选了却调不起来」的死路。判断依据是 User-Agent。
     *
     * <p>传 {@link PaymentChannel#QR_UPLOAD} 会被接受但<b>不会调用支付网关</b>：
     * 那表示用户打算走「传截图给管理员核销」的降级路径，
     * 他应当去调上传凭证的接口，而不是在这里发起线上支付。
     */
    @NotNull(message = "支付通道不能为空")
    private PaymentChannel channel;
}
