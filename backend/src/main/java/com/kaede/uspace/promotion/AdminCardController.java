package com.kaede.uspace.promotion;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.promotion.dto.IssueCardRequest;
import com.kaede.uspace.promotion.dto.MonthlyCardOrderVo;
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
 * <p>四个动作：查<b>卡</b>、查<b>购买单</b>、<b>开卡</b>与<b>停卡</b>。
 * 两种查询是两张表的两种流水，别混（见 {@link #listOrders} 的注释）。
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
     * 分页查询月卡<b>购买单</b>（运营后台「订单流水」页的「月卡」流水）。
     *
     * <p><b>它与上面的 {@link #list} 是两张表的两种流水</b>：那边回答「手上有什么卡」，
     * 这边回答「买过几次、付没付成」。未付款与已关闭的单<b>只存在于购买单表</b> ——
     * 卡表里根本没有它们（还没付钱的卡不是卡），所以「这人到底下过单没有」
     * 只能看这里；反过来「这人现在有没有卡」也只能看卡表。
     *
     * <p>⚠️ <b>路径取 {@code /orders} 是安全的</b>：本类没有
     * {@code GET /{id}} 这类通配端点（只有 {@code POST /{id}/revoke}），
     * 因此不会出现「{@code orders} 被当成 ID」的歧义。将来若要加查单条卡的路由，
     * 别写成能同时匹配 {@code /orders} 的形式。
     *
     * <p>状态与卡种的合法性由 Service 校验：库里两列都是 {@code VARCHAR}，
     * 非法值拿去做等值查询不会报错，只会查出一片空白。
     *
     * @param pageNum  页码，从 1 开始（与月卡列表同一套命名，不是 {@code page}）
     * @param pageSize 每页条数
     * @param userId   购买人用户 ID 筛选，可空
     * @param status   购买单状态筛选（PENDING_PAYMENT / PAID / CLOSED），可空
     * @param cardType 卡种筛选（ALL_DAY / NIGHT / STAFF），可空
     * @return 分页结果，每条含中文的 {@code statusLabel} / {@code cardTypeLabel}
     *         与批量补上的 {@code userNickname}；状态或卡种取值非法时返回 {@code PARAM_INVALID}
     */
    @GetMapping("/orders")
    public ResponseEntity<ApiResult<PageResult<MonthlyCardOrderVo>>> listOrders(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cardType) {
        return ApiResult.of(cardService.listCardOrders(pageNum, pageSize, userId, status, cardType));
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
