package com.kaede.uspace.order;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.security.UserPrincipal;
import com.kaede.uspace.order.dto.HandleDiffRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台对账差异接口（模块 8 的支付能力，仅 ADMIN）。
 *
 * <p><b>只有「标记已处理」这一个动作，而且它什么业务数据都不改。</b>
 *
 * <p>差异的处置动作逐类不同（驳回后有款要回去改复核结论、账单无对应凭证要找顾客、
 * 凭证无对应账单要找钱），但没有一个该由这一页代劳 ——
 * 与「资金动作入口越少越好」是同一条原则（QQ 机器人只读、订单人工核销接口被删，
 * 依据都是它）。这一页只记「我看过了、结论是什么」。
 *
 * <p><b>没有「取消已处理」</b>：那会让 {@code handled} 变成可反复翻转的状态、
 * 多一个入口，而标错了的条目仍然筛得出来、看得见。
 */
@RestController
@RequestMapping("/api/admin/reconcile-diffs")
@PreAuthorize("hasRole('ADMIN')")
public class AdminReconcileDiffController {

    private final ReconcileService reconcileService;

    /**
     * 构造器注入。
     *
     * @param reconcileService 对账的编排
     */
    public AdminReconcileDiffController(ReconcileService reconcileService) {
        this.reconcileService = reconcileService;
    }

    /**
     * 标记一条差异已处理。
     *
     * <p>备注<b>选填</b>：它是管理员给自己的备忘，不是给顾客的结论
     *（对照凭证驳回的 {@code reason} 必填 —— 那一句要展示给用户）。
     * 强制填的话，管理员会对几十条同类差异逐个打上「已处理」三个字，
     * 然后这个功能就没人用了。
     *
     * @param id      差异 ID
     * @param request 处理备注（可空）
     * @param me      当前登录的管理员
     * @return 空数据；已被别人处理过时返回 409
     */
    @PostMapping("/{id}/handle")
    public ResponseEntity<ApiResult<Void>> handle(@PathVariable Long id,
                                                  @Valid @RequestBody(required = false)
                                                  HandleDiffRequest request,
                                                  @AuthenticationPrincipal UserPrincipal me) {
        // 请求体可以整个不传 —— 备注本来就是选填的，为一个空对象要求前端必须发 body 是多余的
        String note = request == null ? null : request.getNote();
        return ApiResult.of(reconcileService.handleDiff(id, me.id(), note));
    }
}
