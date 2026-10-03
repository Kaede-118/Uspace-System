package com.kaede.uspace.order;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.common.security.UserPrincipal;
import com.kaede.uspace.order.dto.CreateOrderRequest;
import com.kaede.uspace.order.dto.MonthSpentVo;
import com.kaede.uspace.order.dto.OrderOpenVo;
import com.kaede.uspace.order.dto.OrderPreviewVo;
import com.kaede.uspace.order.dto.OrderSettleVo;
import com.kaede.uspace.order.dto.OrderStatsVo;
import com.kaede.uspace.order.dto.OrderVo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
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
 * 用户端订单接口（模块 8）。
 *
 * <p><b>路径里不出现用户 ID</b>：身份一律从凭证里取（{@link UserPrincipal}），
 * 从根上杜绝「改一下 URL 里的 ID 就能看别人的订单」这类越权 ——
 * 压根没有那个 ID 可改。
 *
 * <p>类上没有 {@code @PreAuthorize}：这些接口对任何已登录用户开放，
 * 「只能操作自己的订单」由 Service 层按 {@code userId} 保证。
 */
@RestController
@RequestMapping("/api/orders")
@Validated
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 查询本月的累计消费与优惠资格。
     *
     * <p>回答的是「我这个月花了多少、还差多少能享优惠价、下一单按哪个价算」——
     * 门槛是 200 元这种规则用户猜不到，而它直接决定下一单要花多少钱。
     *
     * <p>口径与结算一致（只算已支付的、不含月卡卡费），判定也直接复用计费服务，
     * 不在别处重算一遍。
     *
     * @param me 当前登录用户
     * @return 本月累计额、门槛、是否已享优惠、还差多少
     */
    @GetMapping("/me/month-spent")
    public ResponseEntity<ApiResult<MonthSpentVo>> monthSpent(
            @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(orderService.monthSpent(me.id()));
    }

    /**
     * 查询我的累计时长与本月时长。
     *
     * <p>与月累计消费并排回答「我一共玩了多久、这个月玩了多久」。
     * 统计的是<b>在店时长</b>（含包场时段、含宽限那 5 分钟），
     * 不是计费时长 —— 包场用户按计费口径会看到「累计 0 分钟」。
     *
     * <p>与 {@code /me/month-spent} 分开两个接口：一个是时长、一个是金额，
     * 前端并发调即可，谁也不该等谁。
     *
     * @param me 当前登录用户
     * @return 累计在店分钟数、本月在店分钟数与本月起始日期
     */
    @GetMapping("/me/stats")
    public ResponseEntity<ApiResult<OrderStatsVo>> stats(@AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(orderService.stats(me.id()));
    }

    /**
     * 点击「开门」：创建订单、下发限时密码、开始计费。
     *
     * <p><b>用户端只点这一次</b>，不存在「先下单、再开门」两步。
     * 前端可以加二次确认弹窗，但后端只有这一个入口。
     *
     * <p><b>上一单未结清时会被挡下，且两种情形给的是不同的码</b>：
     * <ul>
     *   <li>{@code ORDER_ALREADY_ACTIVE} —— 人还在店里玩。前端应转而调「查看密码」，
     *       而不是把它当成错误展示</li>
     *   <li>{@code ORDER_UNPAID_EXISTS} —— 玩完了但账单还欠着。前端应引导到「去支付」</li>
     * </ul>
     * 两者的可行动作不同，前端要分开处置 —— 这是把它们分成两个码的全部理由。
     *
     * @param request 请求体，可携带包场邀请令牌
     * @param me      当前登录用户
     * @return 门锁密码与有效期
     */
    @PostMapping
    public ResponseEntity<ApiResult<OrderOpenVo>> open(@Valid @RequestBody CreateOrderRequest request,
                                                       @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(orderService.createOrder(me.id(), request));
    }

    /**
     * 查询当前进行中的订单。
     *
     * <p>前端首页靠它决定「开门」按钮的语义：没有进行中的订单就是「开门并开始计费」，
     * 有就是「查看密码」。没有这个接口，前端只能靠试错。
     *
     * <p><b>只返回使用中的订单，不含待支付的</b>：待支付的单已经结算、
     * 密码也撤销了，塞进来会让用户看到一个点不动的「查看密码」。
     * 首页要展示「你有 N 笔未付款」，另调 {@code GET /api/orders/me?status=PENDING_PAYMENT}。
     *
     * @param me 当前登录用户
     * @return 订单视图；没有进行中的订单时 {@code data} 为 null
     */
    @GetMapping("/current")
    public ResponseEntity<ApiResult<OrderVo>> current(@AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(orderService.findCurrentOrder(me.id()));
    }

    /**
     * 查看门锁密码，已过期时自动续期。
     *
     * <p>用于「玩到一半出门买水、回来还要进门」的场景。续期<b>不换密码</b>，
     * 用户截图里的那串数字始终有效。
     *
     * @param id 订单 ID
     * @param me 当前登录用户
     * @return 密码与有效期，并标明本次是否续期、是否换了密码
     */
    @GetMapping("/{id}/passcode")
    public ResponseEntity<ApiResult<OrderOpenVo>> passcode(@PathVariable Long id,
                                                           @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(orderService.currentPasscode(me.id(), id));
    }

    /**
     * 结账预览：算出「此刻停止计时的话要付多少」，计时照走、密码照旧。
     *
     * <p>纯查询，没有副作用 —— 订单状态不变、门锁密码不撤销、不消耗门锁云额度。
     * 用户看完这一屏再点「停止计时」（{@code POST /{id}/settle}）才算确认结束，
     * 按已确认的产品决定，不再有二次确认弹窗。
     *
     * <p><b>前端应持续轮询本接口</b>（约 10 秒一次）：金额随时长增长，
     * 屏幕上的数字要一直是新的，「确认」才是对当下一刻的确认。
     * 比对 {@code previewAt} 丢弃乱序到达的旧响应；用户点完「停止计时」后
     * 立刻停掉轮询，否则会收到 409 —— 那个 409 不是错误，而是「本页使命结束」，
     * 前端应据此刷新订单状态，不要弹「操作失败」。
     *
     * @param id 订单 ID
     * @param me 当前登录用户
     * @return 预览账单：在店时长、当前金额、是否封顶、停止后是否无需支付
     */
    @GetMapping("/{id}/preview")
    public ResponseEntity<ApiResult<OrderPreviewVo>> preview(@PathVariable Long id,
                                                             @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(orderService.previewOrder(me.id(), id));
    }

    /**
     * 结束使用：算出账单并转待支付。
     *
     * <p>金额为 0 时（5 分钟内出场，或整段被包场覆盖）直接结清为已支付，
     * 不会让用户看到一个「0 元去支付」的按钮。
     *
     * @param id 订单 ID
     * @param me 当前登录用户
     * @return 分段账单，前端按段展示
     */
    @PostMapping("/{id}/settle")
    public ResponseEntity<ApiResult<OrderSettleVo>> settle(@PathVariable Long id,
                                                           @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(orderService.settleOrder(me.id(), id));
    }

    /**
     * 分页查询我的订单。
     *
     * @param page   页码，从 1 开始
     * @param size   每页条数，最多 100
     * @param status 状态筛选，可空
     * @param me     当前登录用户
     * @return 分页结果，按 id 倒序（新订单在前）
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResult<PageResult<OrderVo>>> myOrders(
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = 100, message = "每页最多 100 条") long size,
            @RequestParam(required = false) String status,
            @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(orderService.listMyOrders(me.id(), page, size, status));
    }

    /**
     * 查询我的订单详情。
     *
     * <p>非本人的订单返回 404 而不是 403 —— 403 等于承认「这个订单存在，
     * 只是不归你」，可以被用来枚举订单号。
     *
     * @param id 订单 ID
     * @param me 当前登录用户
     * @return 订单视图
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResult<OrderVo>> detail(@PathVariable Long id,
                                                     @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(orderService.getMyOrder(me.id(), id));
    }
}
