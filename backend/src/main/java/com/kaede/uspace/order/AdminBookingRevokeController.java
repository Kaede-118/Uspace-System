package com.kaede.uspace.order;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.security.UserPrincipal;
import com.kaede.uspace.order.dto.RevokeBookingRequest;
import com.kaede.uspace.space.dto.BookingVo;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 撤销包场并退款（管理员侧）。
 *
 * <p><b>路径挂在 {@code /api/admin/bookings} 下，类却住在 order 包</b> ——
 * 看着别扭，但这是依赖方向定死的：撤销要同时改包场状态（模块 3）
 * 与调支付平台退款（模块 8），而包级依赖是单向的 {@code order → space}。
 * 放在 space 包就得反过来依赖 order，那是错的。
 * 项目里已有同样的先例：{@code UserBookingController} 住在 order 包、
 * 路径却在 {@code /api/bookings}。
 *
 * <p><b>与 {@code DELETE /api/admin/bookings/{id}} 的分工</b>：
 * <ul>
 *   <li>{@code DELETE} —— 取消<b>待付款</b>的场次，没有钱的事</li>
 *   <li>本接口 —— 撤销<b>已付款</b>的场次，钱要退回去</li>
 * </ul>
 * 两个动作的后果完全不同，所以是两个端点、两个错误码，界面上也是两个按钮。
 * 合成一个「都行」的接口，前端就没法告诉管理员「这一下会不会动钱」。
 *
 * <p>权限声明标在类上而不是逐个方法上，理由同 {@code AdminUserController}
 * （漏写一个就是接口裸奔，而那种疏漏不会有任何报错）。
 */
@RestController
@RequestMapping("/api/admin/bookings")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminBookingRevokeController {

    private final BookingRefundService bookingRefundService;

    public AdminBookingRevokeController(BookingRefundService bookingRefundService) {
        this.bookingRefundService = bookingRefundService;
    }

    /**
     * 撤销一场已付款的包场并退款。
     *
     * @param id      包场 ID
     * @param request 退款方式
     * @param me      当前登录的管理员，登记为退款操作人
     * @return 撤销后的包场（状态为 {@code REFUNDED}，含退款方式、金额、时刻、操作人）
     */
    @PostMapping("/{id}/revoke")
    public ResponseEntity<ApiResult<BookingVo>> revoke(
            @PathVariable Long id,
            @Valid @RequestBody RevokeBookingRequest request,
            @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(bookingRefundService.revoke(id, request.getRefundMode(), me.id()));
    }
}
