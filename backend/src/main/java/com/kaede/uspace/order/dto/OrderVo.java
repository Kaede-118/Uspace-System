package com.kaede.uspace.order.dto;

import com.kaede.uspace.order.OrderStatus;
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

    /** 本单优惠金额（说明性字段，已含在合计中，展示时不要从合计里再减一次） */
    private BigDecimal discountAmount;

    /** 应付金额。发起支付时用它 */
    private BigDecimal payableAmount;

    /** 状态名，取值见 {@link OrderStatus} */
    private String status;

    /** 状态中文说明，供前端直接展示 */
    private String statusText;

    /** 支付通道名，未支付时为 null */
    private String paymentMethod;

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
        vo.setDayMinutes(order.getDayMinutes());
        vo.setDayAmount(order.getDayAmount());
        vo.setNightMinutes(order.getNightMinutes());
        vo.setNightAmount(order.getNightAmount());
        vo.setTotalAmount(order.getTotalAmount());
        vo.setDiscountAmount(order.getDiscountAmount());
        vo.setPayableAmount(order.getPayableAmount());
        vo.setStatus(order.getStatus());
        vo.setStatusText(OrderStatus.labelOf(order.getStatus()));
        vo.setPaymentMethod(order.getPaymentMethod());
        vo.setPaidAt(order.getPaidAt());
        vo.setAdjusted(order.getAdjusted());
        vo.setAdjustReason(order.getAdjustReason());
        vo.setCreatedAt(order.getCreatedAt());
        return vo;
    }
}
