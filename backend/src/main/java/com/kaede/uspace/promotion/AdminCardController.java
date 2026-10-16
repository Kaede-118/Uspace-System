package com.kaede.uspace.promotion;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.promotion.dto.IssueCardRequest;
import com.kaede.uspace.promotion.dto.MonthlyCardVo;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 月卡管理接口（模块 9，运营后台）。
 *
 * <p>三个动作：查询、<b>开卡</b>与<b>停卡</b>。
 *
 * <p><b>开卡补的是「面对面收款」那条路</b>：顾客在店里当面付了钱
 *（现金 / 当面转账），管理员在这里一次点完 —— 不必让他掏出手机
 * 在商城再下一次单、再传一张截图走复核。测试也走它。
 *
 * <p><b>停卡只停权益、不动钱</b>：收回发错的卡与离职员工的STAFF 卡。
 * 退款政策仍未拍板（未使用能否全退、已部分使用如何折算），
 * 因此这里没有「退款」入口 —— 钱的事走线下，与卡的状态是两回事
 *（这也是 {@code REVOKED} 与 {@code REFUNDED} 分开的原因）。
 *
 * <p>整个类的接口都要求管理员身份 —— 注解标在类上而不是逐个方法上，
 * 少写一个就是接口裸奔，而那种疏漏不会有任何报错。
 */
@RestController
@RequestMapping("/api/admin/cards")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminCardController {

    private final MonthlyCardService cardService;

    public AdminCardController(MonthlyCardService cardService) {
        this.cardService = cardService;
    }

    /**
     * 分页查询月卡。
     *
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @param userId   持卡用户 ID 筛选，可空
     * @param status   状态筛选（ACTIVE / EXPIRED / REFUNDED / REVOKED），可空
     * @param cardType 卡类型筛选（ALL_DAY / NIGHT / STAFF），可空
     * @return 分页结果
     */
    @GetMapping
    public ResponseEntity<ApiResult<PageResult<MonthlyCardVo>>> list(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cardType) {
        return ApiResult.of(cardService.listCards(pageNum, pageSize, userId, status, cardType));
    }

    /**
     * 给指定用户开一张卡。
     *
     * <p>三条按卡种分岔的规则（STAFF 卡不校验已有卡、普通卡校验、实收金额）
     * 见 {@link MonthlyCardService#issueCard} 的注释。
     *
     * @param request 用户、卡种与选填的实收金额
     * @return 新卡视图；用户不存在或已有生效卡时返回对应错误码
     */
    @PostMapping
    public ResponseEntity<ApiResult<MonthlyCardVo>> issue(
            @Valid @RequestBody IssueCardRequest request) {
        return ApiResult.of(cardService.issueCard(
                request.getUserId(), request.getCardType(), request.getPrice()));
    }

    /**
     * 停用一张卡（收回权益）。
     *
     * <p>只受理生效中的卡（状态守卫在 SQL 里），重复点两下第二次会收到
     * 「不是生效中状态」而不是静默成功。STAFF 卡的结束只有这一条路 ——
     * 它的到期日在 2099，定时任务不会碰它。
     *
     * @param id 卡 ID
     * @return 停用后的卡视图；卡不存在或状态不对时返回对应错误码
     */
    @PostMapping("/{id}/revoke")
    public ResponseEntity<ApiResult<MonthlyCardVo>> revoke(@PathVariable Long id) {
        return ApiResult.of(cardService.revokeCard(id));
    }
}
