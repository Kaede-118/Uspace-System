package com.kaede.uspace.order.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 主动查单的结果。
 *
 * <p><b>这个方法存在的理由是「回调可能不到」</b>：网络抖动、平台重试次数耗尽、
 * 正好赶上服务重启 —— 支付成功而回调丢失在生产环境是必然事件，不是意外。
 * 没有补偿路径的话，用户付了钱、订单却停在待支付，只能人工翻支付平台后台去对。
 *
 * <p>补偿的做法是：查到平台说已支付、而本地还没更新时，
 * 把结果<b>包装成一次等价的回调</b>走同一段处理逻辑（幂等、金额核对、状态流转
 * 全都复用），而不是另写一套「直接改状态」的代码 —— 那样两套逻辑迟早会漂移。
 */
@Data
public class PaymentQueryResult {

    /** 查询本身是否成功（不代表已支付） */
    private boolean success;

    /** 查询失败的原因，仅用于日志 */
    private String errmsg;

    /** 商户订单号 */
    private String outTradeNo;

    /** 平台交易号 */
    private String transactionNo;

    /** 平台记录的金额（元） */
    private BigDecimal amount;

    /** 平台那边这笔钱到底付了没有 */
    private boolean paid;

    /** 平台记录的支付完成时刻 */
    private LocalDateTime paidAt;

    /**
     * 构造一个查询失败的结果。
     *
     * @param errmsg 失败原因
     * @return 失败的结果对象
     */
    public static PaymentQueryResult fail(String errmsg) {
        PaymentQueryResult result = new PaymentQueryResult();
        result.setSuccess(false);
        result.setErrmsg(errmsg);
        return result;
    }
}
