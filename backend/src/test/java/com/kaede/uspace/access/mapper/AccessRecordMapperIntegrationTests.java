package com.kaede.uspace.access.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kaede.uspace.access.AccessSource;
import com.kaede.uspace.access.entity.AccessRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AccessRecordMapper} 的集成测试，连本机真实 MySQL。
 *
 * <p><b>为什么单测之外还需要它</b>：{@code AccessRecordServiceTests} 用的是内存版假 Mapper，
 * 它模拟了数据库行为的<b>语义</b>却绕过了真实的 SQL —— 列名映射写错、审计字段没被自动填充、
 * {@code deleted = 0} 写错表，这些问题在假 Mapper 面前统统不会暴露，却会在运行时炸掉。
 * 本测试专门覆盖这些「只有真库能验证」的部分。
 *
 * <p><b>测试数据如何辨认</b>：数值列没法加前缀，所以门店、门锁、用户三个 ID 都取
 * 大得不可能与真实数据撞车的值（{@code 999xxx}）。加上 {@code @Transactional} 回滚，
 * 正常跑完不会在库里留下任何痕迹。
 *
 * <p><b>三重保护不污染开发库</b>：{@code @Transactional} 每个用例后自动回滚；
 * {@code @EnabledIfEnvironmentVariable} 让没配 {@code MYSQL_PASSWORD} 的机器整个跳过
 * 而不是报错失败；ID 取值可辨认，万一回滚失效也一眼能认出来。
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+",
        disabledReason = "未配置 MYSQL_PASSWORD，跳过需要真实数据库的集成测试")
class AccessRecordMapperIntegrationTests {

    /** 测试用门店 ID。取大数以免与真实数据混淆 */
    private static final Long STORE_ID = 999901L;

    /** 测试用门锁 ID */
    private static final Long LOCK_ID = 999902L;

    /** 测试用用户 ID */
    private static final Long USER_ID = 999903L;

    @Autowired
    private AccessRecordMapper accessRecordMapper;

    // ==================================================================
    // 实体与表结构的对齐
    // ==================================================================

    @Test
    @DisplayName("插入时自动填充 created_at 并回填主键")
    void insert_fillsCreatedAtAndId() {
        AccessRecord record = newRecord(at(10, 0, 0));

        int affected = accessRecordMapper.insert(record);

        assertEquals(1, affected);
        assertNotNull(record.getId(), "主键应当由数据库自增填回实体");
        assertNotNull(record.getCreatedAt(),
                "created_at 是 NOT NULL 且无默认值，漏填会在插入时报错 —— "
                        + "这条用例确保字段上的 fill 注解真的生效，而不是等到线上同步时才炸");
    }

    @Test
    @DisplayName("本表没有 updated_at 与 deleted 列，实体也不该有")
    void insert_doesNotReferenceMissingColumns() {
        // 若 AccessRecord 照抄别的实体继承了 BaseEntity（它带 updated_at 与 deleted），
        // 下面这条插入会直接 Unknown column。它能过，就说明实体与表结构是对齐的。
        AccessRecord record = newRecord(at(10, 0, 0));
        accessRecordMapper.insert(record);

        assertNotNull(accessRecordMapper.selectById(record.getId()),
                "刚插入的行必须能查回来 —— selectById 是 MyBatis-Plus 生成的方法，"
                        + "没有被自动追加 deleted = 0，才说明本表确实没有这个字段");
    }

    @Test
    @DisplayName("三个手写 SQL 都能跑通 —— 写了 deleted = 0 会直接 Unknown column")
    void handwrittenSql_doesNotReferenceDeletedColumn() {
        accessRecordMapper.insert(newRecord(at(10, 0, 0)));

        // 本项目其它 Mapper 的手写 SQL 都必须带 deleted = 0（全局逻辑删除配置管不到它们），
        // 唯独本表没有那一列。照抄别处就会在这里炸出来。
        IPage<AccessRecord> byStore = accessRecordMapper.selectPageByStore(
                new Page<>(1, 10), STORE_ID, null, null, null);
        IPage<AccessRecord> byUser = accessRecordMapper.selectPageByUser(
                new Page<>(1, 10), USER_ID);
        List<AccessRecord> inWindow = accessRecordMapper.selectInWindow(
                LOCK_ID, at(0, 0, 0), at(23, 59, 59));

        assertEquals(1, byStore.getRecords().size());
        assertEquals(1, byUser.getRecords().size());
        assertEquals(1, inWindow.size());
    }

    // ==================================================================
    // 查询
    // ==================================================================

    @Test
    @DisplayName("按用户分页：只返回该用户的记录，按开门时刻倒序")
    void selectPageByUser_returnsOnlyThatUserAndOrdersDescending() {
        insertRecord(at(10, 0, 0), USER_ID);
        insertRecord(at(11, 0, 0), USER_ID);
        insertRecord(at(12, 0, 0), USER_ID + 1);

        IPage<AccessRecord> page = accessRecordMapper.selectPageByUser(new Page<>(1, 10), USER_ID);

        assertEquals(2, page.getTotal(), "另一个用户的记录不该被查到");
        assertEquals(at(11, 0, 0), page.getRecords().get(0).getOpenTime(), "最近的排最前");
    }

    @Test
    @DisplayName("按门店分页：时间范围是半开区间 —— 起点含、终点不含")
    void selectPageByStore_filtersHalfOpenWindow() {
        insertRecord(at(10, 0, 0), USER_ID);
        insertRecord(at(12, 0, 0), USER_ID);

        IPage<AccessRecord> page = accessRecordMapper.selectPageByStore(
                new Page<>(1, 10), STORE_ID, null, at(10, 0, 0), at(12, 0, 0));

        assertEquals(1, page.getTotal(),
                "10:00 那条算在内、12:00 那条不算 —— 这条口径必须与假 Mapper 逐字一致，"
                        + "改了一处不改另一处，边界用例会静默地得出相反结论");
        assertEquals(at(10, 0, 0), page.getRecords().get(0).getOpenTime());
    }

    @Test
    @DisplayName("按门店分页：userId 传 null 时查全部")
    void selectPageByStore_returnsAllWhenUserIdNull() {
        insertRecord(at(10, 0, 0), USER_ID);
        insertRecord(at(11, 0, 0), USER_ID + 1);

        IPage<AccessRecord> page = accessRecordMapper.selectPageByStore(
                new Page<>(1, 10), STORE_ID, null, null, null);

        assertEquals(2, page.getTotal(),
                "管理端不传 userId 就是查全部 —— 与「按用户分页」那条放在一起看，"
                        + "就知道用户端为什么必须走另一个方法：那个方法压根没有「传空查全表」的语义");
    }

    @Test
    @DisplayName("窗口查询：右端不含 —— 单条落库的同秒判重靠它")
    void selectInWindow_excludesRightEndpoint() {
        LocalDateTime second = at(10, 0, 0);
        insertRecord(second, USER_ID);
        insertRecord(second.plusSeconds(1), USER_ID);

        // 单条落库时传的是 [t, t+1秒) 这样一个只覆盖一秒的窄窗口
        List<AccessRecord> sameSecond = accessRecordMapper.selectInWindow(
                LOCK_ID, second, second.plusSeconds(1));

        assertEquals(1, sameSecond.size(),
                "右端开区间，所以只圈住 10:00:00 这一秒，不会把下一秒的记录也算进来");
        assertEquals(second, sameSecond.get(0).getOpenTime());
    }

    // ==================================================================
    // 约束
    // ==================================================================

    @Test
    @DisplayName("可空的列确实可空：订单号、开门人、密码都允许为空")
    void insert_allowsNullOrderIdUserAndPasscode() {
        AccessRecord record = newRecord(at(10, 0, 0));
        record.setOrderId(null);
        record.setUserId(null);
        record.setPasscode(null);

        assertEquals(1, accessRecordMapper.insert(record),
                "从门锁云同步进来的记录本就没有订单号，用指纹开门时也没有用户与密码 —— "
                        + "这三列必须允许为空，否则同步整批会失败");
    }

    @Test
    @DisplayName("store_id 的非空约束确实存在")
    void insert_rejectsNullStoreId() {
        AccessRecord record = newRecord(at(10, 0, 0));
        record.setStoreId(null);

        assertThrows(DataIntegrityViolationException.class,
                () -> accessRecordMapper.insert(record),
                "门店是必填的 —— Service 取不到当前门店时必须提前失败，"
                        + "而不是把一条 store_id 为 null 的记录交给数据库");
    }

    @Test
    @DisplayName("已知取舍：库上没有 (lock_id, open_time) 唯一索引，同锁同秒能插两条")
    void noUniqueIndexOnLockAndOpenTime_allowsDuplicates() {
        LocalDateTime time = at(10, 0, 0);
        insertRecord(time, USER_ID);
        insertRecord(time, USER_ID);

        assertEquals(2, accessRecordMapper.selectInWindow(LOCK_ID, time, time.plusSeconds(1)).size(),
                "判重是应用层的事，库上没有唯一索引拦着。这是有意的："
                        + "同一把锁同一秒真的开两次门（第一次没推开、再输一遍）业务上并非不可能，"
                        + "唯一索引会静默丢掉第二条，而「丢掉一条开门记录」比「多一条重复」更难发现。"
                        + "并发重复的残余风险由「手动触发、低频操作」兜底 —— 这条用例把取舍钉在明面上");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 构造一条测试用记录（未插入）。
     *
     * @param openTime 开门时刻
     * @return 记录实体
     */
    private static AccessRecord newRecord(LocalDateTime openTime) {
        AccessRecord record = new AccessRecord();
        record.setUserId(USER_ID);
        record.setStoreId(STORE_ID);
        record.setLockId(LOCK_ID);
        record.setPasscode("123456");
        record.setOpenType(2);
        record.setOpenTime(openTime);
        record.setSource(AccessSource.MOCK.name());
        return record;
    }

    /**
     * 插入一条记录。
     *
     * @param openTime 开门时刻
     * @param userId   开门人
     */
    private void insertRecord(LocalDateTime openTime, Long userId) {
        AccessRecord record = newRecord(openTime);
        record.setUserId(userId);
        accessRecordMapper.insert(record);
    }

    /**
     * 构造一个固定的日期时间（用写死的日期，结果不随运行时刻变化）。
     *
     * @param hour   小时
     * @param minute 分钟
     * @param second 秒
     * @return 该时刻
     */
    private static LocalDateTime at(int hour, int minute, int second) {
        return LocalDateTime.of(2026, 9, 20, hour, minute, second);
    }
}
