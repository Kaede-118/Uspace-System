package com.kaede.uspace.order.dto;

import com.kaede.uspace.billing.dto.BillingResult;
import com.kaede.uspace.order.OrderStatus;
import com.kaede.uspace.order.PaymentChannel;
import com.kaede.uspace.order.entity.Order;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单视图，列表与详情共用。
 *
 * <p><b>⚠️ 刻意不包含 {@code passcode}</b>：门锁密码只在「点击开门」与
 * 「查看密码」两个动作的返回体里出现（见 {@link OrderOpenVo}）。
 * 若把它放进这个通用视图，那么任何一次订单查询都会把密码带出来 ——
 * 包括后台列表页、也包括用户翻自己的历史订单。密码一旦进入某个不该有它的
 * 响应体，就再也收不回来了。这条边界放在 VO 上，比放在「记得别查那个字段」上可靠。
 *
 * <p>同样不包含 {@code lockId}：那是内部实现细节，用户不需要知道。
 */
@Data
public class OrderVo {

    /** 订单 ID */
    private Long id;

    /** 订单号，对外展示与客服核对用 */
    private String orderNo;

    /** 门店 ID */
    private Long storeId;

    /** 关联的包场 ID，未命中包场时为 null */
    private Long bookingId;

    /** 计费起点（点击开门的时刻） */
    private LocalDateTime startTime;

    /** 离场时刻。使用中为 null */
    private LocalDateTime endTime;

    /**
     * 在店时长（分钟）= 离场时刻 − 开门时刻。使用中为 null。
     *
     * <p><b>它不是「计费时长」</b>：包场时段被剪掉、宽限的 5 分钟也不计入，
     * 两者可以差很多（见 {@link Order#getStayMinutes()}）。
     * 前端展示时不要拿 {@code dayMinutes + nightMinutes} 冒充它。
     */
    private Integer stayMinutes;

    /** 日场时长（分钟） */
    private Integer dayMinutes;

    /** 日场实收 */
    private BigDecimal dayAmount;

    /** 夜场时长（分钟） */
    private Integer nightMinutes;

    /** 夜场实收 */
    private BigDecimal nightAmount;

    /** 实收合计。各段已分别封顶、已含优惠 */
    private BigDecimal totalAmount;

    /** 月度优惠为本单省下的金额（说明性字段，已含在合计中，展示时不要从合计里再减一次） */
    private BigDecimal discountAmount;

    /**
     * 月卡为本单免掉的金额。
     *
     * <p>说明性字段，<b>已从合计中扣除</b>，同样不要从合计里再减一次。
     * 它与 {@link #discountAmount} 是两回事 —— 后者是月度累计优惠省下的钱。
     * 一笔订单可能两者都有（夜间卡用户当月又已达标：日场段走优惠价、夜场段免费），
     * 所以两个数要分开给，合成一个的话账就没法解释了。
     */
    private BigDecimal cardFreeAmount;

    /**
     * 免费活动为本单免掉的金额（元）。不在活动区间内时为 0。
     *
     * <p>说明性字段，<b>已从合计中扣除</b>，不要从合计里再减一次。
     *
     * <p>⚠️ 与 {@link #cardFreeAmount} <b>互斥</b>：被月卡覆盖的段只记月卡 ——
     * 月卡用户本来就免费，活动并没有为他省下什么。
     */
    private BigDecimal activityFreeAmount;

    /** 应付金额。发起支付时用它 */
    private BigDecimal payableAmount;

    /**
     * 分段账单（几档 × 单价），<b>只有详情接口有它</b>。
     *
     * <p>展示结构与结账页的 {@link OrderSettleVo#getBill()} 完全一致
     * （同一份 {@link BillingResult}），前端用同一套 props 渲染账单组件。
     * 数据来源是订单上的快照（结算时落库）；快照为空的老订单由
     * {@code OrderService} 按当前规则重算，且只有金额与落库完全一致才会给到这里。
     *
     * <p><b>{@link #from} 刻意不填它</b>——列表接口（{@code /api/orders/me}、
     * {@code /api/admin/orders}）因此不带账单：一页 20 条各带一份既白占响应体，
     * 老订单还要逐条重算。要看分段明细就进详情页。
     *
     * <p>为 null 的三种情形（前端一律回落到日场/夜场汇总行）：使用中的订单
     * （费用还在走，估算看结账预览接口）、老订单重算与落库金额对不上、
     * 快照损坏且重算也失败。
     */
    private BillingResult bill;

    /**
     * 本单是否因命中包场而减免了计费时长。
     *
     * <p>与 {@link #bill} 同来同去（由详情接口的账单装配一起给出），没有账单时为 null。
     * 它<b>不是</b>从 {@link #bookingId} 推出来的：订单挂着包场、而结算发生在
     * 包场开始之前时，并没有任何时长被减免 —— 靠 ID 推断会凭空多出一句
     * 「包场时段不计费」的解释。
     */
    private Boolean freeByBooking;

    /** 状态名，取值见 {@link OrderStatus} */
    private String status;

    /** 状态中文说明，供前端直接展示 */
    private String statusText;

    /** 支付通道名，未支付时为 null */
    private String paymentMethod;

    /**
     * 支付通道的用户口径中文名，供前端直接展示。
     *
     * <p><b>由后端给而不是前端自己映射</b>：订单详情页曾经自己维护过一张
     * 「通道名 → 中文」的表，而它写着 {@code QR_UPLOAD: '转账核销'}，
     * 与后端口径的「扫码转账」对不上 —— 同一个枚举在两处各有一个名字，
     * 迟早分岔，且不会有任何报错。现在只有
     * {@link PaymentChannel#userLabelOf(String)} 一处定义。
     *
     * <p>未支付（含 0 元自动结清）时为 null，前端按「—」展示即可。
     */
    private String paymentMethodLabel;

    /** 支付完成时刻 */
    private LocalDateTime paidAt;

    /** 时长是否经人工调整：0=否 1=是 */
    private Integer adjusted;

    /** 调整原因，未调整时为 null */
    private String adjustReason;

    /** 下单时刻 */
    private LocalDateTime createdAt;

    /**
     * 由实体转换为视图。
     *
     * <p>手工逐字段赋值而不是用 BeanUtils 拷贝：一来能明确「哪些字段对外」，
     * 二来新增实体字段时不会因为自动拷贝而悄悄泄露出去 ——
     * 这正是 {@code passcode} 与 {@code lockId} 被挡在外面的方式。
     *
     * @param order 订单实体，可为 null
     * @return 订单视图；入参为 null 时返回 null
     */
    public static OrderVo from(Order order) {
        if (order == null) {
            return null;
        }
        OrderVo vo = new OrderVo();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setStoreId(order.getStoreId());
        vo.setBookingId(order.getBookingId());
        vo.setStartTime(order.getStartTime());
        vo.setEndTime(order.getEndTime());
        vo.setStayMinutes(order.getStayMinutes());
        vo.setDayMinutes(order.getDayMinutes());
        vo.setDayAmount(order.getDayAmount());
        vo.setNightMinutes(order.getNightMinutes());
        vo.setNightAmount(order.getNightAmount());
        vo.setTotalAmount(order.getTotalAmount());
        vo.setDiscountAmount(order.getDiscountAmount());
        vo.setCardFreeAmount(order.getCardFreeAmount());
        vo.setActivityFreeAmount(order.getActivityFreeAmount());
        vo.setPayableAmount(order.getPayableAmount());
        vo.setStatus(order.getStatus());
        vo.setStatusText(OrderStatus.labelOf(order.getStatus()));
        vo.setPaymentMethod(order.getPaymentMethod());
        vo.setPaymentMethodLabel(PaymentChannel.userLabelOf(order.getPaymentMethod()));
        vo.setPaidAt(order.getPaidAt());
        vo.setAdjusted(order.getAdjusted());
        vo.setAdjustReason(order.getAdjustReason());
        vo.setCreatedAt(order.getCreatedAt());
        return vo;
    }
}
