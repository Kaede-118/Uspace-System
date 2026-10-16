package com.kaede.uspace.order;

import com.kaede.uspace.billing.BillingProperties;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.order.dto.InstoreSnapshotVo;
import com.kaede.uspace.order.dto.InstoreUserVo;
import com.kaede.uspace.order.dto.OrderTrafficEventVo;
import com.kaede.uspace.order.dto.OrderTrafficVo;
import com.kaede.uspace.order.entity.Order;
import com.kaede.uspace.promotion.FakeMonthlyCardMapper;
import com.kaede.uspace.promotion.FakeMonthlyCardOrderMapper;
import com.kaede.uspace.promotion.MonthlyCardService;
import com.kaede.uspace.promotion.MonthlyCardStatus;
import com.kaede.uspace.promotion.MonthlyCardType;
import com.kaede.uspace.promotion.PromotionProperties;
import com.kaede.uspace.promotion.entity.MonthlyCard;
import com.kaede.uspace.space.FakeStoreMapper;
import com.kaede.uspace.space.entity.Store;
import com.kaede.uspace.user.FakeSysUserMapper;
import com.kaede.uspace.user.entity.SysUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InstoreService} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring、不连数据库</b>：订单、门店、用户、月卡四张表
 * 各由一个假 Mapper 顶替，与模块 8、模块 9 既有测试用的是同一批假实现。
 *
 * <p>本类盯得最紧的两处，都是「写错了不会报错、只会给出一个错误答案」的类型：
 * <ol>
 *   <li><b>在店时长必须实时算，不能读 {@code stay_minutes} 列</b> ——
 *       那一列要结算时才写入，在店期间是 NULL。读列的实现只会拿到 null，
 *       而它在任何一条「刚开门」的用例里看起来都对</li>
 *   <li><b>月卡按订单的开始日期判定，不按「今天」</b> —— 与结算的免单判定同源。
 *       按今天判，跨零点的夜单会出现「名册说他有卡、结账却照收钱」</li>
 * </ol>
 */
class InstoreServiceTests {

    private static final Long USER_ID = 1001L;
    private static final Long OTHER_USER = 1002L;
    private static final Long THIRD_USER = 1003L;
    private static final Long STORE_ID = 1L;

    private final FakeOrderMapper orderMapper = new FakeOrderMapper();
    private final FakeStoreMapper storeMapper = new FakeStoreMapper();
    private final FakeSysUserMapper userMapper = new FakeSysUserMapper();

    /** 月卡的两张表。本模块只用得上卡表，购买单表是 {@code MonthlyCardService} 的构造需要 */
    private final FakeMonthlyCardMapper cardMapper = new FakeMonthlyCardMapper();
    private final FakeMonthlyCardOrderMapper cardOrderMapper = new FakeMonthlyCardOrderMapper();
    /**
     * 月卡服务。末位参数是用户表（后台开卡要用），本模块用不到 ——
     * {@code userMapper} 声明在上面，字段按声明顺序初始化，这里读得到
     */
    private final MonthlyCardService monthlyCardService = new MonthlyCardService(
            cardMapper.asMapper(), cardOrderMapper.asMapper(),
            new PromotionProperties(), new BillingProperties(), event -> { },
            userMapper.asMapper());

    private final InstoreService service = new InstoreService(
            orderMapper.asMapper(), storeMapper.asMapper(),
            userMapper.asMapper(), monthlyCardService);

    // ==================================================================
    // 门店与空名册
    // ==================================================================

    @Test
    @DisplayName("门店不存在时返回 STORE_NOT_FOUND，而不是一个空名册")
    void listInstoreUsers_failsWhenStoreMissing() {
        // 刻意不预置门店 —— 空名册会被前端读成「店里没人」，与「系统还没配好」是两回事
        BizResult<List<InstoreUserVo>> result = service.listInstoreUsers();

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.STORE_NOT_FOUND, result.getError());
    }

    @Test
    @DisplayName("店里没人时返回空数组 —— 那是一个正常状态，不是 404")
    void listInstoreUsers_returnsEmptyListWhenNobodyIsIn() {
        seedStore();

        BizResult<List<InstoreUserVo>> result = service.listInstoreUsers();

        assertTrue(result.isSuccess());
        assertTrue(result.getData().isEmpty());
    }

    // ==================================================================
    // 名册内容
    // ==================================================================

    @Test
    @DisplayName("名册：补齐昵称、头像、背景图与偏好 code")
    void listInstoreUsers_reportsProfileOfEachVisitor() {
        seedStore();
        seedUser(USER_ID, "小枫", "PAIPAI,TAISHOU");
        LocalDateTime start = minutesAgo(10);
        seedOrder(USER_ID, STORE_ID, OrderStatus.IN_USE, start);

        List<InstoreUserVo> list = service.listInstoreUsers().getData();

        assertEquals(1, list.size());
        InstoreUserVo vo = list.get(0);
        assertEquals(USER_ID, vo.getUserId());
        assertEquals("小枫", vo.getNickname());
        assertEquals("/uploads/avatar/1001.jpg", vo.getAvatar());
        assertEquals("/uploads/banner/1001.jpg", vo.getBanner());
        assertEquals("PAIPAI,TAISHOU", vo.getPreference(),
                "偏好只给 code，中文名由前端用 GET /api/devices/types 映射");
        assertEquals(start, vo.getStartTime());
    }

    @Test
    @DisplayName("名册：按进店时刻升序 —— 先来的排在前面")
    void listInstoreUsers_sortsByStartTime() {
        seedStore();
        // 故意乱序预置：先放玩得短的那条
        seedOrder(OTHER_USER, STORE_ID, OrderStatus.IN_USE, minutesAgo(30));
        seedOrder(USER_ID, STORE_ID, OrderStatus.IN_USE, minutesAgo(90));

        List<InstoreUserVo> list = service.listInstoreUsers().getData();

        assertEquals(2, list.size());
        assertEquals(USER_ID, list.get(0).getUserId(), "玩了 90 分钟的该排在玩了 30 分钟的前面");
        assertEquals(OTHER_USER, list.get(1).getUserId());
    }

    @Test
    @DisplayName("名册只含在店订单：已结算待支付的单不再算「在店里」")
    void listInstoreUsers_excludesSettledOrders() {
        seedStore();
        seedOrder(USER_ID, STORE_ID, OrderStatus.IN_USE, minutesAgo(30));
        seedOrder(OTHER_USER, STORE_ID, OrderStatus.PENDING_PAYMENT, minutesAgo(60));

        List<InstoreUserVo> list = service.listInstoreUsers().getData();

        assertEquals(1, list.size());
        assertEquals(USER_ID, list.get(0).getUserId(),
                "待支付的单已经结算、密码也撤了，放进名册会让别人以为他还在店里");
    }

    @Test
    @DisplayName("名册只含当前门店的订单")
    void listInstoreUsers_excludesOrdersOfOtherStores() {
        seedStore();
        seedOrder(USER_ID, STORE_ID, OrderStatus.IN_USE, minutesAgo(30));
        seedOrder(OTHER_USER, STORE_ID + 1, OrderStatus.IN_USE, minutesAgo(40));

        List<InstoreUserVo> list = service.listInstoreUsers().getData();

        assertEquals(1, list.size());
        assertEquals(USER_ID, list.get(0).getUserId(), "别家店的顾客不该出现在本店名册上");
    }

    @Test
    @DisplayName("用户记录查不到时仍留在名册上，只是资料为空")
    void listInstoreUsers_keepsRowWhenUserRecordIsGone() {
        seedStore();
        // 刻意不给这个 userId 预置用户 —— 模拟账号已被逻辑删除的情形
        seedOrder(USER_ID, STORE_ID, OrderStatus.IN_USE, minutesAgo(20));

        List<InstoreUserVo> list = service.listInstoreUsers().getData();

        assertEquals(1, list.size(), "人还坐在店里，不能因为账号没了就当他不存在");
        assertEquals(USER_ID, list.get(0).getUserId());
        assertNull(list.get(0).getNickname());
        assertEquals(20, list.get(0).getStayMinutes().intValue(), "资料缺失不影响时长照算");
    }

    // ==================================================================
    // 在店时长
    // ==================================================================

    @Test
    @DisplayName("在店时长：实时算出，订单上的 stay_minutes 列有值也不读")
    void listInstoreUsers_computesStayMinutesLiveInsteadOfReadingColumn() {
        seedStore();
        seedUser(USER_ID, "小枫", null);
        Order order = seedOrder(USER_ID, STORE_ID, OrderStatus.IN_USE, minutesAgo(72));
        // 在店期间这一列本该是 NULL。故意塞个脏值：读列的实现会立刻露馅，
        // 而它在「刚开门」那类用例里看起来完全正常
        order.setStayMinutes(999);

        assertEquals(72, service.listInstoreUsers().getData().get(0).getStayMinutes().intValue());
    }

    @Test
    @DisplayName("在店时长：秒级零头向下取整，与结算写下 stay_minutes 时的口径一致")
    void listInstoreUsers_truncatesStayMinutesToWholeMinutes() {
        seedStore();
        seedUser(USER_ID, "小枫", null);
        // 起点落在整分钟上，而「此刻」带着若干秒 —— 那几秒零头不该凑成第 6 分钟
        seedOrder(USER_ID, STORE_ID, OrderStatus.IN_USE, minutesAgo(5));

        assertEquals(5, service.listInstoreUsers().getData().get(0).getStayMinutes().intValue(),
                "不足一分钟的零头舍去，与 Duration.toMinutes() 的截断行为一致");
    }

    // ==================================================================
    // 月卡状态
    // ==================================================================

    @Test
    @DisplayName("月卡：全天卡给出类型与中文名，供前端展示「全天月卡 · 生效中」")
    void listInstoreUsers_reportsAllDayCard() {
        seedStore();
        seedUser(USER_ID, "小枫", null);
        seedOrder(USER_ID, STORE_ID, OrderStatus.IN_USE, minutesAgo(30));
        LocalDate today = LocalDate.now();
        seedCard(USER_ID, MonthlyCardType.ALL_DAY, today.minusDays(5), today.plusDays(24));

        InstoreUserVo vo = service.listInstoreUsers().getData().get(0);

        assertEquals(MonthlyCardType.ALL_DAY.name(), vo.getCardType());
        assertEquals("全天月卡", vo.getCardTypeLabel());
    }

    @Test
    @DisplayName("月卡：夜间卡与全天卡要分得开 —— 两者覆盖的时段不同")
    void listInstoreUsers_reportsNightCard() {
        seedStore();
        seedUser(USER_ID, "小枫", null);
        seedOrder(USER_ID, STORE_ID, OrderStatus.IN_USE, minutesAgo(30));
        LocalDate today = LocalDate.now();
        seedCard(USER_ID, MonthlyCardType.NIGHT, today.minusDays(5), today.plusDays(24));

        InstoreUserVo vo = service.listInstoreUsers().getData().get(0);

        assertEquals(MonthlyCardType.NIGHT.name(), vo.getCardType());
        assertEquals("夜间月卡", vo.getCardTypeLabel());
    }

    @Test
    @DisplayName("月卡：没卡时两个字段都是 null，前端据此不显示卡标")
    void listInstoreUsers_reportsNoCardWhenUserHasNone() {
        seedStore();
        seedUser(USER_ID, "小枫", null);
        seedOrder(USER_ID, STORE_ID, OrderStatus.IN_USE, minutesAgo(30));

        InstoreUserVo vo = service.listInstoreUsers().getData().get(0);

        assertNull(vo.getCardType());
        assertNull(vo.getCardTypeLabel());
    }

    @Test
    @DisplayName("月卡：已过期的卡不再显示")
    void listInstoreUsers_ignoresExpiredCard() {
        seedStore();
        seedUser(USER_ID, "小枫", null);
        seedOrder(USER_ID, STORE_ID, OrderStatus.IN_USE, minutesAgo(30));
        LocalDate today = LocalDate.now();
        seedCard(USER_ID, MonthlyCardType.ALL_DAY, today.minusDays(40), today.minusDays(10));

        InstoreUserVo vo = service.listInstoreUsers().getData().get(0);

        assertNull(vo.getCardType(), "过期卡不该继续显示为生效中");
        assertNull(vo.getCardTypeLabel());
    }

    @Test
    @DisplayName("月卡：按订单的开始日期判定，不按「今天」—— 与结算的免单口径同源")
    void listInstoreUsers_judgesCardByOrderStartDateNotToday() {
        seedStore();
        seedUser(USER_ID, "小枫", null);

        // 卡在【昨天】到期（end_date 含当日），而这一单是昨天 23:00 开的：
        // 按订单开始日期判 —— 有卡（结算时这一单确实免单）；
        // 按今天判 —— 无卡。两个答案只有一个与账单对得上
        LocalDate yesterday = LocalDate.now().minusDays(1);
        seedOrder(USER_ID, STORE_ID, OrderStatus.IN_USE, yesterday.atTime(23, 0));
        seedCard(USER_ID, MonthlyCardType.ALL_DAY, yesterday.minusDays(29), yesterday);

        InstoreUserVo vo = service.listInstoreUsers().getData().get(0);

        assertEquals(MonthlyCardType.ALL_DAY.name(), vo.getCardType(),
                "名册若按今天判，跨零点的夜单会出现「名册说他有卡、结账却照收钱」");
        assertEquals("全天月卡", vo.getCardTypeLabel());
    }

    // ==================================================================
    // 测试数据构造
    // ==================================================================

    /**
     * 造一个落在整分钟上的「若干分钟前」，用作订单的开门时刻。
     *
     * <p><b>必须落在整分钟上</b>：这样「此刻」与起点之间的差值是「整数分钟 + 若干秒」，
     * 而那些秒数不足一分钟、必然被 {@code toMinutes()} 舍去 ——
     * 断言才能写成一个确定的整数。若起点带着秒数，用例会在某些秒数下算出差一分钟的结果。
     *
     * @param minutes 距今多少分钟
     * @return 开门时刻
     */
    private static LocalDateTime minutesAgo(int minutes) {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).minusMinutes(minutes);
    }

    /**
     * 预置当前门店。单门店运营下 {@code selectCurrent} 取的是 ID 最小的那条。
     *
     * @return 门店
     */
    private Store seedStore() {
        Store store = new Store();
        store.setId(STORE_ID);
        store.setName("测试门店");
        return storeMapper.seed(store);
    }

    /**
     * 预置一个用户，带齐名册要展示的那几个字段。
     *
     * @param id         用户 ID
     * @param nickname   昵称
     * @param preference 偏好 code，可为 null
     * @return 用户
     */
    private SysUser seedUser(Long id, String nickname, String preference) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername("u" + id);
        user.setNickname(nickname);
        user.setPreference(preference);
        user.setAvatar("/uploads/avatar/" + id + ".jpg");
        user.setBanner("/uploads/banner/" + id + ".jpg");
        return userMapper.seed(user);
    }

    /**
     * 预置一条订单。
     *
     * @param userId   下单用户
     * @param storeId  所属门店
     * @param status   订单状态
     * @param start    开门时刻
     * @return 订单
     */
    private Order seedOrder(Long userId, Long storeId, OrderStatus status, LocalDateTime start) {
        Order order = new Order();
        order.setOrderNo("OD" + System.nanoTime());
        order.setUserId(userId);
        order.setStoreId(storeId);
        order.setStartTime(start);
        order.setStatus(status.name());
        return orderMapper.seed(order);
    }

    /**
     * 预置一张生效中的月卡。
     *
     * @param userId    持卡人
     * @param type      卡种
     * @param startDate 生效日期
     * @param endDate   失效日期（含当日）
     * @return 月卡
     */
    private MonthlyCard seedCard(Long userId, MonthlyCardType type,
                                 LocalDate startDate, LocalDate endDate) {
        MonthlyCard card = new MonthlyCard();
        card.setUserId(userId);
        card.setCardType(type.name());
        card.setStartDate(startDate);
        card.setEndDate(endDate);
        card.setStatus(MonthlyCardStatus.ACTIVE.name());
        return cardMapper.seed(card);
    }

    // ==================================================================
    // 进出记录与名册快照（2026-10-16 新增）
    //
    // 口径由用户定死：到店 = 开始计时，离店 = 停止计时。本组用例盯的是
    // 「半开区间」与「名册要向窗口外取人」这两处 —— 它们写错了都不会报错，
    // 只会让复盘时看到的名册少一个人或多一个人。
    // ==================================================================

    @Test
    @DisplayName("进出记录：到店那一刻的名册含本人，离店之后的名册不含刚走的那位")
    void listTraffic_reportsSnapshotsOnBothEnds() {
        seedStore();
        seedUser(USER_ID, "小枫", null);
        seedUser(OTHER_USER, "阿岚", null);
        seedUser(THIRD_USER, "小满", null);

        LocalDateTime base = minutesAgo(180);
        // 甲：一直在店；乙：中途离店；丙：最后到
        seedOrder(USER_ID, STORE_ID, OrderStatus.IN_USE, base);
        seedOrder(OTHER_USER, STORE_ID, OrderStatus.PAID, base.plusMinutes(30), base.plusMinutes(120));
        seedOrder(THIRD_USER, STORE_ID, OrderStatus.IN_USE, base.plusMinutes(60));

        PageResult<OrderTrafficVo> page =
                service.listTraffic(1, 10, OTHER_USER, null, null, false).getData();
        OrderTrafficVo vo = page.getRecords().get(0);

        assertEquals(base.plusMinutes(30), vo.getArriveTime());
        assertEquals(base.plusMinutes(120), vo.getLeaveTime());

        // 到店那一刻：甲 + 乙自己（丙还没来）
        assertEquals(2, vo.getInstoreOnArrive().getCount());
        assertEquals(List.of("小枫", "阿岚"), vo.getInstoreOnArrive().getNames());

        // 离店之后：甲 + 丙；乙自己不算 —— 这就是「剩余」两个字的半开语义
        assertEquals(2, vo.getInstoreAfterLeave().getCount());
        assertEquals(List.of("小枫", "小满"), vo.getInstoreAfterLeave().getNames());
    }

    @Test
    @DisplayName("仍在店的那条单子没有「离店后」快照，而不是给一份空名册")
    void listTraffic_leaveSnapshotIsNullWhileStillInStore() {
        seedStore();
        seedUser(USER_ID, "小枫", null);
        seedOrder(USER_ID, STORE_ID, OrderStatus.IN_USE, minutesAgo(30));

        OrderTrafficVo vo =
                service.listTraffic(1, 10, null, null, null, false).getData().getRecords().get(0);

        assertNull(vo.getLeaveTime());
        assertNull(vo.getInstoreAfterLeave());
        assertTrue(vo.getInstoreOnArrive() != null);
        assertEquals(List.of("小枫"), vo.getInstoreOnArrive().getNames());
    }

    @Test
    @DisplayName("名册要跨窗口取人：比本页任何时刻都早到店、且还没走的人必须在场")
    void listTraffic_keepsCrossWindowVisitorInSnapshot() {
        seedStore();
        seedUser(USER_ID, "小枫", null);
        seedUser(OTHER_USER, "阿岚", null);

        LocalDateTime base = minutesAgo(300);
        seedOrder(USER_ID, STORE_ID, OrderStatus.IN_USE, base);
        seedOrder(OTHER_USER, STORE_ID, OrderStatus.PAID, base.plusMinutes(60), base.plusMinutes(90));

        OrderTrafficVo vo =
                service.listTraffic(1, 10, OTHER_USER, null, null, false).getData().getRecords().get(0);

        // 只查「开始时刻落在窗口内」的实现会把甲漏掉，而它不报任何错
        assertEquals(List.of("小枫", "阿岚"), vo.getInstoreOnArrive().getNames());
    }

    @Test
    @DisplayName("还原任意时刻：报出当时的在店名册；恰好离店那一刻已不算在店")
    void snapshotAt_restoresWhoWasInStore() {
        seedStore();
        seedUser(USER_ID, "小枫", null);
        seedUser(OTHER_USER, "阿岚", null);

        LocalDateTime base = minutesAgo(240);
        seedOrder(USER_ID, STORE_ID, OrderStatus.IN_USE, base);
        seedOrder(OTHER_USER, STORE_ID, OrderStatus.PAID, base.plusMinutes(30), base.plusMinutes(90));

        InstoreSnapshotVo both = service.snapshotAt(base.plusMinutes(60)).getData();
        assertEquals(2, both.getCount());
        assertEquals(base.plusMinutes(60), both.getAt());

        // 乙的离店时刻正好是这个点：半开区间把他排除在外，名册上只剩甲
        InstoreSnapshotVo afterLeave = service.snapshotAt(base.plusMinutes(90)).getData();
        assertEquals(List.of("小枫"), afterLeave.getNames());
    }

    @Test
    @DisplayName("按离店视图：仍在店的单子不出现，筛选看的是离店时刻")
    void listTraffic_byLeaveViewFiltersOnLeaveTime() {
        seedStore();
        seedUser(USER_ID, "小枫", null);
        seedUser(OTHER_USER, "阿岚", null);

        LocalDateTime base = minutesAgo(240);
        seedOrder(USER_ID, STORE_ID, OrderStatus.IN_USE, base);
        seedOrder(OTHER_USER, STORE_ID, OrderStatus.PAID, base, base.plusMinutes(60));

        PageResult<OrderTrafficVo> page =
                service.listTraffic(1, 10, null, null, null, true).getData();

        assertEquals(1, page.getTotal());
        assertEquals(OTHER_USER, page.getRecords().get(0).getUserId());
    }

    @Test
    @DisplayName("门店不存在时返回 STORE_NOT_FOUND，而不是一页空记录")
    void listTraffic_failsWhenStoreMissing() {
        assertFalse(service.listTraffic(1, 10, null, null, null, false).isSuccess());
        assertFalse(service.snapshotAt(minutesAgo(10)).isSuccess());
    }

    /**
     * 预置一条<b>已经离店</b>的订单。
     *
     * <p>先按 {@link #seedOrder(Long, Long, OrderStatus, LocalDateTime)} 落库再补
     * {@code endTime} —— 假 Mapper 的 {@code seed} 存的是对象引用，补写对后续查询可见。
     *
     * @param userId  下单用户
     * @param storeId 所属门店
     * @param status  订单状态
     * @param start   到店时刻
     * @param end     离店时刻
     * @return 订单
     */
    private Order seedOrder(Long userId, Long storeId, OrderStatus status,
                            LocalDateTime start, LocalDateTime end) {
        Order order = seedOrder(userId, storeId, status, start);
        order.setEndTime(end);
        return order;
    }

    // ==================================================================
    // 进出事件时间线（2026-10-16 按使用者反馈加：事件粒度，不是订单粒度）
    //
    // 「一行一单、两端并排」的读法被使用者判为意义不明 —— 一屏扫过去看不出
    // 刚才谁走了。改成一行一件事之后，下面这几条是它的守门用例。
    // ==================================================================

    @Test
    @DisplayName("时间线：一条订单出两条事件（到店、离店各一条），按时刻倒序")
    void listTimeline_splitsAnOrderIntoTwoEvents() {
        seedStore();
        seedUser(USER_ID, "小枫", null);
        LocalDateTime base = minutesAgo(120);
        seedOrder(USER_ID, STORE_ID, OrderStatus.PAID, base, base.plusMinutes(60));

        List<OrderTrafficEventVo> events =
                service.listTimeline(1, 10, null, null, null).getData().getRecords();

        assertEquals(2, events.size());
        // 倒序：离店更晚，所以排在上面
        assertEquals("LEAVE", events.get(0).getEventType());
        assertEquals("离店", events.get(0).getEventLabel());
        assertEquals(base.plusMinutes(60), events.get(0).getEventTime());
        assertEquals("ARRIVE", events.get(1).getEventType());
        assertEquals("到店", events.get(1).getEventLabel());
        assertEquals(base, events.get(1).getEventTime());
    }

    @Test
    @DisplayName("时间线：离店那张卡片报的是「他走后还剩谁」，不含他自己")
    void listTimeline_leaveEventReportsRemaining() {
        seedStore();
        seedUser(USER_ID, "小枫", null);
        seedUser(OTHER_USER, "阿岚", null);
        LocalDateTime base = minutesAgo(150);
        seedOrder(USER_ID, STORE_ID, OrderStatus.IN_USE, base);
        seedOrder(OTHER_USER, STORE_ID, OrderStatus.PAID, base.plusMinutes(30), base.plusMinutes(90));

        List<OrderTrafficEventVo> events =
                service.listTimeline(1, 10, null, null, null).getData().getRecords();

        // 最新那条是阿岚离店：名册里只剩小枫
        OrderTrafficEventVo leave = events.get(0);
        assertEquals("LEAVE", leave.getEventType());
        assertEquals("阿岚", leave.getNickname());
        assertEquals(List.of("小枫"), leave.getInstore().getNames());

        // 他到店那条则含他自己（同一份推算逻辑，两个方向都要对）
        OrderTrafficEventVo arrive = events.stream()
                .filter(event -> "ARRIVE".equals(event.getEventType())
                        && OTHER_USER.equals(event.getUserId()))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("小枫", "阿岚"), arrive.getInstore().getNames());
    }

    @Test
    @DisplayName("时间线：0 秒单的两条事件都在，且昵称没丢")
    void listTimeline_keepsZeroSecondOrderNickname() {
        seedStore();
        seedUser(USER_ID, "小枫", null);
        LocalDateTime base = minutesAgo(60);
        // 到店与离店同刻：end_time > 窗口起点 不成立，它不在窗口相交的结果里 ——
        // 昵称因此得靠「事件行本人」那条补查路径拿到，否则会回落成「用户{ID}」
        seedOrder(USER_ID, STORE_ID, OrderStatus.PAID, base, base);

        List<OrderTrafficEventVo> events =
                service.listTimeline(1, 10, null, null, null).getData().getRecords();

        assertEquals(2, events.size());
        assertTrue(events.stream().allMatch(event -> "小枫".equals(event.getNickname())));
    }

    @Test
    @DisplayName("时间线：筛的是事件时刻 —— 选「今天」能看到今天发生的到店与离店")
    void listTimeline_filtersByEventTime() {
        seedStore();
        seedUser(USER_ID, "小枫", null);
        LocalDateTime old = minutesAgo(600);
        LocalDateTime recent = minutesAgo(30);
        seedOrder(USER_ID, STORE_ID, OrderStatus.PAID, old, old.plusMinutes(30));
        seedOrder(USER_ID, STORE_ID, OrderStatus.IN_USE, recent);

        // 下界取 recent 之前一分钟：老那一单的到店与离店都比它早，被筛掉
        List<OrderTrafficEventVo> events = service
                .listTimeline(1, 10, null, recent.minusMinutes(1), null)
                .getData().getRecords();

        assertEquals(1, events.size());
        assertEquals("ARRIVE", events.get(0).getEventType());
        assertEquals(recent, events.get(0).getEventTime());
    }
}
