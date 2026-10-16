package com.kaede.uspace.promotion.dto;

import com.kaede.uspace.promotion.CardOrderStatus;
import com.kaede.uspace.promotion.MonthlyCardType;
import com.kaede.uspace.promotion.entity.MonthlyCardOrder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一笔月卡购买单（运营后台的「月卡」流水）。
 *
 * <p><b>它记的是「买卡这件事」，不是「手上那张卡」</b> —— 卡是付款成功后才生成的资产，
 * 由 {@link MonthlyCardVo} 承载；本视图对应的是 {@code biz_monthly_card_order} 表，
 * 也就是「用户发起过哪几笔购卡、付没付成」。两者刻意分开：未付款的意向不该出现在卡包里
 * （见 {@code MonthlyCardOrder} 的类注释）。
 *
 * <p><b>中文一律由后端给</b>（{@code statusLabel} / {@code cardTypeLabel}）：
 * 项目的既定做法是「中文对照表只维护一份，放在枚举旁边」，前端不重实现一遍枚举映射
 * （见 {@code frontend/src/utils/labels.js} 文件头那条规矩）。
 *
 * <p><b>金额是下单时的快照</b>（{@code price}）：月卡调价之后，历史购买单仍要显示
 * 当时的数字，否则管理员在流水上会看到一个顾客从没同意过的金额。
 */
@Data
public class MonthlyCardOrderVo {

    /** 购买单 ID */
    private Long id;

    /** 购买单号，对外展示。它同时是支付时的商户订单号 */
    private String orderNo;

    /** 购买人用户 ID */
    private Long userId;

    /**
     * 购买人昵称（未填昵称时回落登录名）。
     *
     * <p><b>只有后台列表接口填它</b> —— 后台流水要一眼看出「这是谁买的」；
     * 用户端查自己的购买单时它为 null（那条路不带、也不必带，
     * 与 {@code OrderVo#userNickname} / {@code ProductOrderVo#userNickname} 同一条规矩）。
     *
     * <p>用户已注销或确实查不到时为 null，前端回落成「用户 #ID」——
     * 这里不填一个编造的名字，免得把「查不到」伪装成「叫这个名字」。
     */
    private String userNickname;

    /** 卡种名。取值见 {@link MonthlyCardType} */
    private String cardType;

    /** 卡种中文名，如「全天月卡」 */
    private String cardTypeLabel;

    /** 应付金额（元），下单时的快照 */
    private BigDecimal price;

    /** 状态名。取值见 {@link CardOrderStatus} */
    private String status;

    /**
     * 状态中文名，如「待支付」。
     *
     * <p>⚠️ <b>取的是 {@link CardOrderStatus} 而不是 {@code MonthlyCardStatus}</b>：
     * 本表的状态是「购买单的状态」（待支付 / 已支付 / 已关闭），
     * 而 {@code MonthlyCardStatus} 讲的是「卡的状态」（生效中 / 已过期 / 已退款 / 已停用）。
     * 拿卡状态去翻译购买单状态，认不出的取值会被 {@code labelOf} 原样返回 ——
     * 界面上不会报错，只会把 {@code PENDING_PAYMENT} 这串英文直接印出来。
     */
    private String statusLabel;

    /** 支付通道，未支付时为空 */
    private String paymentMethod;

    /** 支付平台交易号，未支付时为空 */
    private String paymentNo;

    /** 支付完成时刻，未支付时为空 */
    private LocalDateTime paidAt;

    /** 下单时刻 */
    private LocalDateTime createdAt;

    /**
     * 把购买单实体转成视图。
     *
     * @param order 购买单实体，可为 null
     * @return 购买单视图；入参为 null 时返回 null
     */
    public static MonthlyCardOrderVo from(MonthlyCardOrder order) {
        if (order == null) {
            return null;
        }
        MonthlyCardOrderVo vo = new MonthlyCardOrderVo();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setUserId(order.getUserId());
        // userNickname 不在这里填：它要查用户表，只有后台列表那条路需要（见字段注释）
        vo.setCardType(order.getCardType());
        vo.setCardTypeLabel(MonthlyCardType.labelOf(order.getCardType()));
        vo.setPrice(order.getPrice());
        vo.setStatus(order.getStatus());
        vo.setStatusLabel(CardOrderStatus.labelOf(order.getStatus()));
        vo.setPaymentMethod(order.getPaymentMethod());
        vo.setPaymentNo(order.getPaymentNo());
        vo.setPaidAt(order.getPaidAt());
        vo.setCreatedAt(order.getCreatedAt());
        return vo;
    }
}
