package com.kaede.uspace.order.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员人工核销的请求体（支付降级路径）。
 *
 * <p>用于「用户上传付款截图 → 管理员核对到账后确认」这条路径。
 * 主路径是支付回调自动核销，不需要人工介入 —— 这条路径只在运营方
 * 尚无支付商户号时启用（个人主体办不了商户号，而办执照、开户、备案
 * 是一串以月计的外部流程）。
 */
@Data
public class ConfirmPaymentRequest {

    /**
     * 支付平台交易号。
     *
     * <p>管理员从微信 / 支付宝的收款记录里抄过来的那串号，写进
     * {@code biz_order.payment_no}，供日后对账时勾稽。
     *
     * <p><b>允许为空但强烈建议填</b>：不填也能核销（有些收款方式确实没有交易号，
     * 比如收到的现金），但那笔钱就再也无法自动对账了。留成可选是为了不把
     * 「有交易号但管理员懒得填」与「确实没有交易号」变成同一种失败。
     */
    @Size(max = 64, message = "支付交易号不能超过 64 个字符")
    private String paymentNo;
}
