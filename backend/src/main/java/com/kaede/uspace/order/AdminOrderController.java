package com.kaede.uspace.order;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.common.security.UserPrincipal;
import com.kaede.uspace.order.dto.AdjustOrderRequest;
import com.kaede.uspace.order.dto.OrderSettleVo;
import com.kaede.uspace.order.dto.OrderVo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.LocalDateTime;

/**
 * 后台订单接口（模块 8）。
 *
 * <p>权限声明放在<b>类上</b>而不是每个方法上 —— 少一处遗漏的机会。
 *
 * <p>这里有一个「人工兜底」的入口：调整时长。它的存在不是因为系统不可靠，
 * 而是因为这件事本质上需要人判断 —— 顾客可能忘记点「结束使用」，
 * 只有查了监控才知道他是什么时候走的。
 *
 * <p><b>核销付款凭证的入口不在这里</b>，而在 {@code AdminPaymentProofController}：
 * 凭证是四类收款共用的（订单 / 包场 / 月卡 / 商品），挂在订单下面
 * 就等于给它开了一个只认订单的旁路 —— 那正是 2026-09-30 改造前的情形。
 */
@RestController
@RequestMapping("/api/admin/orders")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminOrderController {

    private final OrderService orderService;

    public AdminOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 分页查询订单。
     *
     * @param page     页码，从 1 开始
     * @param size     每页条数，最多 100
     * @param userId   用户 ID 筛选，可空
     * @param status   状态筛选，可空
     * @param from     计费起点下界（含），可空
     * @param to       计费起点上界（不含），可空
     * @param adjusted 是否经人工调整（0/1），可空。传 1 可筛出所有被改过时长的订单 ——
     *                 那是「用户忘记点结束」的高发信号，值得定期过一眼
     * @return 分页结果
     */
    @GetMapping
    public ResponseEntity<ApiResult<PageResult<OrderVo>>> list(
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = 100, message = "每页最多 100 条") long size,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String status,
            // 查询参数走的是 Spring 的类型转换，不是 Jackson —— JacksonConfig 里配的
            // 时间格式对 @RequestParam 不生效，这里必须显式声明格式，
            // 否则带空格的日期串会在进方法之前就被拒掉
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to,
            @RequestParam(required = false) Integer adjusted) {
        return ApiResult.of(orderService.listOrders(page, size, userId, status, from, to, adjusted));
    }

    /**
     * 查询订单详情。
     *
     * @param id 订单 ID
     * @return 订单视图
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResult<OrderVo>> detail(@PathVariable Long id) {
        return ApiResult.of(orderService.getOrderForAdmin(id));
    }

    /**
     * 人工调整订单时长并重算金额。
     *
     * <p>典型场景：顾客玩完直接走了、忘了点「结束使用」，管理员查监控后
     * 把离场时刻改过来，让账单能出来收款。使用中的订单调整后会自动转待支付 ——
     * 不转的话，一条永远不会被点结束的订单就永远收不到钱。
     *
     * <p>已支付的订单不允许调整（涉及退款，走人工流程）。
     *
     * @param id      订单 ID
     * @param request 新的离场时刻与调整原因
     * @param me      当前登录的管理员
     * @return 重算后的账单
     */
    @PostMapping("/{id}/adjust")
    public ResponseEntity<ApiResult<OrderSettleVo>> adjust(@PathVariable Long id,
                                                           @Valid @RequestBody AdjustOrderRequest request,
                                                           @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(orderService.adjustOrder(id, request, me.id()));
    }
}
