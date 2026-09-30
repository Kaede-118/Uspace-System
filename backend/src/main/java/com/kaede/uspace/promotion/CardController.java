package com.kaede.uspace.promotion;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.security.UserPrincipal;
import com.kaede.uspace.promotion.dto.CardPurchaseVo;
import com.kaede.uspace.promotion.dto.CardTypeVo;
import com.kaede.uspace.promotion.dto.CardWalletVo;
import com.kaede.uspace.promotion.dto.PurchaseCardRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 月卡接口（模块 9，用户端）。
 *
 * <p><b>这里不提供付款接口</b>：购买单创建出来后，付款走模块 8 的统一入口
 * {@code POST /api/payments}（{@code targetType = MONTHLY_CARD}，
 * {@code targetId} 传购买单 ID），微信 / 支付宝三条通道与回调全部复用。
 * 于是「加一类收款」只多了一个处理器实现，支付链路一行未改。
 *
 * <pre>
 *   POST /api/cards/purchases   →  拿到购买单与单号
 *   POST /api/payments          →  拿到各通道的支付参数
 *   POST /api/payments/mock/pay →  （模拟模式）完成付款
 *   GET  /api/cards/me          →  卡包里出现生效中的卡
 * </pre>
 */
@RestController
@RequestMapping("/api/cards")
@Validated
public class CardController {

    private final MonthlyCardService cardService;

    public CardController(MonthlyCardService cardService) {
        this.cardService = cardService;
    }

    /**
     * 列出在售卡种与价格。
     *
     * <p>供购买页展示。价格来自后端配置，前端不参与定价。
     *
     * @return 卡种列表
     */
    @GetMapping("/types")
    public ResponseEntity<ApiResult<List<CardTypeVo>>> types() {
        return ApiResult.of(cardService.cardTypes());
    }

    /**
     * 发起购买月卡。
     *
     * <p>创建一笔待支付的购买单，<b>此时还没有卡</b> —— 付款成功后才会生成。
     * 已有生效中的卡、或已有一笔未超时的待支付单时会被拒绝。
     *
     * @param request 卡种
     * @param me      当前登录用户
     * @return 购买单（含单号与应付金额）
     */
    @PostMapping("/purchases")
    public ResponseEntity<ApiResult<CardPurchaseVo>> purchase(
            @Valid @RequestBody PurchaseCardRequest request,
            @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(cardService.purchase(me.id(), request.getCardType()));
    }

    /**
     * 取消一笔待支付的购买单。
     *
     * <p><b>这不是退款</b> —— 那笔钱从来没付过。取消只是让用户可以立刻
     * 重新选择卡种，不必等存活时长过去。
     *
     * @param id 购买单 ID
     * @param me 当前登录用户
     * @return 成功返回空数据
     */
    @PostMapping("/purchases/{id}/cancel")
    public ResponseEntity<ApiResult<Void>> cancelPurchase(@PathVariable Long id,
                                                          @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(cardService.cancelPurchase(me.id(), id));
    }

    /**
     * 查我的卡包。
     *
     * <p>一次给出三样：此刻生效的卡、待支付的购买单、历史卡。
     *
     * @param me 当前登录用户
     * @return 卡包
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResult<CardWalletVo>> wallet(@AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(cardService.wallet(me.id()));
    }
}
