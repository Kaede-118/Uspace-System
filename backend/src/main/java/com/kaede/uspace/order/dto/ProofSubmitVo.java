package com.kaede.uspace.order.dto;

import com.kaede.uspace.order.PaymentProofStatus;
import lombok.Data;

/**
 * 提交付款凭证的结果。
 *
 * <p><b>它同时服务两种截然不同的结果</b>，靠 {@link #delivered} 区分：
 * <ul>
 *   <li>订单 / 商品：<b>已经交付了</b> —— 落账、扣库存、解除欠费拦截都在提交
 *       那一刻完成，用户接着就能再开一单</li>
 *   <li>包场 / 月卡：<b>还在等复核</b> —— 邀请令牌没生成、卡没发，
 *       用户拿到的是「凭证已提交，等待确认」</li>
 * </ul>
 * 前端拿这个字段决定文案，<b>不要自己按 targetType 判断</b>：
 * 哪一类提交即交付由 {@code PaymentTargetHandler#deliverOnSubmit} 声明，
 * 前端再判一遍就会与后端漂移 —— 而漂移的表现是「页面说已交付、实际没交付」，
 * 不报任何错。
 *
 * <p>{@link #verifyStatus} 也是同理：它可能直接是 {@code CONFIRMED}
 * （提交那一刻按「提交即交付」落账的，系统自己就是确认方），
 * 也可能是 {@code SUBMITTED}（等管理员看）。
 */
@Data
public class ProofSubmitVo {

    /** 收款类型，原样回显 */
    private String targetType;

    /** 目标 ID，原样回显 */
    private Long targetId;

    /** 凭证的复核状态名，取值为 {@link PaymentProofStatus} 的枚举名 */
    private String verifyStatus;

    /** 复核状态的中文名，直接展示 */
    private String verifyStatusLabel;

    /**
     * 本次提交是否已经让这笔收款完成了交付。
     *
     * <p>为 true 时用户界面应当说「已提交，支付完成」，false 时应当说
     * 「已提交，等待管理员确认」—— 这句话说错会让用户以为钱已经到账了。
     */
    private boolean delivered;

    /**
     * 给用户看的一句话，由后端拼好。
     *
     * <p>放后端拼的理由与 {@code PaymentChannel#getLabel} 同源：
     * 「提交之后会怎样」是业务规则（取决于交付时机），前端各写一份文案
     * 迟早与规则对不上。
     */
    private String message;

    /**
     * 构造提交结果。
     *
     * @param targetType 收款类型名
     * @param targetId   目标 ID
     * @param status     提交后的凭证状态
     * @param delivered  本次提交是否已完成交付
     * @return 视图对象
     */
    public static ProofSubmitVo of(String targetType, Long targetId,
                                   PaymentProofStatus status, boolean delivered) {
        ProofSubmitVo vo = new ProofSubmitVo();
        vo.setTargetType(targetType);
        vo.setTargetId(targetId);
        vo.setVerifyStatus(status.name());
        vo.setVerifyStatusLabel(status.getLabel());
        vo.setDelivered(delivered);
        vo.setMessage(delivered
                ? "付款凭证已提交，这笔款项已结清"
                : "付款凭证已提交，管理员确认后即可使用");
        return vo;
    }
}
