package com.kaede.uspace.billing.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kaede.uspace.billing.entity.FreePeriod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FreePeriodMapper} 的集成测试，连本机真实 MySQL。
 *
 * <p><b>三重保护不污染开发库</b>：{@code @Transactional} 让每个用例结束后自动回滚；
 * {@code @EnabledIfEnvironmentVariable} 让没配 {@code MYSQL_PASSWORD} 的机器整个跳过；
 * 门店 ID 取 9999xx 这类可辨认的大数，万一回滚失效也一眼能认出来。
 *
 * <p><b>为什么假 Mapper 不够</b>：本 Mapper 的五条查询全是手写 SQL，
 * 而手写 SQL 有一处<b>漏了不会有任何报错</b>的坑 ——
 * <b>{@code deleted = 0} 必须自己带</b>（全局逻辑删除配置只作用于
 * MyBatis-Plus 自己生成的方法）。漏掉的后果是「删掉的活动还在免单」，
 * 从界面上完全看不出来，只会在对账时发现怎么少收了钱。
 * 另有三处也只有真跑一遍才知道：半开区间的比较符、
 * {@code updatePeriod} 清空名称、以及 {@code ORDER BY} 与 {@code LIMIT} 的实际效果。
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+",
        disabledReason = "未配置 MYSQL_PASSWORD，跳过需要真实数据库的集成测试")
class FreePeriodMapperIntegrationTests {

    /** 测试用的门店 ID。取大数以免与真实数据混淆 */
    private static final Long STORE_ID = 999911L;

    /** 另一家门店，用于验证查询只取本店的记录 */
    private static final Long OTHER_STORE_ID = 999912L;

    /**
     * 测试基准时刻，截到秒 —— 库列是秒精度，带纳秒的值写进去会被四舍五入，
     * 于是「本应相等」的两个时刻在断言里差 1 秒，而那种失败看起来像是 SQL 写错了
     */
    private static final LocalDateTime BASE = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

    @Autowired
    private FreePeriodMapper freePeriodMapper;

    /**
     * 构造一条本门店的活动。
     *
     * @param start 开始时刻
     * @param end   结束时刻
     * @return 实体（未落库）
     */
    private static FreePeriod period(LocalDateTime start, LocalDateTime end) {
        FreePeriod period = new FreePeriod();
        period.setStoreId(STORE_ID);
        period.setStartAt(start);
        period.setEndAt(end);
        return period;
    }

    /**
     * 基准时刻之后第 n 分钟。
     *
     * @param minutes 分钟数
     * @return 时刻
     */
    private static LocalDateTime fromBase(long minutes) {
        return BASE.plusMinutes(minutes);
    }

    // ==================================================================
    // 落库
    // ==================================================================

    @Test
    @DisplayName("插入时自动填充审计字段并回填主键")
    void insert_fillsAuditFieldsAndId() {
        FreePeriod row = period(fromBase(60), fromBase(120));

        int affected = freePeriodMapper.insert(row);

        assertEquals(1, affected, "插入一行");
        assertNotNull(row.getId(), "主键应当由数据库自增填回实体");
        assertNotNull(row.getCreatedAt(), "created_at 是 NOT NULL 且无默认值，靠自动填充补上");
        assertNotNull(row.getUpdatedAt(), "updated_at 同理");
    }

    // ==================================================================
    // 半开区间：五条查询共用同一套比较符
    // ==================================================================

    @Test
    @DisplayName("区间交集：首尾相接不算重叠")
    void selectOverlapping_touchingRangesDoNotOverlap() {
        // 18:00–20:00 与 20:00–22:00 两场背靠背
        freePeriodMapper.insert(period(fromBase(60), fromBase(120)));
        freePeriodMapper.insert(period(fromBase(120), fromBase(180)));

        // 从第二场开始的那一刻起算：只有第二场与该区间有交集
        List<FreePeriod> matched = freePeriodMapper.selectOverlapping(
                STORE_ID, fromBase(120), fromBase(150));

        assertEquals(1, matched.size(),
                "半开区间 [start, end) 下 20:00 已不属于第一场 —— "
                        + "写成闭区间的话第一场会一并命中，计费侧凭空多切一刀、多享一次宽限");
        assertEquals(fromBase(120), matched.get(0).getStartAt());
    }

    @Test
    @DisplayName("区间交集：活动包住订单、订单包住活动都命中")
    void selectOverlapping_handlesBothDirections() {
        // 活动 10:00–14:00，订单两头都在它里面
        freePeriodMapper.insert(period(fromBase(60), fromBase(300)));

        assertEquals(1, freePeriodMapper.selectOverlapping(
                        STORE_ID, fromBase(90), fromBase(120)).size(),
                "订单完全落在活动内");
        assertEquals(0, freePeriodMapper.selectOverlapping(
                        STORE_ID, fromBase(0), fromBase(30)).size(),
                "完全没有交集");
    }

    @Test
    @DisplayName("单点覆盖：开始那一刻算、结束那一刻不算")
    void selectCoveringAt_boundaryIsHalfOpen() {
        freePeriodMapper.insert(period(fromBase(60), fromBase(120)));

        assertNotNull(freePeriodMapper.selectCoveringAt(STORE_ID, fromBase(60)),
                "start_at 那一刻（含）已经在活动内");
        assertNull(freePeriodMapper.selectCoveringAt(STORE_ID, fromBase(120)),
                "end_at 那一刻（不含）恢复计费 —— 顾客在活动结束那一秒进场要照常收钱");
        assertNull(freePeriodMapper.selectCoveringAt(STORE_ID, fromBase(59)));
    }

    @Test
    @DisplayName("重叠计数：排除自己之后「原地改个名字」不再冲突")
    void countOverlapping_excludeIdSkipsSelf() {
        FreePeriod seeded = period(fromBase(60), fromBase(120));
        freePeriodMapper.insert(seeded);

        assertEquals(1, freePeriodMapper.countOverlapping(
                        STORE_ID, fromBase(90), fromBase(150), null),
                "不排除自己时，时段与自身重叠");
        assertEquals(0, freePeriodMapper.countOverlapping(
                        STORE_ID, fromBase(90), fromBase(150), seeded.getId()),
                "排除自己之后为 0 —— 少了它，「只改个名字」会被自己挡住");
    }

    // ==================================================================
    // 用户端「近期活动」
    // ==================================================================

    @Test
    @DisplayName("近期活动：正在进行的也在，已结束的不在，条数上限生效")
    void selectUpcoming_includesOngoingAndRespectsLimit() {
        freePeriodMapper.insert(period(fromBase(-120), fromBase(-60)));   // 已结束
        freePeriodMapper.insert(period(fromBase(-30), fromBase(30)));     // 进行中
        freePeriodMapper.insert(period(fromBase(60), fromBase(120)));     // 未来

        List<FreePeriod> upcoming = freePeriodMapper.selectUpcoming(STORE_ID, BASE, 10);

        assertEquals(2, upcoming.size(), "已结束的那场不该出现");
        assertEquals(fromBase(-30), upcoming.get(0).getStartAt(), "按开始时间升序");
        assertEquals(1, freePeriodMapper.selectUpcoming(STORE_ID, BASE, 1).size(),
                "LIMIT 生效 —— 它是「首页只放几条」的唯一一道保证");
    }

    // ==================================================================
    // 后台列表
    // ==================================================================

    @Test
    @DisplayName("后台列表：只取本门店，按开始时间倒序分页")
    void selectPageByStore_filtersStoreAndOrdersDesc() {
        freePeriodMapper.insert(period(fromBase(60), fromBase(90)));
        freePeriodMapper.insert(period(fromBase(120), fromBase(150)));

        FreePeriod others = period(fromBase(60), fromBase(90));
        others.setStoreId(OTHER_STORE_ID);
        freePeriodMapper.insert(others);

        Page<FreePeriod> page = new Page<>(1, 10);
        freePeriodMapper.selectPageByStore(page, STORE_ID);

        assertEquals(2, page.getTotal(), "别家门店的活动不该出现在本店列表里");
        assertEquals(fromBase(120), page.getRecords().get(0).getStartAt(),
                "按开始时间倒序 —— 运营多半想知道「接下来哪天有活动」，最近的在最前面最顺手");
    }

    // ==================================================================
    // 逻辑删除与显式 SQL
    // ==================================================================

    @Test
    @DisplayName("逻辑删除后：五条手写查询一条都查不到它")
    void deletedPeriod_isInvisibleToEveryQuery() {
        FreePeriod seeded = period(fromBase(60), fromBase(120));
        seeded.setReason("办完的活动");
        freePeriodMapper.insert(seeded);

        // 删之前先确认它确实能被查到 —— 否则下面的「查不到」可能只是因为查错了
        assertEquals(1, freePeriodMapper.selectOverlapping(
                STORE_ID, fromBase(90), fromBase(100)).size());

        freePeriodMapper.deleteById(seeded.getId());

        // ⚠️ 这是本 Mapper 最要紧的一条：五条查询全是手写 SQL，
        // 全局逻辑删除配置管不到它们，`deleted = 0` 漏在哪一条上都不会报错 ——
        // 只会表现成「活动删了却还在免单」，而对账时才发现少收了钱
        assertTrue(freePeriodMapper.selectOverlapping(
                        STORE_ID, fromBase(90), fromBase(100)).isEmpty(),
                "selectOverlapping 漏了 deleted = 0 的话，删掉的活动会继续免单");
        assertNull(freePeriodMapper.selectCoveringAt(STORE_ID, fromBase(90)),
                "selectCoveringAt 漏了的话，营业状态会显示成「活动进行中」");
        assertTrue(freePeriodMapper.selectUpcoming(STORE_ID, BASE, 10).isEmpty());
        assertEquals(0, freePeriodMapper.countOverlapping(
                        STORE_ID, fromBase(60), fromBase(120), null),
                "countOverlapping 漏了的话，重新排一场同样的活动会被自己挡");
        assertEquals(0, freePeriodMapper.selectPageByStore(new Page<>(1, 10), STORE_ID).getTotal());
    }

    @Test
    @DisplayName("改活动：名称可以真的清空")
    void updatePeriod_clearsReason() {
        FreePeriod seeded = period(fromBase(60), fromBase(120));
        seeded.setReason("要撤掉的名称");
        freePeriodMapper.insert(seeded);

        int affected = freePeriodMapper.updatePeriod(seeded.getId(),
                fromBase(60), fromBase(120), null);

        assertEquals(1, affected);
        assertNull(freePeriodMapper.selectById(seeded.getId()).getReason(),
                "走 updateById 的话这一格会被跳过：接口 200、刷新后旧名称还在，且不报任何错");
    }

    @Test
    @DisplayName("改活动：名称能换成新的，时段也一并落库")
    void updatePeriod_writesNewValues() {
        FreePeriod seeded = period(fromBase(60), fromBase(120));
        seeded.setReason("原名");
        freePeriodMapper.insert(seeded);

        freePeriodMapper.updatePeriod(seeded.getId(), fromBase(180), fromBase(240), "新名");

        FreePeriod loaded = freePeriodMapper.selectById(seeded.getId());
        assertEquals("新名", loaded.getReason());
        assertEquals(fromBase(180), loaded.getStartAt());
        assertEquals(fromBase(240), loaded.getEndAt());
    }
}
