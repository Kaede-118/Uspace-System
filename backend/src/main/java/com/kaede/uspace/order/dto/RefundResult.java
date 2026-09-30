package com.kaede.uspace.order.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 退款结果。
 *
 * <p>与下单、查单一样，<b>失败不用异常表达，而是 {@code success=false} 加原因</b>：
 * 调用方要的是「这笔钱退回去了没有」，而异常会把「平台拒绝退款」与
 * 「我们自己代码有 bug」混成一类东西，在日志里分不开。
 *
 * <p>「退款失败」在这套设计里是<b>必须走得到</b>的一条分支，不是意外：
 * 原支付订单超过可退款期限、商户余额不足、平台侧限流 —— 每一种都真实存在，
 * 而且都不是「重试就一定好」的。所以失败时本地状态要原样留着（仍然是已付款），
 * 让钱与单子始终对得上：<b>宁可退不成，也不能记成退成了</b>。
 */
@Data
public class RefundResult {

    /** 退款请求是否成功 */
    private boolean success;

    /** 失败原因，仅用于日志与提示 */
    private String errmsg;

    /** 商户退款单号（我们自己生成的），成功时有值 */
    private String refundNo;

    /** 平台侧的退款单号（微信 refund_id / 支付宝 trade_no 对应的退款流水），成功时有值 */
    private String platformRefundNo;

    /** 退款完成时刻 */
    private LocalDateTime refundedAt;

    /**
     * 构造一个成功结果。
     *
     * @param refundNo         商户退款单号
     * @param platformRefundNo 平台退款单号
     * @param refundedAt       退款完成时刻
     * @return 成功的结果对象
     */
    public static RefundResult ok(String refundNo, String platformRefundNo, LocalDateTime refundedAt) {
        RefundResult result = new RefundResult();
        result.setSuccess(true);
        result.setRefundNo(refundNo);
        result.setPlatformRefundNo(platformRefundNo);
        result.setRefundedAt(refundedAt);
        return result;
    }

    /**
     * 构造一个失败结果。
     *
     * @param errmsg 失败原因
     * @return 失败的结果对象
     */
    public static RefundResult fail(String errmsg) {
        RefundResult result = new RefundResult();
        result.setSuccess(false);
        result.setErrmsg(errmsg);
        return result;
    }
}
