package com.kaede.uspace.billing;

import com.kaede.uspace.billing.dto.FreePeriodRequest;
import com.kaede.uspace.billing.dto.FreePeriodVo;
import com.kaede.uspace.billing.entity.FreePeriod;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.result.PageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FreePeriodService} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring、不连数据库</b> —— 数据访问由
 * {@link FakeFreePeriodMapper} 在内存里顶替，跑得快，也不必为了测一条规则
 * 先去准备数据库。
 *
 * <p>覆盖重点有三个：
 * <ol>
 *   <li><b>半开区间 {@code [start, end)} 的边界行为</b> —— 首尾相接的两场活动
 *       （18:00–20:00 与 20:00–22:00）<b>不算重叠</b>，要能背靠背排上去；
 *       而 start 与 end 相同是个空区间，应当拒绝。这种差一秒的规则写错了
 *       不会报错，只会让管理员某次排期莫名其妙被挡</li>
 *   <li><b>重叠校验要排除自己</b> —— 否则「只改个活动名字」会被自己挡住。
 *       与停业那边同源</li>
 *   <li><b>坏数据不能让计费崩掉</b> —— 库里若有一条起止颠倒的记录，
 *       {@code findRangesFor} 要跳过它并记 warn，而不是把异常抛给
 *       调用它的结算链路（那会让所有订单都结算不了）</li>
 * </ol>
 */
class FreePeriodServiceTests {

    /** 测试门店的 ID */
    private static final Long STORE_ID = 1L;

    /** 另一家门店的 ID，用于验证跨门店的活动互不干扰 */
    private static final Long OTHER_STORE_ID = 2L;

    /** 测试管理员 ID */
    private static final Long ADMIN_ID = 99L;

    /** 测试基准日期（月中，避开月初月末的干扰） */
    private static final LocalDate DATE = LocalDate.of(2026, 9, 16);

    /** 内存版数据访问层 */
    private final FakeFreePeriodMapper periodMapper = new FakeFreePeriodMapper();

    /** 被测服务 */
    private final FreePeriodService freePeriodService =
            new FreePeriodService(periodMapper.asMapper());

    /**
     * 构造一个固定日期的时间点。
     *
     * @param hour   小时
     * @param minute 分钟
     * @return {@link #DATE} 当天的该时刻
     */
    private static LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(DATE, LocalTime.of(hour, minute));
    }

    /**
     * 构造一个新增 / 修改请求。
     *
     * @param start  开始时刻
     * @param end    结束时刻
     * @param reason 活动名称，可为 null
     * @return 请求体
     */
    private static FreePeriodRequest request(LocalDateTime start, LocalDateTime end, String reason) {
        FreePeriodRequest request = new FreePeriodRequest();
        request.setStartAt(start);
        request.setEndAt(end);
        request.setReason(reason);
        return request;
    }

    /**
     * 预置一条本门店的活动。
     *
     * @param start  开始时刻
     * @param end    结束时刻
     * @param reason 活动名称
     * @return 落库后的实体
     */
    private FreePeriod seed(LocalDateTime start, LocalDateTime end, String reason) {
        FreePeriod period = new FreePeriod();
        period.setStoreId(STORE_ID);
        period.setStartAt(start);
        period.setEndAt(end);
        period.setReason(reason);
        return periodMapper.seed(period);
    }

    // ==================================================================
    // 新增
    // ==================================================================

    @Test
    @DisplayName("新增活动：写入时段、名称、登记人与门店")
    void createPeriod_persistsAllFields() {
        LocalDateTime start = at(20, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 17, 2, 0);

        BizResult<FreePeriodVo> result = freePeriodService.createPeriod(
                STORE_ID, request(start, end, "跨年活动"), ADMIN_ID);

        assertTrue(result.isSuccess(), "正常时段应当创建成功");

        FreePeriod stored = periodMapper.get(result.getData().getId());
        assertEquals(start, stored.getStartAt());
        assertEquals(end, stored.getEndAt(), "跨零点的区间要原样存下来");
        assertEquals("跨年活动", stored.getReason());
        assertEquals(ADMIN_ID, stored.getCreatedBy(), "要记下是谁登记的，事后复盘时查得到");
        assertEquals(STORE_ID, stored.getStoreId());
    }

    @Test
    @DisplayName("新增活动：结束早于开始时拒绝")
    void createPeriod_rejectsReversedRange() {
        BizResult<FreePeriodVo> result = freePeriodService.createPeriod(
                STORE_ID, request(at(14, 0), at(12, 0), null), ADMIN_ID);

        assertEquals(ErrorCode.FREE_PERIOD_TIME_INVALID, result.getError());
        assertEquals(0, periodMapper.size(), "被拒绝的请求不该落库");
    }

    @Test
    @DisplayName("新增活动：起止相同时拒绝 —— 那是空区间，免不到任何人")
    void createPeriod_rejectsEmptyRange() {
        BizResult<FreePeriodVo> result = freePeriodService.createPeriod(
                STORE_ID, request(at(14, 0), at(14, 0), null), ADMIN_ID);

        assertEquals(ErrorCode.FREE_PERIOD_TIME_INVALID, result.getError(),
                "半开区间下 start == end 是空集，写进去只会在后台多一条看不出问题的记录");
    }

    @Test
    @DisplayName("新增活动：与已有活动重叠时拒绝")
    void createPeriod_rejectsOverlap() {
        seed(at(10, 0), at(12, 0), "上午活动");

        BizResult<FreePeriodVo> result = freePeriodService.createPeriod(
                STORE_ID, request(at(11, 0), at(13, 0), "重叠的活动"), ADMIN_ID);

        assertEquals(ErrorCode.FREE_PERIOD_OVERLAP, result.getError());
        assertEquals(1, periodMapper.size(), "只有预置的那一条");
    }

    @Test
    @DisplayName("新增活动：首尾相接不算重叠，可以背靠背排")
    void createPeriod_allowsAdjacentRanges() {
        seed(at(18, 0), at(20, 0), "第一场");

        BizResult<FreePeriodVo> result = freePeriodService.createPeriod(
                STORE_ID, request(at(20, 0), at(22, 0), "第二场"), ADMIN_ID);

        assertTrue(result.isSuccess(),
                "半开区间 [start, end) 下 20:00 已经不属于第一场了 —— "
                        + "判成重叠的话，运营连着办两场活动会被系统挡住");
    }

    @Test
    @DisplayName("新增活动：名称只有空白时存 null，不留空串")
    void createPeriod_blankReasonStoredAsNull() {
        BizResult<FreePeriodVo> result = freePeriodService.createPeriod(
                STORE_ID, request(at(10, 0), at(12, 0), "   "), ADMIN_ID);

        FreePeriod stored = periodMapper.get(result.getData().getId());
        assertNull(stored.getReason(),
                "空串与 null 在库里的含义一样，存两种形态只会让「有没有填名称」要看两个条件");
    }

    @Test
    @DisplayName("新增活动：别家门店的活动不参与本店的重叠校验")
    void createPeriod_ignoresOtherStores() {
        FreePeriod other = new FreePeriod();
        other.setStoreId(OTHER_STORE_ID);
        other.setStartAt(at(10, 0));
        other.setEndAt(at(12, 0));
        periodMapper.seed(other);

        BizResult<FreePeriodVo> result = freePeriodService.createPeriod(
                STORE_ID, request(at(10, 0), at(12, 0), null), ADMIN_ID);

        assertTrue(result.isSuccess(),
                "重叠校验只在本门店内做 —— 跨门店判重叠会让开分店之后两家店的活动互相挡");
    }

    // ==================================================================
    // 修改
    // ==================================================================

    @Test
    @DisplayName("修改活动：时段与名称都更新")
    void updatePeriod_updatesFields() {
        FreePeriod seeded = seed(at(10, 0), at(12, 0), "原名");

        BizResult<FreePeriodVo> result = freePeriodService.updatePeriod(
                STORE_ID, seeded.getId(), request(at(14, 0), at(16, 0), "新名"));

        assertTrue(result.isSuccess());
        assertEquals(at(14, 0), result.getData().getStartAt());
        assertEquals("新名", result.getData().getReason());
        assertEquals(at(14, 0), periodMapper.get(seeded.getId()).getStartAt(), "改动要落到库里");
    }

    @Test
    @DisplayName("修改活动：与自己重叠不算冲突")
    void updatePeriod_excludesSelfFromOverlap() {
        FreePeriod seeded = seed(at(10, 0), at(12, 0), "原名");

        // 只改名字，时段原地不动 —— 校验若不排除自己，这里会被自己挡住
        BizResult<FreePeriodVo> result = freePeriodService.updatePeriod(
                STORE_ID, seeded.getId(), request(at(10, 0), at(12, 0), "新名"));

        assertTrue(result.isSuccess(), "「只改个名字」不该被自己挡下来");
        assertEquals("新名", result.getData().getReason());
    }

    @Test
    @DisplayName("修改活动：改到与别人重叠时拒绝")
    void updatePeriod_rejectsOverlapWithOther() {
        FreePeriod first = seed(at(10, 0), at(12, 0), "第一场");
        seed(at(14, 0), at(16, 0), "第二场");

        BizResult<FreePeriodVo> result = freePeriodService.updatePeriod(
                STORE_ID, first.getId(), request(at(15, 0), at(17, 0), "第一场"));

        assertEquals(ErrorCode.FREE_PERIOD_OVERLAP, result.getError());
        assertEquals(at(10, 0), periodMapper.get(first.getId()).getStartAt(),
                "被拒绝的修改不该改动库里那条记录");
    }

    @Test
    @DisplayName("修改活动：名称传 null 能真的清空")
    void updatePeriod_clearsReason() {
        FreePeriod seeded = seed(at(10, 0), at(12, 0), "要撤掉的名称");

        BizResult<FreePeriodVo> result = freePeriodService.updatePeriod(
                STORE_ID, seeded.getId(), request(at(10, 0), at(12, 0), null));

        assertTrue(result.isSuccess());
        assertNull(periodMapper.get(seeded.getId()).getReason(),
                "走 updateById 的话这一格会被跳过 —— 接口 200、刷新后旧名称还在，且不报任何错。"
                        + "本条钉住 FreePeriodMapper#updatePeriod 那条显式 SQL");
    }

    @Test
    @DisplayName("修改活动：记录不存在时返回未找到")
    void updatePeriod_rejectsMissing() {
        BizResult<FreePeriodVo> result = freePeriodService.updatePeriod(
                STORE_ID, 999L, request(at(10, 0), at(12, 0), null));

        assertEquals(ErrorCode.FREE_PERIOD_NOT_FOUND, result.getError());
    }

    @Test
    @DisplayName("修改活动：别家门店的记录当作不存在")
    void updatePeriod_rejectsOtherStore() {
        FreePeriod other = new FreePeriod();
        other.setStoreId(OTHER_STORE_ID);
        other.setStartAt(at(10, 0));
        other.setEndAt(at(12, 0));
        periodMapper.seed(other);

        BizResult<FreePeriodVo> result = freePeriodService.updatePeriod(
                STORE_ID, other.getId(), request(at(14, 0), at(16, 0), null));

        assertEquals(ErrorCode.FREE_PERIOD_NOT_FOUND, result.getError(),
                "单门店下这条判断不会触发，但多门店时它是「A 店改了 B 店的活动」的唯一防线 —— "
                        + "而那种错从界面上完全看不出来");
    }

    // ==================================================================
    // 删除
    // ==================================================================

    @Test
    @DisplayName("删除活动：删完立刻不再免单")
    void deletePeriod_stopsFreeingImmediately() {
        FreePeriod seeded = seed(at(10, 0), at(12, 0), "办完的活动");

        // 删之前：区间内的查询能查到它
        assertEquals(1, freePeriodService.findRangesFor(STORE_ID, at(10, 0), at(11, 0)).size());

        BizResult<Void> result = freePeriodService.deletePeriod(STORE_ID, seeded.getId());

        assertTrue(result.isSuccess());
        assertTrue(freePeriodService.findRangesFor(STORE_ID, at(10, 0), at(11, 0)).isEmpty(),
                "删除之后账要照收 —— 与停业同理，这是运营想要的效果（「活动取消了」）");
        assertTrue(freePeriodService.listUpcoming(STORE_ID, at(0, 0), null).isEmpty());
    }

    @Test
    @DisplayName("删除活动：记录不存在时返回未找到")
    void deletePeriod_rejectsMissing() {
        BizResult<Void> result = freePeriodService.deletePeriod(STORE_ID, 999L);

        assertEquals(ErrorCode.FREE_PERIOD_NOT_FOUND, result.getError());
    }

    // ==================================================================
    // 查询
    // ==================================================================

    @Test
    @DisplayName("近期活动：正在进行的也在，已结束的不在")
    void listUpcoming_includesOngoingExcludesFinished() {
        seed(at(9, 0), at(11, 0), "已结束");                 // 9:00–11:00
        seed(at(10, 0), at(23, 59), "进行中");               // 10:00 起，现在（12:00）还没结束
        FreePeriod tomorrow = new FreePeriod();
        tomorrow.setStoreId(STORE_ID);
        tomorrow.setStartAt(DATE.plusDays(1).atTime(14, 0));
        tomorrow.setEndAt(DATE.plusDays(1).atTime(18, 0));
        tomorrow.setReason("明天");
        periodMapper.seed(tomorrow);

        List<FreePeriodVo> upcoming = freePeriodService.listUpcoming(STORE_ID, at(12, 0), null);

        assertEquals(2, upcoming.size(), "正在进行的必须显示出来 —— 那正是最该被看见的一条");
        assertEquals("进行中", upcoming.get(0).getReason());
        assertEquals("明天", upcoming.get(1).getReason(), "按开始时间升序");
    }

    @Test
    @DisplayName("近期活动：条数越界时截到边界值，不报错")
    void listUpcoming_clampsLimit() {
        for (int i = 0; i < 25; i++) {
            seed(at(10, 0).plusDays(i), at(11, 0).plusDays(i), "第 " + (i + 1) + " 场");
        }

        assertEquals(20, freePeriodService.listUpcoming(STORE_ID, at(0, 0), 100).size(),
                "超出上限截到 20 —— 前端传错一个数不该让整个首页 500");
        assertEquals(3, freePeriodService.listUpcoming(STORE_ID, at(0, 0), 0).size(),
                "0 或负数取默认值 3");
        assertEquals(3, freePeriodService.listUpcoming(STORE_ID, at(0, 0), null).size(),
                "不传也是默认值");
        assertEquals(5, freePeriodService.listUpcoming(STORE_ID, at(0, 0), 5).size(),
                "正常范围内的值原样生效");
    }

    @Test
    @DisplayName("计费区间查询：只返回有交集的活动，且返回活动原区间")
    void findRangesFor_returnsOnlyOverlapping() {
        seed(at(8, 0), at(9, 0), "活动前");          // 与订单无交集
        seed(at(10, 0), at(12, 0), "活动中");        // 包含订单起点
        seed(at(15, 0), at(17, 0), "活动后");        // 与订单无交集

        List<FreeRange> ranges = freePeriodService.findRangesFor(STORE_ID, at(11, 0), at(14, 0));

        assertEquals(1, ranges.size(), "只有中间那场与订单区间有交集");
        assertEquals(at(10, 0), ranges.get(0).from(),
                "返回的是活动本身的区间，不裁剪到订单区间 —— 裁剪是计费侧切段逻辑的事");
        assertEquals(at(12, 0), ranges.get(0).to());
    }

    @Test
    @DisplayName("计费区间查询：起止颠倒的坏数据被跳过，不抛异常")
    void findRangesFor_skipsMalformedRecords() {
        FreePeriod bad = new FreePeriod();
        bad.setStoreId(STORE_ID);
        bad.setStartAt(at(12, 0));
        bad.setEndAt(at(11, 0));
        periodMapper.seed(bad);

        List<FreeRange> ranges = freePeriodService.findRangesFor(STORE_ID, at(10, 0), at(13, 0));

        assertTrue(ranges.isEmpty(),
                "抛出去的话所有订单都结算不了 —— 代价远大于「少免一次活动」。"
                        + "跳过至少账单能算出来，warn 日志也把问题留下了痕迹");
    }

    @Test
    @DisplayName("计费区间查询：区间为空或倒置时直接返回空列表")
    void findRangesFor_handlesBadArguments() {
        seed(at(10, 0), at(12, 0), "活动中");

        assertTrue(freePeriodService.findRangesFor(STORE_ID, at(12, 0), at(10, 0)).isEmpty(),
                "调用方给了一个倒置的区间");
        assertTrue(freePeriodService.findRangesFor(STORE_ID, at(10, 0), at(10, 0)).isEmpty(),
                "零长区间");
        assertTrue(freePeriodService.findRangesFor(null, at(10, 0), at(12, 0)).isEmpty());
    }

    @Test
    @DisplayName("后台列表：按开始时间倒序分页")
    void listPeriods_paginatesNewestFirst() {
        seed(at(10, 0), at(11, 0), "早的");
        seed(at(14, 0), at(15, 0), "晚的");
        seed(at(18, 0), at(19, 0), "更晚的");

        BizResult<PageResult<FreePeriodVo>> result = freePeriodService.listPeriods(STORE_ID, 1, 2);

        assertTrue(result.isSuccess());
        PageResult<FreePeriodVo> page = result.getData();
        assertEquals(3, page.getTotal());
        assertEquals(2, page.getRecords().size(), "第一页 2 条");
        assertEquals("更晚的", page.getRecords().get(0).getReason(),
                "按开始时间倒序 —— 运营多半想知道「接下来哪天有活动」，最近的在最前面最顺手");
        assertNotNull(page.getRecords().get(0).getStartAt());
    }
}
