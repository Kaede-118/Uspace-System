package com.kaede.uspace.promotion;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.promotion.dto.MonthlyCardVo;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 月卡管理接口（模块 9，运营后台）。
 *
 * <p><b>本期只读</b>：月卡的退款政策尚未拍板（未使用能否全退、已部分使用如何折算），
 * 因此这里不提供退款或作废入口，那类操作走人工流程改库。
 * 数据库里 {@code REFUNDED} 这个取值的语义已经定下，
 * 一旦管理员把卡置为已退款，免单会立刻停止（判定同时校验状态与日期）。
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
     * @param status   状态筛选（ACTIVE / EXPIRED / REFUNDED），可空
     * @param cardType 卡类型筛选（ALL_DAY / NIGHT），可空
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
}
