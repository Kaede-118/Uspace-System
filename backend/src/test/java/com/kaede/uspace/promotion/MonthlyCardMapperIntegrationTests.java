package com.kaede.uspace.promotion;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kaede.uspace.promotion.entity.MonthlyCard;
import com.kaede.uspace.promotion.mapper.MonthlyCardMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MonthlyCardMapper} 的集成测试。
 *
 * <p><b>连真库、每个用例结束后自动回滚。</b>三重保护不污染开发库：
 * {@code @Transactional} 回滚；{@code @EnabledIfEnvironmentVariable} 让没配
 * {@code MYSQL_PASSWORD} 的机器整个跳过而不是报错失败；用户 ID 取 999xxx 这类
 * 可辨认的大数，万一回滚失效也一眼能认出来。
 *
 * <p><b>为什么假 Mapper 不够、必须连真库</b>：假实现模拟的是数据库行为的
 * <b>语义</b>，却绕过了真实 SQL —— 列名映射写错、条件写漏、聚合函数的空结果
 * 返回 null 而不是 0，这些问题在假 Mapper 面前统统不会暴露。
 * 本类重点钉住的是<b>手写 SQL 里的 {@code deleted = 0}</b>：
 * 全局逻辑删除配置管不到注解 SQL，漏写的后果不是报错，
 * 而是「删掉的卡又冒出来」。
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+",
        disabledReason = "未配置 MYSQL_PASSWORD，跳过需要真实数据库的集成测试")
class MonthlyCardMapperIntegrationTests {

    /** 测试用的用户 ID。取大数以免与真实数据混淆 */
    private static final Long USER_ID = 999901L;
    private static final Long OTHER_USER_ID = 999902L;

    @Autowired
    private MonthlyCardMapper cardMapper;

    @Test
    @DisplayName("插入：主键回填，审计字段自动填充，列名映射对得上")
    void insert_fillsAuditFieldsAndMapsColumns() {
        LocalDate today = LocalDate.now();
        MonthlyCard card = newCard(USER_ID, MonthlyCardType.ALL_DAY, today, today.plusDays(29));
        cardMapper.insert(card);

        assertNotNull(card.getId(), "自增主键要回填到实体上");

        MonthlyCard loaded = cardMapper.selectById(card.getId());
        assertNotNull(loaded, "插入的卡要能查回来");
        assertEquals(card.getCardNo(), loaded.getCardNo());
        assertEquals(USER_ID, loaded.getUserId());
        assertEquals(0, new BigDecimal("600").compareTo(loaded.getPrice()), "金额列的标度要对得上");
        assertNotNull(loaded.getCreatedAt(), "created_at 由框架自动填充");
        assertNotNull(loaded.getUpdatedAt(), "updated_at 由框架自动填充");
    }

    @Test
    @DisplayName("生效判定：已退款的卡不再命中，哪怕它的 ID 更大")
    void selectActiveAt_skipsRefundedCard() {
        LocalDate today = LocalDate.now();

        MonthlyCard effective = insertCard(USER_ID, MonthlyCardType.ALL_DAY,
                today, today.plusDays(29));
        MonthlyCard refunded = insertCard(USER_ID, MonthlyCardType.ALL_DAY,
                today, today.plusDays(29));
        refunded.setStatus(MonthlyCardStatus.REFUNDED.name());
        cardMapper.updateById(refunded);

        // 构造前提：SQL 是 ORDER BY id DESC LIMIT 1，已退款的那张 ID 更大 ——
        // 少了状态条件，取出来的就会是它
        assertTrue(refunded.getId() > effective.getId(), "构造前提：已退款的卡是后插入的");

        assertEquals(effective.getId(), cardMapper.selectActiveAt(USER_ID, today).getId(),
                "少了状态条件，退款之后卡会继续免单，钱一路免下去且没人会发现");
    }

    @Test
    @DisplayName("生效判定：用户之间互不干扰")
    void selectActiveAt_isScopedToUser() {
        LocalDate today = LocalDate.now();
        insertCard(USER_ID, MonthlyCardType.ALL_DAY, today, today.plusDays(29));

        assertNull(cardMapper.selectActiveAt(OTHER_USER_ID, today), "别人有卡不代表自己有");
    }

    @Test
    @DisplayName("生效判定：生效当天与失效当天都算有效")
    void selectActiveAt_includesBothEnds() {
        LocalDate today = LocalDate.now();
        MonthlyCard card = insertCard(USER_ID, MonthlyCardType.NIGHT,
                today.minusDays(5), today.plusDays(3));

        assertNotNull(cardMapper.selectActiveAt(USER_ID, today.minusDays(5)), "生效当天算有效");
        assertNotNull(cardMapper.selectActiveAt(USER_ID, today.plusDays(3)),
                "失效当天算有效 —— 用 >= 而不是 >");
        assertNull(cardMapper.selectActiveAt(USER_ID, today.minusDays(6)), "生效前一天不算");
        assertNull(cardMapper.selectActiveAt(USER_ID, today.plusDays(4)), "失效次日不算");
    }

    @Test
    @DisplayName("生效判定：逻辑删除的卡查不出来")
    void selectActiveAt_excludesDeleted() {
        LocalDate today = LocalDate.now();
        MonthlyCard card = insertCard(USER_ID, MonthlyCardType.ALL_DAY, today, today.plusDays(29));
        cardMapper.deleteById(card.getId());

        assertNull(cardMapper.selectActiveAt(USER_ID, today),
                "手写 SQL 必须自己带 deleted = 0 —— 全局配置管不到注解里的 SQL，"
                        + "漏写的后果是删掉的卡又冒出来");
        assertEquals(0, cardMapper.selectByUser(USER_ID).size(), "列表查询同样要过滤");
    }

    @Test
    @DisplayName("到期翻转：只翻已过失效日的，当天到期的不动")
    void expireBefore_flipsOnlyPassedCards() {
        LocalDate today = LocalDate.now();
        MonthlyCard passed = insertCard(USER_ID, MonthlyCardType.ALL_DAY,
                today.minusDays(30), today.minusDays(1));
        MonthlyCard dueToday = insertCard(OTHER_USER_ID, MonthlyCardType.ALL_DAY,
                today.minusDays(29), today);

        int affected = cardMapper.expireBefore(today);

        // 这条 SQL 是全表操作，受影响行数里可能包含库里既有的到期卡，
        // 所以断言「至少翻转了一张」，正确性由下面两条具体记录的状态来钉
        assertTrue(affected >= 1, "本次插入的那张昨天到期的卡应当被翻转");
        assertEquals(MonthlyCardStatus.EXPIRED.name(),
                cardMapper.selectById(passed.getId()).getStatus());
        assertEquals(MonthlyCardStatus.ACTIVE.name(),
                cardMapper.selectById(dueToday.getId()).getStatus(),
                "end_date = 今天仍算有效，与免单判定的 >= 口径一致");
    }

    @Test
    @DisplayName("后台分页：三个筛选条件都能生效，也能都为空")
    void selectPageBy_filtersByEachCondition() {
        LocalDate today = LocalDate.now();
        insertCard(USER_ID, MonthlyCardType.NIGHT, today, today.plusDays(29));
        insertCard(OTHER_USER_ID, MonthlyCardType.ALL_DAY, today, today.plusDays(29));

        // 每条查询都带上自己的测试用户 ID：开发库里可能有别人留下的卡
        // （手工验证、演示数据），拿「全表条数」做断言会被那些数据打翻 ——
        // 这种失败与本次改动毫无关系，却要花时间去查，不如一开始就限定范围
        List<MonthlyCard> mine = cardMapper.selectPageBy(
                new Page<>(1, 10), USER_ID, null, null).getRecords();
        assertEquals(1, mine.size(), "按用户筛选");
        assertEquals(MonthlyCardType.NIGHT.name(), mine.get(0).getCardType());

        List<MonthlyCard> nights = cardMapper.selectPageBy(
                new Page<>(1, 10), OTHER_USER_ID, null, MonthlyCardType.NIGHT.name()).getRecords();
        assertEquals(0, nights.size(),
                "按卡类型筛选：这个测试用户名下只有一张全天卡，夜间卡应当筛不出来");

        List<MonthlyCard> active = cardMapper.selectPageBy(
                new Page<>(1, 10), USER_ID, MonthlyCardStatus.ACTIVE.name(), null).getRecords();
        assertEquals(1, active.size(), "按状态筛选");

        // 取一个明确没有任何数据的用户 ID：不能用 USER_ID + 1，
        // 那正是上面用到的另一个测试用户
        List<MonthlyCard> none = cardMapper.selectPageBy(
                new Page<>(1, 10), USER_ID + 1000L, null, null).getRecords();
        assertEquals(0, none.size(), "没有卡的用户查出来是空的，不是全表");
    }

    // ==================================================================
    // 构造辅助
    // ==================================================================

    /**
     * 造一张卡并落库。
     *
     * @param userId 持卡人
     * @param type   卡种
     * @param start  生效日期
     * @param end    失效日期
     * @return 已落库的卡（主键已回填）
     */
    private MonthlyCard insertCard(Long userId, MonthlyCardType type,
                                   LocalDate start, LocalDate end) {
        MonthlyCard card = newCard(userId, type, start, end);
        cardMapper.insert(card);
        return card;
    }

    /**
     * 造一张未落库的卡。
     *
     * @param userId 持卡人
     * @param type   卡种
     * @param start  生效日期
     * @param end    失效日期
     * @return 月卡实体
     */
    private static MonthlyCard newCard(Long userId, MonthlyCardType type,
                                       LocalDate start, LocalDate end) {
        MonthlyCard card = new MonthlyCard();
        card.setCardNo(MonthlyCardNo.generate());
        card.setUserId(userId);
        card.setCardType(type.name());
        card.setPrice(new BigDecimal("600"));
        card.setStartDate(start);
        card.setEndDate(end);
        card.setStatus(MonthlyCardStatus.ACTIVE.name());
        return card;
    }
}
