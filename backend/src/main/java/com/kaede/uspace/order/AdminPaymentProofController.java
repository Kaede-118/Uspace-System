package com.kaede.uspace.order;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.common.security.UserPrincipal;
import com.kaede.uspace.order.dto.AdminProofVo;
import com.kaede.uspace.order.dto.ProofRejectRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台付款凭证接口（模块 8 的支付能力，仅 ADMIN）。
 *
 * <p>2026-09-30 起扫码转账是 12 月投产时唯一的收款方式，<b>这一页就是收银台的
 * 对账台</b>：管理员在这里逐条核对用户提交的付款截图。它的设计目标是
 * <b>把人工工作量从「逐条看 100%」降到「只看差异」</b>（差异由 Phase 5 的对账
 * 自动算出），所以列表把风险条目与待复核的排在最前面。
 *
 * <p><b>权限靠类上的 {@code @PreAuthorize}</b>，不在方法上逐个写 ——
 * 逐个写的话，将来新增一个方法忘了标，就是一个「谁都能替别人核销」的缺口，
 * 而且不会有任何报错。
 */
@RestController
@RequestMapping("/api/admin/payment-proofs")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminPaymentProofController {

    private final PaymentProofService paymentProofService;

    /**
     * 构造器注入。
     *
     * @param paymentProofService 凭证的提交与复核
     */
    public AdminPaymentProofController(PaymentProofService paymentProofService) {
        this.paymentProofService = paymentProofService;
    }

    /**
     * 分页查询付款凭证。
     *
     * <p>不传 {@code verifyStatus} 时返回全部，排序是「有风险的 → 待复核的 → 新的」，
     * 见 {@code PaymentProofMapper#selectPageForAdmin}。后台默认视图就靠它 ——
     * 一打开先看到的应该是最需要处理的那几条。
     *
     * @param page         页码，从 1 开始
     * @param size         每页条数，最多 100
     * @param verifyStatus 状态筛选（SUBMITTED / CONFIRMED / REJECTED），可空
     * @return 分页结果
     */
    @GetMapping
    public ResponseEntity<ApiResult<PageResult<AdminProofVo>>> list(
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = 100, message = "每页最多 100 条") long size,
            @RequestParam(required = false) String verifyStatus) {
        return ResponseEntity.ok(ApiResult.ok(
                paymentProofService.listForAdmin((int) page, (int) size, verifyStatus)));
    }

    /**
     * 复核通过。
     *
     * <p>对订单与商品而言这是<b>纯登记</b>（钱在提交那一刻就算收到了）；
     * 对包场与月卡而言<b>此刻才交付</b> —— 邀请令牌与月卡都产生在这一步。
     *
     * <p>没有请求体：要填的东西一样都没有。不为对称造一个空对象。
     *
     * @param id 凭证 ID
     * @param me 当前登录的管理员
     * @return 空数据
     */
    @PostMapping("/{id}/confirm")
    public ResponseEntity<ApiResult<Void>> confirm(@PathVariable Long id,
                                                   @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(paymentProofService.confirm(id, me.id()));
    }

    /**
     * 复核不通过。
     *
     * <p><b>驳回不会回退已经发生的交付</b>：订单与商品在提交那刻就落账了
     * （订单已支付、库存已扣、累计消费已加），系统不做自动回退，
     * 只能人工处置。所以返回体里不带「已回退」之类的承诺 ——
     * 后台会根据条目上的 {@code delivered} 标记提示管理员还有一步要做。
     *
     * @param id      凭证 ID
     * @param request 未通过原因（会展示给用户，必填）
     * @param me      当前登录的管理员
     * @return 空数据
     */
    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResult<Void>> reject(@PathVariable Long id,
                                                  @Valid @RequestBody ProofRejectRequest request,
                                                  @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(paymentProofService.reject(id, me.id(), request.getReason()));
    }
}
