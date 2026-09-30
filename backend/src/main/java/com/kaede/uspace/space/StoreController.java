package com.kaede.uspace.space;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.space.dto.BookingScheduleVo;
import com.kaede.uspace.space.dto.StoreStatusVo;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 门店接口（模块 3 的用户端）。
 *
 * <p>两个接口：查当前营业状态，以及查包场时间表。首页顶部那两块信息
 * ——「现在开不开门」与「接下来哪些时段进不去」—— 都从这里来。
 *
 * <p><b>两个接口都允许匿名访问</b>（配在 {@code SecurityConfig} 的放行列表里）。
 * 门店名称、地址、「此刻是否营业」、以及未来哪些时段被包了，
 * 都是面向公众的信息 —— 相当于店门口挂的牌子与贴在墙上的场地安排表，
 * 没有理由要求先注册才能看。让新顾客先登录才能知道店在哪、开没开门，
 * 是把人挡在门外的做法。
 *
 * <p><b>但停业原因与包场人不会出现在响应里</b>：那道边界在
 * {@link StoreStatusVo} 与 {@link BookingScheduleVo} 上 ——
 * 后者的字段列表里根本没有「包场人」这一项，运营内务与顾客隐私都不外泄。
 */
@RestController
@RequestMapping("/api/store")
public class StoreController {

    private final StoreService storeService;
    private final BookingService bookingService;

    public StoreController(StoreService storeService, BookingService bookingService) {
        this.storeService = storeService;
        this.bookingService = bookingService;
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
}
