package com.kaede.uspace.space;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.space.dto.ClosureRequest;
import com.kaede.uspace.space.dto.ClosureVo;
import com.kaede.uspace.space.entity.Closure;
import com.kaede.uspace.space.entity.Store;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClosureService} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring、不连数据库</b> —— 数据访问由两个假 Mapper
 * 在内存里顶替，跑得快，也不必为了测一条规则先去准备数据库。
 *
 * <p>覆盖重点有两个：
 * <ol>
 *   <li><b>时段口径</b> —— 半开区间 {@code [start, end)} 的边界行为。
 *       恰好卡在停业开始那一刻应该被拦，卡在结束那一刻应该放行。
 *       这种差一秒的规则写错了不会报错，只会让某个倒霉顾客在
 *       恢复营业的那一秒下不了单，所以必须用例钉住</li>
 *   <li><b>重叠校验</b> —— 相邻时段要能排进去，真重叠的才拒绝；
 *       修改时要排除自己，否则「把时段往后挪半小时」会被自己挡住</li>
 * </ol>
 */
class ClosureServiceTests {

    /** 测试门店的 ID，与 {@link #setUp()} 里预置的一致 */
    private static final Long STORE_ID = 1L;

    /** 测试管理员 ID */
    private static final Long ADMIN_ID = 99L;

    /** 内存版数据访问层 */
    private final FakeStoreMapper storeMapper = new FakeStoreMapper();
    private final FakeClosureMapper closureMapper = new FakeClosureMapper();

    /** 被测服务 */
    private final ClosureService closureService =
            new ClosureService(closureMapper.asMapper(), storeMapper.asMapper());

    /**
     * 每个用例前预置一条门店 —— 停业记录必须挂在门店下，
     * 没有门店时 Service 会提前返回「门店不存在」，测不到后面的规则。
     */
    @BeforeEach
    void setUp() {
        Store store = new Store();
        store.setId(STORE_ID);
        store.setName("测试门店");
        storeMapper.seed(store);
    }

    // ==================================================================
    // 新增
    // ==================================================================

    @Test
    @DisplayName("新增停业：写入时段、原因与登记人")
    void createClosure_persistsAllFields() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 1, 10, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 1, 14, 0);

        BizResult<ClosureVo> result = closureService.createClosure(
                newRequest(start, end, "设备维护"), ADMIN_ID);

        assertTrue(result.isSuccess(), "正常时段应当创建成功");

        Closure stored = closureMapper.get(result.getData().getId());
        assertEquals(start, stored.getStartAt());
        assertEquals(end, stored.getEndAt());
        assertEquals("设备维护", stored.getReason());
        assertEquals(ADMIN_ID, stored.getCreatedBy(), "要记下是谁登记的，事后复盘时查得到");
        assertEquals(STORE_ID, stored.getStoreId());
    }

    @Test
    @DisplayName("新增停业：结束时刻不晚于开始时刻时拒绝")
    void createClosure_rejectsReversedRange() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 1, 10, 0);

        BizResult<ClosureVo> same = closureService.createClosure(newRequest(t, t, null), ADMIN_ID);
        assertEquals(ErrorCode.CLOSURE_TIME_INVALID, same.getError(),
                "首尾相同时段长度为零，没有意义，应当拒绝");

        BizResult<ClosureVo> reversed = closureService.createClosure(
                newRequest(t, t.minusHours(1), null), ADMIN_ID);
        assertEquals(ErrorCode.CLOSURE_TIME_INVALID, reversed.getError());
    }

    @Test
    @DisplayName("新增停业：与既有记录重叠时拒绝")
    void createClosure_rejectsOverlappingRange() {
        closureService.createClosure(newRequest(
                LocalDateTime.of(2026, 10, 1, 10, 0),
                LocalDateTime.of(2026, 10, 1, 14, 0), "维护"), ADMIN_ID);

        // 与既有记录部分相交
        BizResult<ClosureVo> result = closureService.createClosure(newRequest(
                LocalDateTime.of(2026, 10, 1, 13, 0),
                LocalDateTime.of(2026, 10, 1, 16, 0), "另一件事"), ADMIN_ID);

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.CLOSURE_OVERLAP, result.getError());
    }

    @Test
    @DisplayName("新增停业：首尾相接的两段可以相邻，不算重叠")
    void createClosure_allowsAdjacentRanges() {
        closureService.createClosure(newRequest(
                LocalDateTime.of(2026, 10, 1, 10, 0),
                LocalDateTime.of(2026, 10, 1, 12, 0), "上午维护"), ADMIN_ID);

        // 半开区间 [10:00, 12:00) 与 [12:00, 14:00)：12:00 那一刻只属于后者
        BizResult<ClosureVo> result = closureService.createClosure(newRequest(
                LocalDateTime.of(2026, 10, 1, 12, 0),
                LocalDateTime.of(2026, 10, 1, 14, 0), "下午维护"), ADMIN_ID);

        assertTrue(result.isSuccess(),
                "闭区间下 12:00 会被两段同时覆盖而判为重叠，运营排班时得为避让一秒去挪时间，很别扭");
    }

    @Test
    @DisplayName("新增停业：门店不存在时返回门店错误码，而不是静默写入脏数据")
    void createClosure_failsWhenStoreMissing() {
        FakeStoreMapper empty = new FakeStoreMapper();
        ClosureService service = new ClosureService(closureMapper.asMapper(), empty.asMapper());

        BizResult<ClosureVo> result = service.createClosure(newRequest(
                LocalDateTime.of(2026, 10, 1, 10, 0),
                LocalDateTime.of(2026, 10, 1, 14, 0), null), ADMIN_ID);

        assertEquals(ErrorCode.STORE_NOT_FOUND, result.getError());
    }

    // ==================================================================
    // 修改
    // ==================================================================

    @Test
    @DisplayName("修改停业：校验重叠时排除自己，往后挪半小时不会被自己挡住")
    void updateClosure_excludesSelfWhenCheckingOverlap() {
        Long id = closureService.createClosure(newRequest(
                LocalDateTime.of(2026, 10, 1, 10, 0),
                LocalDateTime.of(2026, 10, 1, 14, 0), "维护"), ADMIN_ID).getData().getId();

        // 新时段与旧时段大面积重合 —— 若没排除自己，这里必然误报重叠
        BizResult<Void> result = closureService.updateClosure(id, newRequest(
                LocalDateTime.of(2026, 10, 1, 10, 30),
                LocalDateTime.of(2026, 10, 1, 14, 30), "维护延长"));

        assertTrue(result.isSuccess(), "改期与自身必然重叠，不排除自己的话这个功能根本用不了");

        Closure stored = closureMapper.get(id);
        assertEquals(LocalDateTime.of(2026, 10, 1, 10, 30), stored.getStartAt());
        assertEquals(LocalDateTime.of(2026, 10, 1, 14, 30), stored.getEndAt());
        assertEquals("维护延长", stored.getReason());
    }

    @Test
    @DisplayName("修改停业：记录不存在时返回 404 类错误码")
    void updateClosure_failsWhenNotFound() {
        BizResult<Void> result = closureService.updateClosure(999L, newRequest(
                LocalDateTime.of(2026, 10, 1, 10, 0),
                LocalDateTime.of(2026, 10, 1, 14, 0), null));

        assertEquals(ErrorCode.CLOSURE_NOT_FOUND, result.getError());
    }

    // ==================================================================
    // 删除
    // ==================================================================

    @Test
    @DisplayName("删除停业：走逻辑删除，删完不再拦下单")
    void deleteClosure_marksDeletedInsteadOfRemoving() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 1, 10, 0);
        Long id = closureService.createClosure(
                newRequest(start, start.plusHours(4), "维护"), ADMIN_ID).getData().getId();

        assertTrue(closureService.isClosedAt(start.plusHours(1)), "删之前应当拦");

        BizResult<Void> result = closureService.deleteClosure(id);

        assertTrue(result.isSuccess());
        assertEquals(1, closureMapper.get(id).getDeleted(),
                "应当是逻辑删除（deleted=1）而非物理删除 —— 事后复盘「那天为什么关门」还查得到");
        assertFalse(closureService.isClosedAt(start.plusHours(1)), "删之后不该再拦");
    }

    @Test
    @DisplayName("删除停业：记录不存在时返回 404 类错误码")
    void deleteClosure_failsWhenNotFound() {
        assertEquals(ErrorCode.CLOSURE_NOT_FOUND, closureService.deleteClosure(999L).getError());
    }

    // ==================================================================
    // 停业判断
    // ==================================================================

    @Test
    @DisplayName("停业判断：半开区间 —— 起点算停业，终点已恢复")
    void isClosedAt_followsHalfOpenRange() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 1, 10, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 1, 14, 0);
        closureService.createClosure(newRequest(start, end, "维护"), ADMIN_ID);

        assertFalse(closureService.isClosedAt(start.minusSeconds(1)), "开始前一秒还没停业");
        assertTrue(closureService.isClosedAt(start), "开始那一刻起不对外营业");
        assertTrue(closureService.isClosedAt(end.minusSeconds(1)), "结束前一秒仍在停业");
        assertFalse(closureService.isClosedAt(end), "结束那一刻已经恢复营业");
    }

    @Test
    @DisplayName("停业判断：没有停业记录时一律放行")
    void isClosedAt_returnsFalseWhenNoClosure() {
        assertFalse(closureService.isClosedAt(LocalDateTime.of(2026, 10, 1, 10, 0)));
        assertNull(closureService.findCoveringClosure(LocalDateTime.of(2026, 10, 1, 10, 0)));
    }

    @Test
    @DisplayName("停业判断：门店不存在时返回「不停业」，不伪装成停业")
    void isClosedAt_returnsFalseWhenStoreMissing() {
        FakeStoreMapper empty = new FakeStoreMapper();
        ClosureService service = new ClosureService(closureMapper.asMapper(), empty.asMapper());

        assertFalse(service.isClosedAt(LocalDateTime.now()),
                "门店不存在属于系统未初始化，应当让下单链路自己去报「门店不存在」，"
                        + "而不是在这里伪装成停业把排障引向错误方向");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 构造一个停业请求。
     *
     * @param startAt 开始时刻
     * @param endAt   结束时刻
     * @param reason  原因，可为 null
     * @return 请求对象
     */
    private static ClosureRequest newRequest(LocalDateTime startAt, LocalDateTime endAt, String reason) {
        ClosureRequest request = new ClosureRequest();
        request.setStartAt(startAt);
        request.setEndAt(endAt);
        request.setReason(reason);
        return request;
    }
}
