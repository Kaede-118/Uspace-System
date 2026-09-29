package com.kaede.uspace.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 模拟支付的请求体（仅 {@code provider=mock} 时存在）。
 *
 * <p>真实场景下这个动作发生在微信或支付宝的收银台页面上，用户点「立即支付」
 * 之后由平台服务器回调本系统。模拟环境没有那个页面，所以由前端弹一个模拟收银台，
 * 用这个请求体触发 —— <b>但它触发的仍然是完整的回调链路</b>
 * （签名 → 验签 → 幂等 → 金额核对 → 状态流转），不是直接改订单状态。
 *
 * <p>这一点是刻意的：若模拟支付直接改状态，那么验签失败、金额不符、重复回调
 * 这些分支在演示与测试时就永远走不到，而它们恰恰是最需要验证的部分。
 */
@Data
public class MockPayRequest {

    /**
     * 商户订单号。
     *
     * <p>订单号（{@code OD} 前缀）或包场单号（{@code BK} 前缀）都可以，
     * 由前缀路由到对应的处理器。
     */
    @NotBlank(message = "支付单号不能为空")
    @Size(max = 32, message = "支付单号格式不正确")
    private String outTradeNo;
}
