package com.kaede.uspace.access;

import com.kaede.uspace.access.dto.AccessRecordVo;
import com.kaede.uspace.access.dto.ManualOpenRequest;
import com.kaede.uspace.access.dto.RecordOpenVo;
import com.kaede.uspace.access.dto.SyncRecordsVo;
import com.kaede.uspace.access.entity.AccessRecord;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.lock.LockProperties;
import com.kaede.uspace.lock.dto.LockRecordDto;
import com.kaede.uspace.lock.dto.LockStatus;
import com.kaede.uspace.space.FakeStoreMapper;
import com.kaede.uspace.space.entity.Store;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AccessRecordService} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring、不连数据库。</b>
 *
 * <p>覆盖重点在两处「写错了不报错、只会静默出错」的地方：
 * <ul>
 *   <li><b>判重的秒截断</b> —— 门锁云返回的开门时刻可能带亚秒精度，而
 *       {@code open_time} 列是秒精度。不归一的话，「拉回来的值」与「库里的值」
 *       永远不相等，判重形同虚设，每次同步都重复插入，且不报任何错</li>
 *   <li><b>「拉取失败」与「确实没有记录」的区分</b> —— 上游把失败吞成了空列表，
 *       直接采信就会把「没拉到」当成「确实没有」，系统一声不吭地少记数据</li>
 * </ul>
 *
 * <p>另外钉住半开区间口径：本模块对外是 {@code [from, to)}，而上游是闭区间
 * {@code [from, to]}，多出来的那条（恰好落在 {@code to} 上）必须被过滤掉。
 */
class AccessRecordServiceTests {

    /** 测试门店的 ID */
    private static final Long STORE_ID = 1L;

    /** 测试门锁的 ID */
    private static final Long LOCK_ID = 1001L;

    /** 测试用户 ID */
    private static final Long USER_ID = 7L;

    /** 另一个用户 ID，用于验证用户端的查询隔离 */
    private static final Long OTHER_USER_ID = 99L;

    /** 开门所用密码 */
    private static final String PASSCODE = "123456";

    /** 内存版数据访问层 */
    private final FakeAccessRecordMapper recordMapper = new FakeAccessRecordMapper();
    private final FakeStoreMapper storeMapper = new FakeStoreMapper();
    private final FakeLockService lockService = new FakeLockService();

    /** 被测服务，默认按模拟门锁（provider=mock）构造 */
    private final AccessRecordService service = newService("mock");

    /**
     * 每个用例前预置门店 —— 缺了它同步会在第一步就返回 STORE_NOT_FOUND，
     * 后面的规则一条都测不到。
     */
    @BeforeEach
    void setUp() {
        Store store = new Store();
        store.setId(STORE_ID);
        store.setName("测试门店");
        storeMapper.seed(store);
    }

    // ==================================================================
    // 同步：落库与幂等
    // ==================================================================

    @Test
    @DisplayName("同步：把云端记录落库，字段一一对应")
    void syncRecords_insertsFetchedRecords() {
        lockService.seedRecord(record(yesterdayAt(10, 0, 0)));
        lockService.seedRecord(record(yesterdayAt(11, 30, 0)));

        BizResult<SyncRecordsVo> result = service.syncRecords(
                LOCK_ID, yesterdayAt(9, 0, 0), yesterdayAt(12, 0, 0));

        assertTrue(result.isSuccess(), "正常的同步应当成功");
        SyncRecordsVo vo = result.getData();
        assertEquals(2, vo.getFetched(), "云端返回两条，都落在区间内");
        assertEquals(2, vo.getInserted(), "两条都是新的，应当全部落库");
        assertEquals(0, vo.getSkipped());
        assertEquals(0, vo.getDiscarded());
        assertEquals(1, vo.getCloudCalls(), "拉取非空时不该额外探活，只花一次额度");

        AccessRecord stored = recordMapper.get(1L);
        assertNotNull(stored, "应当有一条记录落库");
        assertEquals(STORE_ID, stored.getStoreId(), "单门店运营下取当前门店");
        assertEquals(LOCK_ID, stored.getLockId());
        assertEquals(USER_ID, stored.getUserId());
        assertEquals(PASSCODE, stored.getPasscode(), "通通锁的 keyboardPwd 对应本表的 passcode");
        assertEquals(AccessSource.MOCK.name(), stored.getSource());
        assertEquals(yesterdayAt(10, 0, 0), stored.getOpenTime());
        assertNotNull(stored.getCreatedAt(), "created_at 由框架自动填充");
    }

    @Test
    @DisplayName("同步：同一区间同步两次，第二次全部跳过（幂等）")
    void syncRecords_skipsDuplicatesOnSecondSync() {
        lockService.seedRecord(record(yesterdayAt(10, 0, 0)));
        lockService.seedRecord(record(yesterdayAt(11, 0, 0)));

        service.syncRecords(LOCK_ID, yesterdayAt(9, 0, 0), yesterdayAt(12, 0, 0));
        BizResult<SyncRecordsVo> second = service.syncRecords(
                LOCK_ID, yesterdayAt(9, 0, 0), yesterdayAt(12, 0, 0));

        assertTrue(second.isSuccess());
        assertEquals(2, second.getData().getFetched());
        assertEquals(0, second.getData().getInserted(), "第二次不该写入任何数据");
        assertEquals(2, second.getData().getSkipped(), "两条都因重复被跳过");
        assertEquals(2, recordMapper.size(), "库里仍然只有两条 —— 重复同步不产生重复数据");
    }

    @Test
    @DisplayName("同步：带亚秒精度的开门时刻被截断到秒，判重因此有效")
    void syncRecords_dedupesSubSecondOpenTime() {
        // 门锁云返回的时刻可能带纳秒，而 DATETIME 列只存到秒。
        // 不截断的话，拉回来的值永远不等于库里的值 —— 判重失效且不报错。
        lockService.seedRecord(record(yesterdayAt(10, 0, 0).withNano(700_000_000)));

        service.syncRecords(LOCK_ID, yesterdayAt(9, 0, 0), yesterdayAt(12, 0, 0));
        BizResult<SyncRecordsVo> second = service.syncRecords(
                LOCK_ID, yesterdayAt(9, 0, 0), yesterdayAt(12, 0, 0));

        assertEquals(1, recordMapper.size(),
                "纳秒被截断后，第二次同步应当认出这是同一条记录");
        assertEquals(0, second.getData().getInserted());
        assertEquals(1, second.getData().getSkipped());
        assertEquals(yesterdayAt(10, 0, 0), recordMapper.get(1L).getOpenTime(),
                "落库的是截断到秒的时刻，不是云端给的原始值");
    }

    @Test
    @DisplayName("同步：同一批里的两条同秒记录只落一条")
    void syncRecords_dedupesWithinSameBatch() {
        lockService.seedRecord(record(yesterdayAt(10, 0, 0)));
        lockService.seedRecord(record(yesterdayAt(10, 0, 0)));

        BizResult<SyncRecordsVo> result = service.syncRecords(
                LOCK_ID, yesterdayAt(9, 0, 0), yesterdayAt(12, 0, 0));

        assertEquals(1, result.getData().getInserted());
        assertEquals(1, result.getData().getSkipped(), "同批里的第二条被判重集合挡住");
        assertEquals(1, recordMapper.size());
    }

    // ==================================================================
    // 同步：区间口径
    // ==================================================================

    @Test
    @DisplayName("同步：恰好落在起点的记录会被收下（半开区间起点含）")
    void syncRecords_includesRecordExactlyAtFrom() {
        lockService.seedRecord(record(yesterdayAt(10, 0, 0)));

        BizResult<SyncRecordsVo> result = service.syncRecords(
                LOCK_ID, yesterdayAt(10, 0, 0), yesterdayAt(12, 0, 0));

        assertEquals(1, result.getData().getInserted(), "区间是 [from, to)，起点属于本窗口");
        assertEquals(1, recordMapper.size());
    }

    @Test
    @DisplayName("同步：恰好落在终点的记录被剔除（上游是闭区间，会多给这一条）")
    void syncRecords_excludesRecordExactlyAtTo() {
        // 上游 listRecords 是闭区间 [from, to]，会连 to 那一秒的记录一起返回。
        // 不剔除的话，这条记录下次以 to 为起点同步时会被再拉一遍 ——
        // 虽然判重能兜住，但「哪个窗口该收哪条」的口径就含糊了。
        lockService.seedRecord(record(yesterdayAt(12, 0, 0)));

        BizResult<SyncRecordsVo> result = service.syncRecords(
                LOCK_ID, yesterdayAt(10, 0, 0), yesterdayAt(12, 0, 0));

        assertEquals(0, result.getData().getInserted(), "to 那一刻不属于本窗口，留给下一次同步");
        assertEquals(0, result.getData().getFetched(), "被剔除的不计入 fetched");
        assertEquals(0, recordMapper.size());
    }

    @Test
    @DisplayName("同步：结束时间不晚于开始时间时拒绝")
    void syncRecords_rejectsReversedRange() {
        assertEquals(ErrorCode.ACCESS_TIME_INVALID,
                service.syncRecords(LOCK_ID, yesterdayAt(12, 0, 0), yesterdayAt(10, 0, 0)).getError());
        assertEquals(ErrorCode.ACCESS_TIME_INVALID,
                service.syncRecords(LOCK_ID, yesterdayAt(10, 0, 0), yesterdayAt(10, 0, 0)).getError(),
                "首尾相同也不合法 —— 那是个空区间，没有拉取的意义");
    }

    @Test
    @DisplayName("同步：跨度超过 31 天时拒绝，且一次云端调用都不发生")
    void syncRecords_rejectsOversizedRange() {
        BizResult<SyncRecordsVo> result = service.syncRecords(
                LOCK_ID, yesterdayAt(10, 0, 0), yesterdayAt(10, 0, 0).plusDays(32));

        assertEquals(ErrorCode.ACCESS_TIME_INVALID, result.getError());
        assertEquals(0, lockService.listCalls(),
                "参数就不合法，不该白花额度去拉取 —— 校验必须发生在调用云端之前");
    }

    @Test
    @DisplayName("同步：门店不存在时拒绝，而不是落一条 store_id 为空的记录")
    void syncRecords_failsWhenStoreMissing() {
        // 换一个没有门店数据的 Mapper，模拟「建表脚本没跑」的情形
        FakeStoreMapper emptyStoreMapper = new FakeStoreMapper();
        AccessRecordService bareService = new AccessRecordService(
                recordMapper.asMapper(), emptyStoreMapper.asMapper(),
                lockService.asService(), propertiesOf("mock"));

        BizResult<SyncRecordsVo> result = bareService.syncRecords(
                LOCK_ID, yesterdayAt(9, 0, 0), yesterdayAt(12, 0, 0));

        assertEquals(ErrorCode.STORE_NOT_FOUND, result.getError(),
                "store_id 是 NOT NULL 列，取不到门店就必须停下");
    }

    // ==================================================================
    // 同步：区分「拉取失败」与「确实没有记录」
    // ==================================================================

    @Test
    @DisplayName("同步：拉取为空且锁离线时报错，而不是「成功、0 条」")
    void syncRecords_reportsFailureWhenEmptyAndLockOffline() {
        lockService.failNextList().withStatus(LockStatus.OFFLINE);

        BizResult<SyncRecordsVo> result = service.syncRecords(
                LOCK_ID, yesterdayAt(9, 0, 0), yesterdayAt(12, 0, 0));

        assertEquals(ErrorCode.LOCK_CLOUD_UNAVAILABLE, result.getError(),
                "空结果有两种可能，锁离线时无法确认是「确实没有记录」——"
                        + "把失败当成空会让系统一声不吭地少记数据");
        assertEquals(0, recordMapper.size(), "失败时不该留下任何数据");
    }

    @Test
    @DisplayName("同步：拉取为空且锁状态未知时报错")
    void syncRecords_reportsFailureWhenEmptyAndStatusUnknown() {
        lockService.failNextList().withStatus(LockStatus.UNKNOWN);

        assertEquals(ErrorCode.LOCK_CLOUD_UNAVAILABLE,
                service.syncRecords(LOCK_ID, yesterdayAt(9, 0, 0), yesterdayAt(12, 0, 0)).getError(),
                "云端都联系不上，这次「0 条」没有任何可信度");
    }

    @Test
    @DisplayName("同步：锁在线且确实没有记录时正常返回 0 条")
    void syncRecords_acceptsEmptyWhenLockOnline() {
        lockService.withStatus(LockStatus.ONLINE);

        BizResult<SyncRecordsVo> result = service.syncRecords(
                LOCK_ID, yesterdayAt(9, 0, 0), yesterdayAt(12, 0, 0));

        assertTrue(result.isSuccess(), "探到锁在线，就敢下「确实没有记录」的结论");
        assertEquals(0, result.getData().getFetched());
        assertEquals(0, result.getData().getInserted());
        assertEquals(2, result.getData().getCloudCalls(), "拉取一次 + 探活一次");
    }

    @Test
    @DisplayName("同步：拉取非空时不探活，不白花额度")
    void syncRecords_skipsProbeWhenRecordsExist() {
        lockService.seedRecord(record(yesterdayAt(10, 0, 0)));

        BizResult<SyncRecordsVo> result = service.syncRecords(
                LOCK_ID, yesterdayAt(9, 0, 0), yesterdayAt(12, 0, 0));

        assertEquals(1, result.getData().getCloudCalls(),
                "拉到了记录就说明云端可达，不该再多花一次探活的额度");
        assertEquals(1, lockService.listCalls());
    }

    @Test
    @DisplayName("同步：缺少开门时刻的脏数据被丢弃并计数，不让整批失败")
    void syncRecords_ignoresRecordWithNullOpenTime() {
        lockService.seedRecord(new LockRecordDto(LOCK_ID, PASSCODE, null, 2, USER_ID));
        lockService.seedRecord(record(yesterdayAt(10, 0, 0)));

        BizResult<SyncRecordsVo> result = service.syncRecords(
                LOCK_ID, yesterdayAt(9, 0, 0), yesterdayAt(12, 0, 0));

        assertTrue(result.isSuccess(), "一条脏数据不该让整批同步失败");
        assertEquals(1, result.getData().getDiscarded(), "丢弃了几条要如实报出来，不能悄悄吞掉");
        assertEquals(1, result.getData().getInserted(), "正常的那条照常落库");
    }

    // ==================================================================
    // 同步：记录来源
    // ==================================================================

    @Test
    @DisplayName("同步：记录来源跟随门锁实现方")
    void syncRecords_marksSourceByProvider() {
        lockService.seedRecord(record(yesterdayAt(10, 0, 0)));

        newService("ttlock").syncRecords(LOCK_ID, yesterdayAt(9, 0, 0), yesterdayAt(12, 0, 0));

        assertEquals(AccessSource.TTLOCK.name(), recordMapper.get(1L).getSource(),
                "从哪个实现拉来的就记哪个来源，不该由调用方指定");
    }

    // ==================================================================
    // 单条落库
    // ==================================================================

    @Test
    @DisplayName("落库：新记录写入并标记 created")
    void recordOpen_insertsNewRecordWithCreatedTrue() {
        BizResult<RecordOpenVo> result = service.recordOpen(record(yesterdayAt(10, 0, 0)), null);

        assertTrue(result.isSuccess());
        assertTrue(result.getData().isCreated(), "这是该时刻的第一条记录");
        assertEquals(1, recordMapper.size());
        assertNotNull(result.getData().getRecord().getId(), "落库后主键应当已回填");
    }

    @Test
    @DisplayName("落库：同一把锁同一秒的第二条记录不写入，返回已有那条")
    void recordOpen_returnsExistingWithCreatedFalseOnSameSecond() {
        service.recordOpen(record(yesterdayAt(10, 0, 0)), null);
        BizResult<RecordOpenVo> second = service.recordOpen(record(yesterdayAt(10, 0, 0)), null);

        assertTrue(second.isSuccess(), "重复开门不是错误，只是不该重复记");
        assertFalse(second.getData().isCreated(), "该秒已有记录，本次没新增");
        assertEquals(yesterdayAt(10, 0, 0), second.getData().getRecord().getOpenTime());
        assertEquals(1, recordMapper.size(), "库里仍然只有一条 —— 同一次进门不该记两次");
    }

    @Test
    @DisplayName("落库：关联订单 ID 会被一并写入")
    void recordOpen_persistsOrderId() {
        service.recordOpen(record(yesterdayAt(10, 0, 0)), 555L);

        assertEquals(555L, recordMapper.get(1L).getOrderId(),
                "带上订单号才能在事后按订单追溯开门记录");
    }

    // ==================================================================
    // 补录
    // ==================================================================

    @Test
    @DisplayName("补录：来源固定记为管理员补录，请求方无法改变")
    void recordManualOpen_persistsWithAdminSource() {
        ManualOpenRequest request = new ManualOpenRequest();
        request.setLockId(LOCK_ID);
        request.setOpenTime(yesterdayAt(10, 0, 0));
        request.setUserId(USER_ID);
        request.setPasscode(PASSCODE);

        BizResult<RecordOpenVo> result = service.recordManualOpen(request);

        assertTrue(result.isSuccess());
        AccessRecord stored = recordMapper.get(1L);
        assertEquals(AccessSource.ADMIN.name(), stored.getSource(),
                "补录来源固定，不由请求方指定 —— 审计上必须能一眼看出哪些是人工填的");
        assertEquals(USER_ID, stored.getUserId());
        assertEquals(PASSCODE, stored.getPasscode());
    }

    @Test
    @DisplayName("补录：开门时刻落在未来时拒绝")
    void recordManualOpen_rejectsFutureOpenTime() {
        ManualOpenRequest request = new ManualOpenRequest();
        request.setLockId(LOCK_ID);
        request.setOpenTime(LocalDateTime.now().plusHours(1));

        assertEquals(ErrorCode.ACCESS_TIME_INVALID, service.recordManualOpen(request).getError(),
                "补录天然是事后行为，未来的时刻还没发生");
    }

    // ==================================================================
    // 查询与隔离
    // ==================================================================

    @Test
    @DisplayName("用户端查询：只返回自己的记录")
    void listMyRecords_returnsOnlyThatUser() {
        seedFor(USER_ID, yesterdayAt(10, 0, 0));
        seedFor(OTHER_USER_ID, yesterdayAt(11, 0, 0));

        BizResult<PageResult<AccessRecordVo>> result = service.listMyRecords(USER_ID, 1, 10);

        assertTrue(result.isSuccess());
        assertEquals(1, result.getData().getRecords().size(), "别人的记录不该出现在这里");
        assertEquals(USER_ID, result.getData().getRecords().get(0).getUserId());
    }

    @Test
    @DisplayName("管理端查询：不传 userId 时查全表（这正是用户端不能复用它的原因）")
    void listRecords_returnsAllWhenUserIdNull() {
        seedFor(USER_ID, yesterdayAt(10, 0, 0));
        seedFor(OTHER_USER_ID, yesterdayAt(11, 0, 0));

        BizResult<PageResult<AccessRecordVo>> result = service.listRecords(1, 10, null, null, null);

        assertTrue(result.isSuccess());
        assertEquals(2, result.getData().getRecords().size(),
                "管理端不传 userId 就是查全部 —— 所以用户端必须走另一个方法，"
                        + "否则传 null 就能看到所有人的开门记录");
    }

    @Test
    @DisplayName("管理端查询：按时间范围过滤时起点含、终点不含")
    void listRecords_filtersByHalfOpenWindow() {
        seedFor(USER_ID, yesterdayAt(10, 0, 0));
        seedFor(USER_ID, yesterdayAt(12, 0, 0));

        BizResult<PageResult<AccessRecordVo>> result = service.listRecords(
                1, 10, null, yesterdayAt(10, 0, 0), yesterdayAt(12, 0, 0));

        assertEquals(1, result.getData().getRecords().size(),
                "与同步口径一致：起点那条算在内，终点那条不算");
    }

    @Test
    @DisplayName("查询：列表按开门时刻倒序")
    void listRecords_ordersByOpenTimeDescending() {
        seedFor(USER_ID, yesterdayAt(10, 0, 0));
        seedFor(USER_ID, yesterdayAt(11, 0, 0));

        BizResult<PageResult<AccessRecordVo>> result = service.listRecords(1, 10, null, null, null);

        assertEquals(yesterdayAt(11, 0, 0), result.getData().getRecords().get(0).getOpenTime(),
                "最近的开门记录排在最前面");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 按指定实现方构造一个被测服务。
     *
     * @param provider 门锁实现方（{@code mock} / {@code ttlock}）
     * @return 服务实例
     */
    private AccessRecordService newService(String provider) {
        return new AccessRecordService(recordMapper.asMapper(), storeMapper.asMapper(),
                lockService.asService(), propertiesOf(provider));
    }

    /**
     * 构造门锁配置。
     *
     * @param provider 门锁实现方
     * @return 配置对象
     */
    private static LockProperties propertiesOf(String provider) {
        LockProperties properties = new LockProperties();
        properties.setProvider(provider);
        return properties;
    }

    /**
     * 直接往假 Mapper 里塞一条记录（绕过 Service，用于预置查询用的数据）。
     *
     * @param userId   开门人
     * @param openTime 开门时刻
     */
    private void seedFor(Long userId, LocalDateTime openTime) {
        AccessRecord record = new AccessRecord();
        record.setStoreId(STORE_ID);
        record.setLockId(LOCK_ID);
        record.setUserId(userId);
        record.setOpenTime(openTime);
        record.setSource(AccessSource.MOCK.name());
        recordMapper.seed(record);
    }

    /**
     * 构造一条门锁云返回的开门记录。
     *
     * @param openTime 开门时刻
     * @return 开门记录
     */
    private static LockRecordDto record(LocalDateTime openTime) {
        return new LockRecordDto(LOCK_ID, PASSCODE, openTime, 2, USER_ID);
    }

    /**
     * 构造一个固定的过去时刻（昨天某时某分某秒）。
     *
     * <p>用相对日期而不是写死某一天：补录用例要求开门时刻不在未来，
     * 写死日期的话测试在当天凌晨跑就会失败 —— 那种「看时间脸色」的用例
     * 会让人先怀疑测试而不是代码。
     *
     * @param hour   小时
     * @param minute 分钟
     * @param second 秒
     * @return 昨天该时刻
     */
    private static LocalDateTime yesterdayAt(int hour, int minute, int second) {
        return LocalDateTime.now().minusDays(1)
                .withHour(hour).withMinute(minute).withSecond(second).withNano(0);
    }
}
