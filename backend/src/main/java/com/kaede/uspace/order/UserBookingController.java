package com.kaede.uspace.order;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.common.security.UserPrincipal;
import com.kaede.uspace.space.BookingService;
import com.kaede.uspace.space.dto.BookingInviteVo;
import com.kaede.uspace.space.dto.BookingVo;
import com.kaede.uspace.space.dto.InviteLinkVo;
import com.kaede.uspace.space.dto.JoinResultVo;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端的包场接口。
 *
 * <p><b>路径归包场、代码归模块 8</b>，这是一处刻意的安排：
 * 这里几个接口都是模块 8 引入的需求 —— 包场人的<b>付款入口</b>与<b>取邀请链接</b>、
 * 被邀请者的<b>查看与加入入口</b>，而模块 8 是单向依赖 {@code space} 包的。
 * 若把它们放进 {@code space} 包，那边就要反过来依赖模块 8 的邀请令牌服务，
 * 两个包级的依赖就成环了。
 *
 * <p>包场的<b>排期</b>接口（增删改与后台列表）仍在 {@code space} 包的
 * {@code AdminBookingController} 里 —— 那部分与收款无关，属于模块 3。
 *
 * <p><b>两个列表接口各查各的数据源</b>，这是刻意的：
 * <ul>
 *   <li>{@code /host} —— 我发起的场次，按 {@code biz_booking.host_user_id} 查，
 *       <b>含待付款的</b>。包场人要靠它找到待付款的场、点进付款入口</li>
 *   <li>{@code /joined} —— 我被邀请的场次，按参与者表的 {@code role='PARTICIPANT'} 查。
 *       它天然只含已付款的场（参与记录是付款那一刻才开始有的）</li>
 * </ul>
 * 若两个都从参与者表出发，{@code /host} 就会漏掉待付款的场次 ——
 * 而 {@code HOST} 行恰恰是付款成功才写入的。
 */
@RestController
@RequestMapping("/api/bookings")
@Validated
public class UserBookingController {

    private final BookingService bookingService;
    private final InviteTokenService inviteTokenService;

    public UserBookingController(BookingService bookingService,
                                 InviteTokenService inviteTokenService) {
        this.bookingService = bookingService;
        this.inviteTokenService = inviteTokenService;
    }

    /**
     * 分页查询我作为包场人的场次（含待付款的）。
     *
     * <p>被邀请者看不到这个列表，他走 {@code /joined}。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数，最多 100
     * @param me   当前登录用户
     * @return 分页结果，按开始时间倒序
     */
    @GetMapping("/host")
    public ResponseEntity<ApiResult<PageResult<BookingVo>>> myHostBookings(
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = 100, message = "每页最多 100 条") long size,
            @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(bookingService.listHostBookings(me.id(), page, size));
    }

    /**
     * 分页查询我作为被邀请者参与的场次。
     *
     * <p>「我参与的」与「我发起的」是两个列表：同一场包场不会同时出现在两边
     * （发起人的角色是 {@code HOST}，不在这条查询的筛选范围内）。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数，最多 100
     * @param me   当前登录用户
     * @return 分页结果，按开始时间倒序
     */
    @GetMapping("/joined")
    public ResponseEntity<ApiResult<PageResult<BookingVo>>> myJoinedBookings(
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = 100, message = "每页最多 100 条") long size,
            @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(bookingService.listJoinedBookings(me.id(), page, size));
    }

    /**
     * 凭邀请令牌查看包场信息与参与者名单（被邀请者点开分享链接时调用）。
     *
     * <p><b>只读，不产生任何副作用</b> —— 所以它<b>不会</b>把调用者记进名单。
     * 这是刻意的：GET 会被浏览器预取、被刷新、被爬虫重复请求，
     * 让它们决定「谁进了名单」是不对的。真正的加入是落地页加载完成后
     * 单独发的那次 POST（下面的 {@code /invite/{token}/join}），
     * 用户感受完全一样（点开链接就进去了）。
     *
     * <p><b>令牌的有效性由包场时段界定</b>，不单独设过期时间：
     * 时段一过、或包场被撤销退款，令牌立即失效（判在 {@code InviteTokenService} 里）。
     *
     * @param token 邀请令牌（43 位 URL-safe 字符串）
     * @return 包场信息与参与者名单；令牌无效时返回 404
     */
    @GetMapping("/invite/{token}")
    public ResponseEntity<ApiResult<BookingInviteVo>> invite(@PathVariable String token) {
        return ApiResult.of(inviteTokenService.findByToken(token));
    }

    /**
     * 凭邀请令牌加入包场（落地页加载后自动调用，用户无感）。
     *
     * <p>重复点开同一条链接不是错误：返回 {@code alreadyJoined=true}，
     * 前端据此提示「你已经进入过了」，而不是弹一个错误框。
     *
     * @param token 邀请令牌
     * @param me    当前登录用户
     * @return 加入结果（是否新加入、当前人数）；令牌无效时返回 404
     */
    @PostMapping("/invite/{token}/join")
    public ResponseEntity<ApiResult<JoinResultVo>> join(@PathVariable String token,
                                                        @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(inviteTokenService.join(token, me.id()));
    }

    /**
     * 取某场包场的邀请链接（<b>只有包场人本人</b>）。
     *
     * <p>付款成功后才能取 —— 令牌与参与者名单都是那一刻才生成的。
     * 不是本人的场次返回 404 而非 403：后者等于承认这场包场存在。
     *
     * @param id 包场 ID
     * @param me 当前登录用户
     * @return 链接信息（含 path 与 url）；不是本人 404，未付款 409
     */
    @GetMapping("/{id}/invite-link")
    public ResponseEntity<ApiResult<InviteLinkVo>> inviteLink(@PathVariable Long id,
                                                              @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(inviteTokenService.getInviteLink(id, me.id()));
    }
}
