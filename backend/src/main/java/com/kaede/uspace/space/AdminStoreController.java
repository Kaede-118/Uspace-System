package com.kaede.uspace.space;

import com.kaede.uspace.billing.FreePeriodService;
import com.kaede.uspace.billing.dto.FreePeriodRequest;
import com.kaede.uspace.billing.dto.FreePeriodVo;
import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.common.security.UserPrincipal;
import com.kaede.uspace.space.dto.ClosureRequest;
import com.kaede.uspace.space.dto.ClosureVo;
import com.kaede.uspace.space.dto.StoreVo;
import com.kaede.uspace.space.dto.UpdateStoreRequest;
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
 * 门店、停业与免费时段的管理接口（模块 3 的管理员侧）。
 *
 * <p>路径前缀 {@code /api/admin}，与用户端的 {@code /api/store} 分开。
 *
 * <p><b>权限声明放在类上而不是每个方法上</b>：本类的每个接口都要求管理员，
 * 逐个方法写 {@code @PreAuthorize} 只是重复，且将来新增方法时容易漏掉 ——
 * 漏掉的后果是接口裸奔，而且不会有任何报错提醒。详见 {@code AdminUserController} 的同款说明。
 *
 * <p><b>停业记录挂在 {@code /closures} 子路径下</b>，而不是平级的
 * {@code /api/admin/closures}：它是「门店在某段时间不营业」，
 * 语义上从属于门店，路径也应当体现这层关系。
 *
 * <p><b>免费时段（{@code /free-periods}）为什么也挂在本类下</b>：它虽然归模块 7
 * （是计费规则，见 {@code FreePeriod} 的类注释），但 {@code billing} 包的
 * {@code FreePeriodService} <b>刻意不认识门店</b> —— 它若注入 {@code StoreMapper}
 * 就建立了 {@code billing → space} 这条边，而本包的门店页又要反过来调它，
 * 两边一成环就再也拆不开了。于是由本包提供接口、把当前门店 ID 递过去。
 * 从运营角度看两者也确实是同一件事的两面：「哪段时间不营业」与「哪段时间不收钱」。
 *
 * @see AdminBookingController 包场排期
 */
@RestController
@RequestMapping("/api/admin/store")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminStoreController {

    private final StoreService storeService;
    private final ClosureService closureService;
    private final FreePeriodService freePeriodService;

    public AdminStoreController(StoreService storeService, ClosureService closureService,
                                FreePeriodService freePeriodService) {
        this.storeService = storeService;
        this.closureService = closureService;
        this.freePeriodService = freePeriodService;
    }

    // ==================================================================
    // 门店信息
    // ==================================================================

    /**
     * 查询门店信息。
     *
     * <p>管理后台在渲染「门店设置」表单时调用，取当前的名称、地址与说明。
     *
     * @return 门店信息
     */
    @GetMapping
    public ResponseEntity<ApiResult<StoreVo>> getStore() {
        return ApiResult.of(storeService.getCurrentStore());
    }

    /**
     * 修改门店信息。
     *
     * <p>全量替换语义：字段传 null 即清空该项。
     *
     * @param request 门店名称、地址与说明
     * @return 更新后的门店信息
     */
    @PutMapping
    public ResponseEntity<ApiResult<StoreVo>> updateStore(
            @Valid @RequestBody UpdateStoreRequest request) {
        return ApiResult.of(storeService.updateStore(request));
    }

    // ==================================================================
    // 停业记录
    // ==================================================================

    /**
     * 分页查询停业记录。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数，上限 100（超出由分页插件截断）
     * @return 分页的停业记录
     */
    @GetMapping("/closures")
    public ResponseEntity<ApiResult<PageResult<ClosureVo>>> listClosures(
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = 100, message = "每页最多 100 条") long size) {
        return ApiResult.of(closureService.listClosures(page, size));
    }

    /**
     * 新增停业记录。
     *
     * <p>停业区间内系统会自动拒绝新下单，无需人工盯守。
     * 已在店内的顾客不受影响。
     *
     * @param request 停业时段与原因
     * @param me      当前登录的管理员，登记为记录的操作人
     * @return 新建的停业记录
     */
    @PostMapping("/closures")
    public ResponseEntity<ApiResult<ClosureVo>> createClosure(
            @Valid @RequestBody ClosureRequest request,
            @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(closureService.createClosure(request, me.id()));
    }

    /**
     * 修改停业记录。
     *
     * @param id      记录 ID
     * @param request 新的停业时段与原因
     * @return 成功时 data 为 null
     */
    @PutMapping("/closures/{id}")
    public ResponseEntity<ApiResult<Void>> updateClosure(
            @PathVariable Long id,
            @Valid @RequestBody ClosureRequest request) {
        return ApiResult.of(closureService.updateClosure(id, request));
    }

    /**
     * 删除停业记录（逻辑删除）。
     *
     * @param id 记录 ID
     * @return 成功时 data 为 null
     */
    @DeleteMapping("/closures/{id}")
    public ResponseEntity<ApiResult<Void>> deleteClosure(@PathVariable Long id) {
        return ApiResult.of(closureService.deleteClosure(id));
    }

    // ==================================================================
    // 免费时段（活动）
    // ==================================================================

    /**
     * 分页查询免费活动。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数，上限 100（超出由分页插件截断）
     * @return 分页的活动列表
     */
    @GetMapping("/free-periods")
    public ResponseEntity<ApiResult<PageResult<FreePeriodVo>>> listFreePeriods(
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = 100, message = "每页最多 100 条") long size) {
        Long storeId = storeService.getCurrentStoreId();
        if (storeId == null) {
            return ApiResult.of(BizResult.fail(ErrorCode.STORE_NOT_FOUND));
        }
        return ApiResult.of(freePeriodService.listPeriods(storeId, page, size));
    }

    /**
     * 新增免费活动。
     *
     * <p>活动区间内所有订单实收为 0 —— 店里照常营业、人照进、门照开，
     * 只是账单不计费（与停业的区别见 {@code FreePeriod} 的类注释）。
     *
     * <p>与已有活动重叠会被拒绝（{@code FREE_PERIOD_OVERLAP}）：
     * 两场活动都免费、重叠本身不致命，但会让后台出现含义重复的记录，
     * 且计费侧要多切一刀 —— 多切一段就多享一次宽限，账单会悄悄少收钱。
     *
     * @param request 活动时段与名称
     * @param me      当前登录的管理员，登记为记录的操作人
     * @return 新建的活动
     */
    @PostMapping("/free-periods")
    public ResponseEntity<ApiResult<FreePeriodVo>> createFreePeriod(
            @Valid @RequestBody FreePeriodRequest request,
            @AuthenticationPrincipal UserPrincipal me) {
        Long storeId = storeService.getCurrentStoreId();
        if (storeId == null) {
            return ApiResult.of(BizResult.fail(ErrorCode.STORE_NOT_FOUND));
        }
        return ApiResult.of(freePeriodService.createPeriod(storeId, request, me.id()));
    }

    /**
     * 修改免费活动。
     *
     * @param id      活动 ID
     * @param request 新的时段与名称
     * @return 更新后的活动
     */
    @PutMapping("/free-periods/{id}")
    public ResponseEntity<ApiResult<FreePeriodVo>> updateFreePeriod(
            @PathVariable Long id,
            @Valid @RequestBody FreePeriodRequest request) {
        Long storeId = storeService.getCurrentStoreId();
        if (storeId == null) {
            return ApiResult.of(BizResult.fail(ErrorCode.STORE_NOT_FOUND));
        }
        return ApiResult.of(freePeriodService.updatePeriod(storeId, id, request));
    }

    /**
     * 删除免费活动（逻辑删除）。
     *
     * <p>删除之后立刻不再免单 —— 与停业同理，这是运营想要的效果
     *（「活动取消了」）。已发生的订单不受影响：金额在结算时就已落库。
     *
     * @param id 活动 ID
     * @return 成功时 data 为 null
     */
    @DeleteMapping("/free-periods/{id}")
    public ResponseEntity<ApiResult<Void>> deleteFreePeriod(@PathVariable Long id) {
        Long storeId = storeService.getCurrentStoreId();
        if (storeId == null) {
            return ApiResult.of(BizResult.fail(ErrorCode.STORE_NOT_FOUND));
        }
        return ApiResult.of(freePeriodService.deletePeriod(storeId, id));
    }
}
