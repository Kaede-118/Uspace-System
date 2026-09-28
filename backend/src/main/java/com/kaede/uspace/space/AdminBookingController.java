package com.kaede.uspace.space;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.common.security.UserPrincipal;
import com.kaede.uspace.space.dto.BookingVo;
import com.kaede.uspace.space.dto.CreateBookingRequest;
import com.kaede.uspace.space.dto.UpdateBookingRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 包场排期接口（模块 3 的管理员侧）。
 *
 * <p><b>这里只做排期，不做收款。</b>创建出来的包场处于「待付款」状态，
 * 由模块 8 完成下单、支付与邀请链接的生成。分成两个模块的理由是：
 * 排期是运营动作（谁、什么时候、多少钱），收款是支付动作（下单、回调、对账），
 * 两者的失败模式与排查路径完全不同。
 *
 * <p>路径取 {@code /api/admin/bookings} 而不是挂在 {@code /api/admin/store} 下：
 * 包场虽有门店归属，但它有自己独立的生命周期（待付款 → 已付款 → 已结束），
 * 将来开分店后还会是后台最常打交道的列表之一，独立成一级路径更合适。
 *
 * @see AdminStoreController 门店信息与停业记录
 */
@RestController
@RequestMapping("/api/admin/bookings")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminBookingController {

    private final BookingService bookingService;

    public AdminBookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /**
     * 分页查询包场记录。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数，上限 100（超出由分页插件截断）
     * @return 分页的包场记录
     */
    @GetMapping
    public ResponseEntity<ApiResult<PageResult<BookingVo>>> list(
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = 100, message = "每页最多 100 条") long size) {
        return ApiResult.of(bookingService.listBookings(page, size));
    }

    /**
     * 创建包场（排期）。
     *
     * <p>新场次处于「待付款」状态：时段已被占住（不会再排进别的包场），
     * 但准入尚未生效 —— 包场人付款后才产生排他性，在那之前散客照常可以进店。
     * 这样安排是为了避免「管理员排了期但对方一直不付款，店白空一个时段」。
     *
     * @param request 包场时段、包场人与价格
     * @param me      当前登录的管理员，登记为排期人
     * @return 新建的包场记录
     */
    @PostMapping
    public ResponseEntity<ApiResult<BookingVo>> create(
            @Valid @RequestBody CreateBookingRequest request,
            @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(bookingService.createBooking(request, me.id()));
    }

    /**
     * 修改包场排期（改期与改价）。
     *
     * <p>只有待付款的包场可以修改 —— 已付款的改期要同时处理退款，
     * 那属于模块 8 与运营流程。
     *
     * @param id      包场 ID
     * @param request 新的时段与价格
     * @return 成功时 data 为 null
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResult<Void>> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateBookingRequest request) {
        return ApiResult.of(bookingService.updateBooking(id, request));
    }

    /**
     * 取消包场。
     *
     * <p>同样是逻辑删除，且只有待付款的可以取消，理由同上。
     *
     * @param id 包场 ID
     * @return 成功时 data 为 null
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResult<Void>> cancel(@PathVariable Long id) {
        return ApiResult.of(bookingService.cancelBooking(id));
    }
}
