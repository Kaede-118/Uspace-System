package com.kaede.uspace.promotion.dto;

import com.kaede.uspace.promotion.CardOrderStatus;
import com.kaede.uspace.promotion.MonthlyCardType;
import com.kaede.uspace.promotion.entity.MonthlyCardOrder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 一笔月卡购买单。
 *
 * <p>用户视角看到的是「一张还没付款的月卡」：付了款它才变成卡
 * （{@link MonthlyCardVo}），所以这里的字段与卡不同 ——
 * 有单号、有失效时刻，但没有生效与失效日期。
 */
@Data
public class CardPurchaseVo {

    /** 购买单 ID，发起支付时作为 targetId */
    private Long id;

    /** 购买单号，同时是商户订单号 */
    private String orderNo;

    /** 卡种名 */
    private String cardType;

    /** 卡种中文名 */
    private String cardTypeLabel;

    /** 应付金额（元） */
    private BigDecimal price;

    /** 状态名 */
    private String status;

    /** 状态中文名 */
    private String statusLabel;

    /** 下单时刻 */
    private LocalDateTime createdAt;

    /**
     * 这笔单子的失效时刻 = 下单时刻 + 存活时长。
     *
     * <p>前端据此显示倒计时 —— 时间一到，用户再发起购买时这张单会被自动关闭，
     * 届时可以直接买新的，不必先去取消。
     */
    private LocalDateTime expireAt;

    /**
     * 把购买单实体转成视图。
     *
     * @param order   购买单实体，可为 null
     * @param timeout 待支付单的存活时长，用于算失效时刻
     * @return 购买单视图；入参为 null 时返回 null
     */
    public static CardPurchaseVo from(MonthlyCardOrder order, Duration timeout) {
        if (order == null) {
            return null;
        }
        CardPurchaseVo vo = new CardPurchaseVo();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setCardType(order.getCardType());
        vo.setCardTypeLabel(MonthlyCardType.labelOf(order.getCardType()));
        vo.setPrice(order.getPrice());
        vo.setStatus(order.getStatus());
        vo.setStatusLabel(CardOrderStatus.labelOf(order.getStatus()));
        vo.setCreatedAt(order.getCreatedAt());
        if (order.getCreatedAt() != null && timeout != null) {
            vo.setExpireAt(order.getCreatedAt().plus(timeout));
        }
        return vo;
    }
}
