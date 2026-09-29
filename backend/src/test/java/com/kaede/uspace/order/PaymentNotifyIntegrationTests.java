package com.kaede.uspace.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 支付回调端点的集成测试，连真库并启动完整的 Spring 上下文。
 *
 * <p><b>本类钉住两件「配错了不报错、但后果很严重」的事</b>：
 * <ol>
 *   <li><b>端点匿名可达</b> —— 支付平台的服务器不会带本系统的 JWT。
 *       忘了把它加进公开路径列表，回调就收不到，而错误表现是
 *       「用户付了钱但订单一直不变成已支付」，排查起来要绕一大圈</li>
 *   <li><b>应答是平台协议格式而不是 {@code ApiResult}</b> ——
 *       微信认 {@code {"code":"SUCCESS"}}、支付宝认纯文本 {@code success}。
 *       给它们一个本系统的结构，平台只会当作处理失败并一直重推，而重推依然失败</li>
 * </ol>
 *
 * <p>顺带钉住反向的一面：其余支付接口<b>仍然需要登录</b>。
 * 公开路径是白名单，加错一条就是一个不设防的入口。
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+",
        disabledReason = "未配置 MYSQL_PASSWORD，跳过需要真实数据库的集成测试")
class PaymentNotifyIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("微信回调端点匿名可达：不带凭证也不会被拦成 401")
    void wxpayNotify_isAnonymousAccessible() throws Exception {
        // 不带 Authorization 头。签名是错的，所以业务上会失败（500），
        // 但【绝不能是 401】—— 那说明请求根本没进到 Controller
        mockMvc.perform(post("/api/payments/notify/wxpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Wechatpay-Timestamp", "1700000000")
                        .header("Wechatpay-Nonce", "abc123")
                        .header("Wechatpay-Signature", "wrong-signature")
                        .content("{\"event_type\":\"TRANSACTION.SUCCESS\"}"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    @DisplayName("微信回调的应答是微信协议格式，不是本系统的 ApiResult")
    void wxpayNotify_returnsWxpayPayload() throws Exception {
        mockMvc.perform(post("/api/payments/notify/wxpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_type\":\"TRANSACTION.SUCCESS\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("{\"code\":\"FAIL\",\"message\":\"处理失败\"}"));
    }

    @Test
    @DisplayName("支付宝回调端点匿名可达，且应答是纯文本 failure")
    void alipayNotify_returnsPlainText() throws Exception {
        mockMvc.perform(post("/api/payments/notify/alipay")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("out_trade_no", "OD202609281200000001")
                        .param("trade_status", "TRADE_SUCCESS"))
                // 没有签名，验签不过 —— 但请求必须能进到 Controller（不是 401）
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("failure"));
    }

    @Test
    @DisplayName("发起支付的接口仍然需要登录")
    void createPayment_requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"ORDER\",\"targetId\":1,\"channel\":\"WXPAY_H5\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("查询支付状态的接口仍然需要登录")
    void queryPayment_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/payments/OD202609281200000001"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("模拟支付接口仍然需要登录 —— 否则任何人都能把别人的订单标成已支付")
    void mockPay_requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/payments/mock/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outTradeNo\":\"OD202609281200000001\"}"))
                .andExpect(status().isUnauthorized());
    }
}
