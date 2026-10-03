package com.kaede.uspace.order.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kaede.uspace.order.entity.ReconcileBatch;
import com.kaede.uspace.order.reconcile.ReconcileChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ReconcileBatchMapper} 的集成测试，连本机真实 MySQL。
 *
 * <p><b>三重保护不污染开发库</b>：{@code @Transactional} 每个用例结束后自动回滚；
 * {@code @EnabledIfEnvironmentVariable} 让没配 {@code MYSQL_PASSWORD} 的机器整个跳过；
 * 门店 ID 取 {@link #STORE_SEQ} 起的连号，万一回滚失效也一眼能认出来。
 *
 * <p><b>为什么必须连真库</b>，这一处只有真库验证得到：
 * <b>MyBatis-Plus 自带的 {@code insert} 真的会回填主键</b>。
 * 那条链路是「插入批次 → 拿批次 ID 去回写凭证的 {@code reconcile_batch_id}」，
 * 主键拿不到的话整个对账写不进任何东西 —— 而假 Mapper 是照着「回填」写的，
 * 两边一起错就一起对。
 *
 * <p>断言都盯着自己插入的那几条：{@code biz_reconcile_batch} 是 append-only 的，
 * 开发库里会积累历次验证留下的批次，所以不去断言「总数是多少」。
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+",
        disabledReason = "未配置 MYSQL_PASSWORD，跳过需要真实数据库的集成测试")
class ReconcileBatchMapperIntegrationTests {

    /** 门店 ID 的起点，取大数以免与真实门店撞上，也让本类插入的行容易辨认 */
    private static final AtomicLong STORE_SEQ = new AtomicLong(999901L);

    /** 测试用的管理员 ID */
    private static final Long ADMIN_ID = 999901L;

    @Autowired
    private ReconcileBatchMapper batchMapper;

    @Test
    @DisplayName("插入：主键被回填 —— 整个对账都指望它")
    void insert_backfillsGeneratedKey() {
        long storeId = STORE_SEQ.incrementAndGet();

        ReconcileBatch batch = newBatch(storeId);
        batchMapper.insert(batch);

        assertNotNull(batch.getId(),
                "MyBatis-Plus 自带的 insert 会回填主键。拿不到它，"
                        + "后面的「把凭证认领到这一批」就没有批次 ID 可用，整条对账断在这里");
        assertNotNull(batch.getCreatedAt(),
                "created_at 由 AuditMetaObjectHandler 填充 —— 本表不继承 BaseEntity，"
                        + "靠实体字段上的 @TableField(fill = ...) 生效");
    }

    @Test
    @DisplayName("字段原样落库：金额不丢精度，可空的留档路径与时间不报错")
    void insert_keepsValues() {
        long storeId = STORE_SEQ.incrementAndGet();
        ReconcileBatch batch = newBatch(storeId);
        batch.setBillFilePath(null);
        batch.setPeriodStart(null);
        batch.setPeriodEnd(null);

        batchMapper.insert(batch);
        ReconcileBatch reloaded = batchMapper.selectById(batch.getId());

        assertEquals(0, new BigDecimal("1234.50").compareTo(reloaded.getBillAmount()),
                "DECIMAL(12,2) 不丢精度");
        assertEquals(0, new BigDecimal("1200.00").compareTo(reloaded.getMatchedAmount()));
        assertEquals(ReconcileChannel.WXPAY.name(), reloaded.getChannel());
        assertNull(reloaded.getBillFilePath(),
                "留档失败时路径为空是合法状态，不是错误 —— 对账本身仍然成功");
        assertNull(reloaded.getPeriodStart(),
                "账单里一条带交易时间的记录都没有时，区间为空 —— 查它的人要按「查不到即无」处理");
    }

    @Test
    @DisplayName("列表：按门店隔离，同店按时间倒序")
    void selectPageForAdmin_ordersByCreatedDesc() {
        long storeId = STORE_SEQ.incrementAndGet();
        long otherStoreId = STORE_SEQ.incrementAndGet();

        ReconcileBatch first = newBatch(storeId);
        batchMapper.insert(first);
        ReconcileBatch second = newBatch(storeId);
        second.setFileName("后来传的.csv");
        batchMapper.insert(second);
        batchMapper.insert(newBatch(otherStoreId));

        List<ReconcileBatch> records = batchMapper
                .selectPageForAdmin(new Page<>(1, 10), storeId).getRecords();

        assertEquals(2, records.size(),
                "另一个店的批次不能出现在这里 —— 当前虽然只有单门店，"
                        + "但索引与查询都是按 store_id 写的，写漏了将来开分店才会发现");
        assertEquals(second.getId(), records.get(0).getId(),
                "新的在前。created_at 精度只到秒时靠 id DESC 兜底，"
                        + "少了那一条，同一秒插入的两批顺序就不确定了");
        assertEquals(first.getId(), records.get(1).getId());
    }

    @Test
    @DisplayName("修正匹配笔数：只改那一列，其余字段纹丝不动")
    void updateMatchedCount_onlyTouchesThatColumn() {
        long storeId = STORE_SEQ.incrementAndGet();
        ReconcileBatch batch = newBatch(storeId);
        batchMapper.insert(batch);

        int affected = batchMapper.updateMatchedCount(batch.getId(), 3);

        assertEquals(1, affected, "这一列就是为「并发抢占后把计数改回实际值」准备的");
        ReconcileBatch reloaded = batchMapper.selectById(batch.getId());
        assertEquals(3, reloaded.getMatchedCount());
        assertEquals(batch.getBillCount(), reloaded.getBillCount(),
                "只改匹配笔数 —— 账单侧的那个数不该被动到");
        assertEquals(batch.getFileName(), reloaded.getFileName());
    }

    @Test
    @DisplayName("批次没有 deleted 列：真库上不会因为逻辑删除而被过滤掉")
    void noLogicalDeleteColumn() {
        long storeId = STORE_SEQ.incrementAndGet();
        ReconcileBatch batch = newBatch(storeId);
        batchMapper.insert(batch);

        assertNotNull(batchMapper.selectById(batch.getId()),
                "本表是 append-only 的财务记录，没有 deleted 列。"
                        + "手写 SQL 里写 deleted = 0 会直接 Unknown column");
    }

    /**
     * 造一条批次。
     *
     * @param storeId 门店 ID
     * @return 批次实体，尚未插入
     */
    private static ReconcileBatch newBatch(long storeId) {
        ReconcileBatch batch = new ReconcileBatch();
        batch.setStoreId(storeId);
        batch.setChannel(ReconcileChannel.WXPAY.name());
        batch.setFileName("微信支付账单(20260901-20260930).csv");
        batch.setBillFilePath("2026/09/abc.csv");
        batch.setFileSha256("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        batch.setPeriodStart(LocalDateTime.of(2026, 9, 1, 10, 12, 3));
        batch.setPeriodEnd(LocalDateTime.of(2026, 9, 30, 22, 41, 55));
        batch.setWindowStart(LocalDateTime.of(2026, 8, 31, 10, 12, 3));
        batch.setWindowEnd(LocalDateTime.of(2026, 10, 2, 22, 41, 55));
        batch.setBillCount(87);
        batch.setBillAmount(new BigDecimal("1234.50"));
        batch.setBillExcludedCount(3);
        batch.setBillSkippedCount(0);
        batch.setProofCount(92);
        batch.setProofAmount(new BigDecimal("1310.00"));
        batch.setProofSkippedCount(5);
        batch.setMatchedCount(85);
        batch.setMatchedAmount(new BigDecimal("1200.00"));
        batch.setDiffCount(7);
        batch.setCreatedBy(ADMIN_ID);
        return batch;
    }

    @Test
    @DisplayName("sha256 列放得下完整的 64 位十六进制串 —— 不能被静默截断")
    void fileSha256FitsColumnWidth() {
        long storeId = STORE_SEQ.incrementAndGet();
        ReconcileBatch batch = newBatch(storeId);

        batchMapper.insert(batch);
        String stored = batchMapper.selectById(batch.getId()).getFileSha256();

        assertEquals(batch.getFileSha256(), stored,
                "CHAR(64) 是按 SHA-256 的十六进制长度定的。列宽写小了不会有任何报错，"
                        + "只会让两个不同的文件在某些输入下算出同一个前缀 —— 而那是静默的错误匹配");
        assertEquals(64, stored.length());
    }
}
