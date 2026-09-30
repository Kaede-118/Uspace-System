package com.kaede.uspace.order;

import com.kaede.uspace.billing.BillingProperties;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.order.dto.InstoreUserVo;
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
    private static final Long STORE_ID = 1L;

    private final FakeOrderMapper orderMapper = new FakeOrderMapper();
    private final FakeStoreMapper storeMapper = new FakeStoreMapper();
    private final FakeSysUserMapper userMapper = new FakeSysUserMapper();

    /** 月卡的两张表。本模块只用得上卡表，购买单表是 {@code MonthlyCardService} 的构造需要 */
    private final FakeMonthlyCardMapper cardMapper = new FakeMonthlyCardMapper();
    private final FakeMonthlyCardOrderMapper cardOrderMapper = new FakeMonthlyCardOrderMapper();
    private final MonthlyCardService monthlyCardService = new MonthlyCardService(
            cardMapper.asMapper(), cardOrderMapper.asMapper(),
            new PromotionProperties(), new BillingProperties());

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
}
