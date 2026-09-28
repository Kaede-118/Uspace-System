package com.kaede.uspace.space;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.space.dto.StoreStatusVo;
import com.kaede.uspace.space.dto.StoreVo;
import com.kaede.uspace.space.dto.UpdateStoreRequest;
import com.kaede.uspace.space.entity.Booking;
import com.kaede.uspace.space.entity.Closure;
import com.kaede.uspace.space.entity.Store;
import com.kaede.uspace.user.FakeSysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StoreService} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring、不连数据库。</b>
 *
 * <p>覆盖重点在<b>营业状态的推导</b> —— 那是本模块真正被其他模块用到的地方
 * （下单链路靠它决定放不放行）。三条规则要钉住：
 * <ol>
 *   <li>停业区间内是 {@code CLOSED}，且给出恢复营业的时刻</li>
 *   <li>已付款包场区间内是 {@code BOOKED}；<b>未付款的不算</b></li>
 *   <li>两者都不命中才是 {@code OPEN}</li>
 * </ol>
 *
 * <p>优先级也单独有一条用例：排期时已校验停业与包场不重叠，
 * 真撞上了（比如先排包场再临时加停业）必须按更严格的算 ——
 * 大门锁着却告诉顾客「本时段已被包场，请凭邀请入场」，那就闹笑话了。
 */
class StoreServiceTests {

    /** 测试门店的 ID */
    private static final Long STORE_ID = 1L;

    /** 内存版数据访问层 */
    private final FakeStoreMapper storeMapper = new FakeStoreMapper();
    private final FakeClosureMapper closureMapper = new FakeClosureMapper();
    private final FakeBookingMapper bookingMapper = new FakeBookingMapper();

    private final ClosureService closureService =
            new ClosureService(closureMapper.asMapper(), storeMapper.asMapper());

    private final BookingService bookingService = new BookingService(
            bookingMapper.asMapper(), storeMapper.asMapper(), closureService,
            new FakeSysUserMapper().asMapper());

    /** 被测服务 */
    private final StoreService storeService =
            new StoreService(storeMapper.asMapper(), closureService, bookingService);

    /** 每个用例前预置一条门店 */
    @BeforeEach
    void setUp() {
        Store store = new Store();
        store.setId(STORE_ID);
        store.setName("测试门店");
        store.setAddress("某某路 1 号");
        store.setDescription("6 台音游机");
        storeMapper.seed(store);
    }

    // ==================================================================
    // 营业状态
    // ==================================================================

    @Test
    @DisplayName("营业状态：既没停业也没包场时是营业中")
    void getStoreStatus_open() {
        BizResult<StoreStatusVo> result = storeService.getStoreStatus();

        assertTrue(result.isSuccess());
        StoreStatusVo status = result.getData();
        assertEquals(StoreStatusVo.STATUS_OPEN, status.getStatus());
        assertNull(status.getStatusEndAt(), "营业中没有「结束时刻」可言");
        assertNotNull(status.getStore());
        assertEquals("测试门店", status.getStore().getName(),
                "状态里要一并带上门店信息 —— 用户端首页两样都要，分两次查还可能落在不同时间点上");
    }

    @Test
    @DisplayName("营业状态：落在停业区间内是停业中，并给出恢复时刻")
    void getStoreStatus_closedWithinClosure() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime end = now.plusHours(2);
        closureMapper.seed(closure(now.minusHours(1), end, "设备维护"));

        StoreStatusVo status = storeService.getStoreStatus().getData();

        assertEquals(StoreStatusVo.STATUS_CLOSED, status.getStatus());
        assertEquals(end, status.getStatusEndAt(), "要告诉用户几点恢复营业");
    }

    @Test
    @DisplayName("营业状态：落在已付款包场区间内是包场中")
    void getStoreStatus_bookedWithinPaidBooking() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime end = now.plusHours(3);
        bookingMapper.seed(booking(now.minusHours(1), end, BookingStatus.PAID));

        StoreStatusVo status = storeService.getStoreStatus().getData();

        assertEquals(StoreStatusVo.STATUS_BOOKED, status.getStatus());
        assertEquals(end, status.getStatusEndAt());
    }

    @Test
    @DisplayName("营业状态：未付款的包场不影响对外状态，散客照常能进")
    void getStoreStatus_ignoresUnpaidBooking() {
        LocalDateTime now = LocalDateTime.now();
        bookingMapper.seed(booking(now.minusHours(1), now.plusHours(3), BookingStatus.PENDING_PAYMENT));

        StoreStatusVo status = storeService.getStoreStatus().getData();

        assertEquals(StoreStatusVo.STATUS_OPEN, status.getStatus(),
                "排了期但没付款的包场不该把散客挡在门外 —— 否则店会白空一个时段");
    }

    @Test
    @DisplayName("营业状态：停业与包场同时命中时按停业算")
    void getStoreStatus_closureTakesPrecedenceOverBooking() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime closureEnd = now.plusHours(1);
        closureMapper.seed(closure(now.minusHours(1), closureEnd, "设备维护"));
        bookingMapper.seed(booking(now.minusHours(2), now.plusHours(5), BookingStatus.PAID));

        StoreStatusVo status = storeService.getStoreStatus().getData();

        assertEquals(StoreStatusVo.STATUS_CLOSED, status.getStatus(),
                "大门锁着却告诉顾客「本时段已被包场，请凭邀请入场」，那就闹笑话了");
        assertEquals(closureEnd, status.getStatusEndAt());
    }

    @Test
    @DisplayName("营业状态：门店不存在时返回 404 类错误码")
    void getStoreStatus_failsWhenStoreMissing() {
        FakeStoreMapper empty = new FakeStoreMapper();
        StoreService service = new StoreService(empty.asMapper(), closureService, bookingService);

        assertEquals(ErrorCode.STORE_NOT_FOUND, service.getStoreStatus().getError());
    }

    // ==================================================================
    // 门店信息
    // ==================================================================

    @Test
    @DisplayName("查询门店：返回名称、地址与说明")
    void getCurrentStore_returnsStoreInfo() {
        StoreVo vo = storeService.getCurrentStore().getData();

        assertEquals("测试门店", vo.getName());
        assertEquals("某某路 1 号", vo.getAddress());
        assertEquals("6 台音游机", vo.getDescription());
    }

    @Test
    @DisplayName("修改门店：名称、地址与说明都会被写入")
    void updateStore_persistsChanges() {
        UpdateStoreRequest request = new UpdateStoreRequest();
        request.setName("新店名");
        request.setAddress("新地址 2 号");
        request.setDescription("10 台机");

        BizResult<StoreVo> result = storeService.updateStore(request);

        assertTrue(result.isSuccess());
        Store stored = storeMapper.get(STORE_ID);
        assertEquals("新店名", stored.getName());
        assertEquals("新地址 2 号", stored.getAddress());
        assertEquals("10 台机", stored.getDescription());
    }

    @Test
    @DisplayName("修改门店：与别家重名时拒绝")
    void updateStore_rejectsDuplicateName() {
        Store other = new Store();
        other.setName("已存在的店名");
        storeMapper.seed(other);

        UpdateStoreRequest request = new UpdateStoreRequest();
        request.setName("已存在的店名");

        assertEquals(ErrorCode.STORE_NAME_EXISTS, storeService.updateStore(request).getError());
    }

    @Test
    @DisplayName("修改门店：名称改回自己原来的不算重名")
    void updateStore_allowsKeepingOwnName() {
        UpdateStoreRequest request = new UpdateStoreRequest();
        request.setName("测试门店");
        request.setAddress("只改地址");

        assertTrue(storeService.updateStore(request).isSuccess(),
                "查重时必须排除自己，否则「只改地址不改名」这个最常见的操作会被拒");
        assertEquals("只改地址", storeMapper.get(STORE_ID).getAddress());
    }

    @Test
    @DisplayName("修改门店：地址传空串会被归一为 null，而不是存成空串")
    void updateStore_normalizesBlankToNull() {
        UpdateStoreRequest request = new UpdateStoreRequest();
        request.setName("测试门店");
        request.setAddress("   ");

        storeService.updateStore(request);

        assertNull(storeMapper.get(STORE_ID).getAddress(),
                "空串与 NULL 混用会让「查没有地址的店」要写两个条件，统一成 null");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 构造一条停业记录实体（绕过 Service 的校验，用于预置数据）。
     *
     * @param startAt 开始时刻
     * @param endAt   结束时刻
     * @param reason  原因
     * @return 停业实体
     */
    private static Closure closure(LocalDateTime startAt, LocalDateTime endAt, String reason) {
        Closure closure = new Closure();
        closure.setStoreId(STORE_ID);
        closure.setStartAt(startAt);
        closure.setEndAt(endAt);
        closure.setReason(reason);
        return closure;
    }

    /**
     * 构造一条包场实体（绕过 Service 的校验，用于预置特定状态）。
     *
     * @param startAt 开始时刻
     * @param endAt   结束时刻
     * @param status  状态
     * @return 包场实体
     */
    private static Booking booking(LocalDateTime startAt, LocalDateTime endAt, BookingStatus status) {
        Booking booking = new Booking();
        booking.setBookingNo("BK-TEST-" + status.name());
        booking.setStoreId(STORE_ID);
        booking.setHostUserId(7L);
        booking.setStartAt(startAt);
        booking.setEndAt(endAt);
        booking.setPrice(BigDecimal.TEN);
        booking.setStatus(status.name());
        return booking;
    }
}
