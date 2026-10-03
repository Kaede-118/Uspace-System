package com.kaede.uspace.space;

import com.kaede.uspace.billing.FreePeriodService;
import com.kaede.uspace.billing.dto.FreePeriodVo;
import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.space.dto.BookingScheduleVo;
import com.kaede.uspace.space.dto.StoreStatusVo;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 门店接口（模块 3 的用户端）。
 *
 * <p>三个接口：查当前营业状态、查包场时间表、查近期免费活动。首页那几块信息
 * ——「现在开不开门」「接下来哪些时段进不去」「接下来哪些时段不收钱」—— 都从这里来。
 *
 * <p><b>三个接口都允许匿名访问</b>（配在 {@code SecurityConfig} 的放行列表里）。
 * 门店名称、地址、「此刻是否营业」、未来哪些时段被包了、哪些时段免费，
 * 都是面向公众的信息 —— 相当于店门口挂的牌子与贴在墙上的场地安排表，
 * 没有理由要求先注册才能看。让新顾客先登录才能知道店在哪、开没开门，
 * 是把人挡在门外的做法。
 *
 * <p><b>但停业原因与包场人不会出现在响应里</b>：那道边界在
 * {@link StoreStatusVo} 与 {@link BookingScheduleVo} 上 ——
 * 后者的字段列表里根本没有「包场人」这一项，运营内务与顾客隐私都不外泄。
 *
 * <p><b>免费活动（{@code /free-periods}）为什么挂在本包</b>：它归模块 7
 * （计费规则），但 {@code FreePeriodService} 刻意不认识门店
 * （避免 {@code billing → space} 与本包成环），所以由本包提供接口、
 * 把当前门店 ID 递过去。见 {@code AdminStoreController} 的同款说明。
 */
@RestController
@RequestMapping("/api/store")
public class StoreController {

    private final StoreService storeService;
    private final BookingService bookingService;
    private final FreePeriodService freePeriodService;

    public StoreController(StoreService storeService, BookingService bookingService,
                           FreePeriodService freePeriodService) {
        this.storeService = storeService;
        this.bookingService = bookingService;
        this.freePeriodService = freePeriodService;
    }

    /**
     * 查询当前营业状态与门店信息。
     *
     * <p>状态取值：{@code OPEN} 营业中 / {@code BOOKED} 已被包场 / {@code CLOSED} 暂停营业。
     * 后两种会一并给出结束时刻，供前端展示「14:00 恢复营业」。
     *
     * @return 营业状态与门店信息
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResult<StoreStatusVo>> status() {
        return ApiResult.of(storeService.getStoreStatus());
    }

    /**
     * 查询包场时间表：尚未结束的已付款包场。
     *
     * <p>与 {@code /status} 的分工：{@code /status} 回答「<b>现在</b>能不能进」，
     * 本接口回答「<b>接下来</b>哪些时段不能进」。
     * 只给时段，不给包场人 —— 见 {@link BookingScheduleVo} 的类注释。
     *
     * <p>每条带一个 {@code ongoing} 标记，前端据此把正在进行的那行标出来：
     * 用户当场进不去时，看到时间表上那行亮着就知道原因，
     * 而不是以为门坏了。
     *
     * @param limit 最多几条，默认 10，最大 20；超范围时自动截到边界值而不报错
     * @return 时间表，按开始时间升序（从近到远）
     */
    @GetMapping("/bookings")
    public ResponseEntity<ApiResult<List<BookingScheduleVo>>> bookings(
            @RequestParam(required = false) Integer limit) {
        return ApiResult.of(bookingService.listSchedule(limit));
    }

    /**
     * 查询近期免费活动：尚未结束的活动（含正在进行的）。
     *
     * <p>与 {@code /bookings} 的分工：那个回答「接下来哪些时段<b>进不去</b>」，
     * 本接口回答「接下来哪些时段<b>不收钱</b>」。两者可以同时成立
     * （活动期间恰好也是某场包场的时段），前端各显示各的。
     *
     * <p>正在进行的活动也在列表里 —— 「今晚 20:00–次日 02:00 免费」这条
     * 在 21:00 打开首页时正是最该看见的。
     *
     * @param limit 最多几条，默认 3，最大 20；越界时自动截到边界值而不报错
     * @return 活动列表，按开始时间升序（从近到远）
     */
    @GetMapping("/free-periods")
    public ResponseEntity<ApiResult<List<FreePeriodVo>>> freePeriods(
            @RequestParam(required = false) Integer limit) {
        Long storeId = storeService.getCurrentStoreId();
        if (storeId == null) {
            return ApiResult.of(BizResult.fail(ErrorCode.STORE_NOT_FOUND));
        }
        return ApiResult.of(BizResult.ok(
                freePeriodService.listUpcoming(storeId, LocalDateTime.now(), limit)));
    }
}
