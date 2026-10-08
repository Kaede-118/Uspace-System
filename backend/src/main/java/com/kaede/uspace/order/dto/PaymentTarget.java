package com.kaede.uspace.order.dto;

import com.kaede.uspace.order.OrderStatus;
import com.kaede.uspace.order.PaymentTargetType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一笔待支付的目标 —— 把「订单」与「包场」两类东西抽象成同一副面孔。
 *
 * <p>发起支付与处理回调都只认这个结构，不必关心钱到底是哪张表的。
 * 它是 {@code PaymentTargetHandler} 的产物：由各处理器从自己的实体翻译过来。
 *
 * <p><b>状态为什么用字符串而不是枚举</b>：订单的状态取值见
 * {@link com.kaede.uspace.order.OrderStatus}，包场见
 * {@link com.kaede.uspace.space.BookingStatus}，两者是各自独立的枚举。
 * 支付侧只关心两个状态 ——「已支付」与「待支付」，而这两个名字在两侧恰好一致，
 * 于是用字符串比较即可，不必为了统一而把两套状态强行合成一个枚举。
 * 这样做的好处是：包场将来新增状态（如「已结束」）时，支付侧一行都不用改。
 */
@Data
public class PaymentTarget {

    /** 已支付状态名。订单与包场用的是同一个字面量 */
    public static final String STATUS_PAID = "PAID";

    /** 待支付状态名。订单与包场用的是同一个字面量 */
    public static final String STATUS_PENDING_PAYMENT = "PENDING_PAYMENT";

    /**
     * 「凭证未通过」状态名。
     *
     * <p>订单与商品的<b>购买单</b>都用这个字面量。包场与月卡不走这条 ——
     * 它们提交凭证时并未交付任何东西，驳回后本来就还停在待支付上。
     *
     * <p>⚠️ <b>它刻意是本类的字面量，而不是某个枚举的 {@code name()}</b>：
     * 订单与商品各有自己的状态枚举（{@code OrderStatus} / {@code ProductOrderStatus}），
     * 那两个 {@code REJECTED} 互不相干。引用其中一个，就等于让「商品被驳回」
     * 取决于两个枚举恰好同名 —— 而那种依赖断裂时没有任何编译错误，
     * 表现只是「重传凭证被当成非法状态拒掉」。
     */
    public static final String STATUS_REJECTED = "REJECTED";

    /** 目标类型 */
    private PaymentTargetType type;

    /** 目标主键（订单 ID 或包场 ID） */
    private Long id;

    /** 付款人用户 ID。用于支付成功后累加其累计消费额 */
    private Long userId;

    /** 应付金额（元） */
    private BigDecimal amount;

    /**
     * 商户订单号 —— 发给支付平台的那个号，回调时原样带回来。
     *
     * <p>订单用 {@code order_no}、包场用 {@code booking_no}，两者都有唯一索引，
     * 所以它天然是一个可靠的幂等键。
     */
    private String outTradeNo;

    /** 当前状态名 */
    private String status;

    /** 支付通道名。未支付时为 null，供查单结果回显 */
    private String paymentMethod;

    /** 平台交易号。未支付时为 null */
    private String paymentNo;

    /**
     * 支付完成时刻。未支付时为 null。
     *
     * <p>注意 0 元自动结清的订单也有这个值 —— 它区分的是「不用付」与「没记录」，
     * 对账时这两种情形必须分得开。
     */
    private LocalDateTime paidAt;

    /**
     * 商品描述，会展示在用户的支付账单上。
     *
     * <p>写清「这是什么钱」比写单号重要 —— 用户在微信账单里看到
     * 「共享娱乐空间使用费」知道是自己玩的，看到一串单号只会以为被盗刷。
     */
    private String description;

    /**
     * 是否已支付。
     *
     * @return 已支付返回 true
     */
    public boolean isPaid() {
        return STATUS_PAID.equals(status);
    }

    /**
     * 是否处于待支付状态。
     *
     * @return 待支付返回 true
     */
    public boolean isPendingPayment() {
        return STATUS_PENDING_PAYMENT.equals(status);
    }

    /**
     * 是否处于「凭证未通过」状态（等待用户重新提交）。
     *
     * <p>只有订单会进入这个状态 —— 商品的购买单被驳回时退回的是「待支付」，
     * 因为商品的处置动作与它一样（重新提交凭证），不必再多一个状态。
     *
     * <p>⚠️ <b>它刻意不算「待支付」</b>：{@code PENDING_PAYMENT} 的处置动作是
     * 「去付款」，而这个是「重新上传一张截图」。{@code PaymentProofService#submit}
     * 靠这个区分放不放行 —— 两者都该放行，但页面上的提示与入口完全不同，
     * 混在一起用户会再付一次钱。
     *
     * <p>⚠️ <b>比较用的是本类的字面量常量，不是某个枚举的 {@code name()}</b>：
     * 订单与商品各有自己的状态枚举（{@code OrderStatus} / {@code ProductOrderStatus}），
     * 那两个 {@code REJECTED} 是互不相干的常量。引用其中一个，就等于让
     * 「商品被驳回」这件事取决于两个枚举的字面量恰好同名 —— 而那种依赖一旦断裂，
     * 表现是「商品驳回后重传凭证被当成非法状态拒掉」，不报错、也查不出原因。
     *
     * @return 凭证未通过返回 true
     */
    public boolean isRejected() {
        return STATUS_REJECTED.equals(status);
    }
}
