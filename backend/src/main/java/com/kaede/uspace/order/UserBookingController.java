package com.kaede.uspace.order;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.common.security.UserPrincipal;
import com.kaede.uspace.space.BookingService;
import com.kaede.uspace.space.dto.BookingVo;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端的包场接口。
 *
 * <p><b>路径归包场、代码归模块 8</b>，这是一处刻意的安排：
 * 这两个接口都是模块 8 引入的需求 —— 包场人的<b>付款入口</b>与
 * 被邀请者的<b>查看入口</b>，而模块 8 是单向依赖 {@code space} 包的。
 * 若把它们放进 {@code space} 包，那边就要反过来依赖模块 8 的邀请令牌服务，
 * 两个包级的依赖就成环了。
 *
 * <p>包场的<b>排期</b>接口（增删改与后台列表）仍在 {@code space} 包的
 * {@code AdminBookingController} 里 —— 那部分与收款无关，属于模块 3。
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
     * <p>被邀请者看不到这个列表 —— 他不记在 {@code host_user_id} 上，
     * 只能凭邀请链接查看那一场（见下面那个接口）。这不是遗漏：
     * 邀请关系本就只存在于「包场人分享出去的那条链接」里，系统没有记录谁被邀请了。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数，最多 100
     * @param me   当前登录用户
     * @return 分页结果，按开始时间倒序
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResult<PageResult<BookingVo>>> myBookings(
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = 100, message = "每页最多 100 条") long size,
            @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(bookingService.listMyBookings(me.id(), page, size));
    }

    /**
     * 凭邀请令牌查看包场信息（被邀请者点开分享链接时调用）。
     *
     * <p>只读，不产生任何副作用 —— 被邀请者看完之后要不要去、什么时候点「开门」，
     * 都由他自己决定。真正让他能进门的是「下单拿到密码」那一步，
     * 而那时需要在下单请求里带上这个令牌。
     *
     * <p><b>令牌的有效性由包场时段界定</b>，不单独设过期时间：
     * 时段一过，那个场次的排他性自然消失，令牌也就没有意义了。
     *
     * @param token 邀请令牌（43 位 URL-safe 字符串）
     * @return 包场信息；令牌无效时返回 404
     */
    @GetMapping("/invite/{token}")
    public ResponseEntity<ApiResult<BookingVo>> invite(@PathVariable String token) {
        return ApiResult.of(inviteTokenService.findByToken(token));
    }
}
