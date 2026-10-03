package com.kaede.uspace.billing;

import com.kaede.uspace.billing.dto.BillingRulesVo;
import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.BizResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计费规则接口（模块 7，用户端）。
 *
 * <p>只读，只有一个端点：给「计费规则」页面与「我的」页面的优惠提示提供价目表。
 *
 * <p><b>为什么不把这些数字写在前端</b>：单价、封顶、宽限、优惠门槛全是配置项
 *（{@code uspace.billing.*}）。前端抄一份等于把计费规则复制了一份，
 * 调价之后两处必然对不上 —— 而那种不一致<b>不会有任何报错</b>，
 * 只是页面上写着旧价格、账单按新价格收。
 *
 * <p>⚠️ <b>月卡价格不在这里</b>（虽然「计费规则」页面要一起展示）：
 * 月卡归模块 9，价格在 {@code PromotionProperties}，现成接口是
 * {@code GET /api/cards/types}。本包若去读那个配置，会与
 * {@code promotion → billing}（{@code CardCoverage} 就是本包定义的）**成环**。
 */
@RestController
@RequestMapping("/api/billing")
public class BillingController {

    private final BillingProperties properties;

    public BillingController(BillingProperties properties) {
        this.properties = properties;
    }

    /**
     * 价目表（计费规则）。
     *
     * <p><b>匿名可访问</b>（见 {@code SecurityConfig.PUBLIC_PATHS}）——
     * 与门店名、营业状态、公告同理：价格是店门口就该看得见的信息，
     * 想进店的人应该先能查到要花多少钱，而不是注册完才看得到。
     * 本端点只读计费配置，不含任何用户数据。
     *
     * @return 价目表
     */
    @GetMapping("/rules")
    public ResponseEntity<ApiResult<BillingRulesVo>> rules() {
        // 走「唯一出口」ApiResult.of(BizResult)，不手写状态码 ——
        // 状态码与响应体的对应关系全项目只有那一处定义
        return ApiResult.of(BizResult.ok(BillingRulesVo.from(properties)));
    }
}
