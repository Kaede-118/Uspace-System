package com.kaede.uspace.space;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.space.dto.StoreStatusVo;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 门店接口（模块 3 的用户端）。
 *
 * <p>只有一个接口：查当前营业状态。用户端首页需要它来渲染
 * 「营业中 / 暂停营业 / 已被包场」以及门店名称与地址。
 *
 * <p><b>本接口允许匿名访问</b>（配在 {@code SecurityConfig} 的放行列表里）。
 * 门店名称、地址与「此刻是否营业」是面向公众的信息 —— 相当于店门口挂的牌子，
 * 没有理由要求先注册才能看。让新顾客先登录才能知道店在哪、开没开门，
 * 是把人挡在门外的做法。
 *
 * <p><b>但停业原因与包场人不会出现在响应里</b>：那道边界在
 * {@link StoreStatusVo} 上，运营内务与顾客隐私都不外泄。
 */
@RestController
@RequestMapping("/api/store")
public class StoreController {

    private final StoreService storeService;

    public StoreController(StoreService storeService) {
        this.storeService = storeService;
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
}
