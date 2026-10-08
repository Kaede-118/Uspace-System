package com.kaede.uspace.order.dto;

import com.kaede.uspace.order.PaymentTargetType;
import com.kaede.uspace.order.entity.PaymentProof;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一条被驳回的付款凭证（用户端）。
 *
 * <p>它是首页那条提醒的来源，也是订单详情页标出「凭证未通过」的依据 ——
 * 补的都是同一个缺口：订单与商品是「先交付后复核」，管理员驳回
 * <b>刻意不回退订单状态</b>，所以凭证上的这个结论必须单独送到用户面前，
 * 否则他看到的永远是「已支付」，而管理员那边早已判这笔钱不成立。
 *
 * <p><b>不带 {@code proofUrl}</b>：那是用户自己刚传的图，回显没有意义 ——
 * 他需要的是「哪一笔、为什么不行、去哪儿重传」三件事。
 *
 * <p><b>{@code targetId} 是给前端跳转用的</b>：光有类型的话，用户还得
 * 自己去列表里翻是哪一笔。四类收款的落地页各不相同
 *（订单详情 / 包场 / 卡包 / 商品订单），由前端按 {@code targetType} 决定。
 */
@Data
public class RejectedProofVo {

    /** 凭证 ID */
    private Long proofId;

    /** 收款类型名（ORDER / BOOKING / MONTHLY_CARD / PRODUCT） */
    private String targetType;

    /** 收款类型中文名（订单 / 包场 / 月卡 / 商品） */
    private String targetTypeLabel;

    /** 目标 ID（订单 / 包场 / 购买单的主键），供前端跳到具体那一笔 */
    private Long targetId;

    /** 商户订单号。展示用，也是用户「对号入座」的依据 */
    private String orderNo;

    /** 提交时的应付额快照（元） */
    private BigDecimal amount;

    /** 管理员填写的驳回原因，<b>必填</b>（与差异处理的选填备注相对） */
    private String reason;

    /** 驳回时刻 */
    private LocalDateTime rejectedAt;

    /**
     * 由实体组装视图。
     *
     * <p>与 {@code AdminProofVo.from} 同一套做法：视图对象自己不去查库，
     * 需要的信息由调用方已经取到的实体给出。
     *
     * @param proof 凭证实体，不可为 null
     * @return 视图对象
     */
    public static RejectedProofVo from(PaymentProof proof) {
        RejectedProofVo vo = new RejectedProofVo();
        vo.setProofId(proof.getId());
        vo.setTargetType(proof.getTargetType());
        vo.setTargetTypeLabel(PaymentTargetType.labelOf(proof.getTargetType()));
        vo.setTargetId(proof.getTargetId());
        vo.setOrderNo(proof.getOrderNo());
        vo.setAmount(proof.getAmount());
        vo.setReason(proof.getRejectReason());
        vo.setRejectedAt(proof.getConfirmedAt());
        return vo;
    }
}
