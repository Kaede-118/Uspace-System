package com.kaede.uspace.order.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaede.uspace.order.OrderStatus;
import com.kaede.uspace.order.entity.Order;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OrderMapper} 的集成测试，连本机真实 MySQL。
 *
 * <p><b>三重保护不污染开发库</b>：{@code @Transactional} 让每个用例结束后自动回滚；
 * {@code @EnabledIfEnvironmentVariable} 让没配 {@code MYSQL_PASSWORD} 的机器整个跳过
 * 而不是报错失败；ID 取 999xxx 这类可辨认的大数，万一回滚失效也一眼能认出来。
 *
 * <p><b>为什么假 Mapper 不够、必须连真库</b>：假实现模拟的是数据库行为的<b>语义</b>，
 * 却绕过了真实 SQL —— 列名映射写错、新增的列没被映射、{@code deleted = 0} 写错表、
 * 聚合函数的空结果返回 null 而不是 0，这些问题在假 Mapper 面前统统不会暴露。
 * 本模块新加的 {@code booking_id} 列与月累计聚合 SQL 尤其如此：
 * 前者是新列，后者是唯一一条聚合查询，都只有跑起来才能验证。
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+",
        disabledReason = "未配置 MYSQL_PASSWORD，跳过需要真实数据库的集成测试")
class OrderMapperIntegrationTests {

    /** 测试用的用户 ID。取大数以免与真实数据混淆 */
    private static final Long USER_ID = 999801L;
    private static final Long STORE_ID = 999802L;
    private static final Long LOCK_ID = 999803L;
    private static final Long BOOKING_ID = 999804L;

    @Autowired
    private OrderMapper orderMapper;

    /** 比较账单快照用 —— MySQL 的 JSON 列会规范化存储，只能按 JSON 语义比 */
    @Autowired
    private ObjectMapper objectMapper;

    // ==================================================================
    // 落库与映射
    // ==================================================================

    @Test
    @DisplayName("插入时自动填充审计字段并回填主键")
    void insert_fillsAuditFieldsAndId() {
        Order order = newOrder(OrderStatus.IN_USE);

        int affected = orderMapper.insert(order);

        assertEquals(1, affected, "插入一行");
        assertNotNull(order.getId(), "主键应当由数据库自增填回实体");
        assertNotNull(order.getCreatedAt(), "created_at 是 NOT NULL 且无默认值，靠自动填充补上");
        assertNotNull(order.getUpdatedAt(), "updated_at 同理");
    }

    @Test
    @DisplayName("新增的 booking_id 列能正确落库与读出")
    void insert_mapsBookingIdColumn() {
        Order order = newOrder(OrderStatus.IN_USE);
        order.setBookingId(BOOKING_ID);

        orderMapper.insert(order);
        Order loaded = orderMapper.selectById(order.getId());

        assertEquals(BOOKING_ID, loaded.getBookingId(),
                "这一列是模块 8 新加的，菜名映射写错或忘了加字段都不会报错，只有查回来才知道");
    }

    @Test
    @DisplayName("逻辑删除后按 ID 查不到")
    void delete_filtersByLogicDelete() {
        Order order = newOrder(OrderStatus.PAID);
        orderMapper.insert(order);

        orderMapper.deleteById(order.getId());

        assertNull(orderMapper.selectById(order.getId()), "全局逻辑删除配置应当让查询自动过滤");
    }

    @Test
    @DisplayName("订单号唯一索引挡住重复插入")
    void insert_rejectsDuplicateOrderNo() {
        Order first = newOrder(OrderStatus.IN_USE);
        orderMapper.insert(first);

        Order second = newOrder(OrderStatus.IN_USE);
        second.setOrderNo(first.getOrderNo());

        assertThrows(DuplicateKeyException.class, () -> orderMapper.insert(second),
                "唯一索引是单号不重复的最终防线 —— 应用层查重挡不住并发");
    }

    // ==================================================================
    // 月度累计消费（优惠门槛的判定依据）
    // ==================================================================

    @Test
    @DisplayName("月累计：只统计已支付的订单")
    void monthPaidAmount_countsOnlyPaid() {
        LocalDateTime start = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        orderMapper.insert(paidOrder(start.plusHours(1), "100.00"));
        orderMapper.insert(orderOf(start.plusHours(2), OrderStatus.PENDING_PAYMENT, "50.00"));
        orderMapper.insert(orderOf(start.plusHours(3), OrderStatus.IN_USE, "30.00"));

        BigDecimal amount = orderMapper.selectMonthPaidAmount(USER_ID, start, start.plusMonths(1));

        assertEquals(0, new BigDecimal("100.00").compareTo(amount),
                "欠着费不算消费 —— 否则用户可以靠不付款堆高累计，白拿优惠");
    }

    @Test
    @DisplayName("月累计：按离场时刻归集，不按支付时刻")
    void monthPaidAmount_groupsByEndTimeNotPaidAt() {
        LocalDateTime thisMonth = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        LocalDateTime lastMonth = thisMonth.minusMonths(1);

        // 上个月离场的单，但它是这个月才付的款
        Order lastMonthOrder = orderOf(lastMonth.plusDays(14), OrderStatus.PAID, "200.00");
        lastMonthOrder.setPaidAt(thisMonth.plusDays(1));
        orderMapper.insert(lastMonthOrder);

        BigDecimal thisMonthAmount = orderMapper.selectMonthPaidAmount(
                USER_ID, thisMonth, thisMonth.plusMonths(1));
        BigDecimal lastMonthAmount = orderMapper.selectMonthPaidAmount(
                USER_ID, lastMonth, thisMonth);

        assertEquals(0, BigDecimal.ZERO.compareTo(thisMonthAmount),
                "按 end_time 归集 —— 用 paid_at 的话，用户拖几天付款就把归月拖走了");
        assertEquals(0, new BigDecimal("200.00").compareTo(lastMonthAmount),
                "它应当被算在上个月");
    }

    @Test
    @DisplayName("月累计：月底进店、次日凌晨离店的夜单计入下个月")
    void monthPaidAmount_crossMonthOrderCountsToNextMonth() {
        LocalDateTime thisMonth = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        LocalDateTime lastMonth = thisMonth.minusMonths(1);
        LocalDateTime nextMonth = thisMonth.plusMonths(1);

        // 上月最后一天 23:30 进店、本月 1 日 00:30 离店 —— 跨零点的那一笔
        Order crossMonth = orderOf(thisMonth.minusMinutes(30), OrderStatus.PAID, "88.00");
        crossMonth.setEndTime(thisMonth.plusMinutes(30));
        crossMonth.setStayMinutes(60);
        orderMapper.insert(crossMonth);

        assertEquals(0, BigDecimal.ZERO.compareTo(
                        orderMapper.selectMonthPaidAmount(USER_ID, lastMonth, thisMonth)),
                "它不该再算进上个月 —— 月初凌晨的消费留在上个月，正是这条口径要修掉的现象");
        assertEquals(0, new BigDecimal("88.00").compareTo(
                        orderMapper.selectMonthPaidAmount(USER_ID, thisMonth, nextMonth)),
                "整笔按离场月归集");
        assertEquals(60L, orderMapper.selectMonthStayMinutes(
                        USER_ID, thisMonth, nextMonth).longValue(),
                "在店时长跟着同一个归月字段走，否则跨月那一刻会出现"
                        + "「消费算本月、时长算上月」的错位");
    }

    @Test
    @DisplayName("月累计：区间是半开的，月末最后一刻属于本月")
    void monthPaidAmount_usesHalfOpenRange() {
        LocalDateTime thisMonth = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        LocalDateTime nextMonth = thisMonth.plusMonths(1);

        // 离场时刻紧挨着下月起点之前一秒 —— 半开区间下仍属于本月
        Order edge = paidOrder(nextMonth.minusHours(2), "66.00");
        edge.setEndTime(nextMonth.minusSeconds(1));
        orderMapper.insert(edge);
        // 离场时刻恰好落在下月起点 —— 半开区间下属于下月
        Order boundary = paidOrder(nextMonth.minusHours(2), "77.00");
        boundary.setEndTime(nextMonth);
        orderMapper.insert(boundary);

        BigDecimal thisMonthAmount = orderMapper.selectMonthPaidAmount(USER_ID, thisMonth, nextMonth);

        assertEquals(0, new BigDecimal("66.00").compareTo(thisMonthAmount),
                "半开区间 [from, to)：下月起点那一秒不算本月 —— "
                        + "写成闭区间就会把两边的单都算进来");
    }

    @Test
    @DisplayName("月累计：没有任何记录时返回 0 而不是 null")
    void monthPaidAmount_returnsZeroWhenEmpty() {
        LocalDateTime thisMonth = LocalDate.now().withDayOfMonth(1).atStartOfDay();

        BigDecimal amount = orderMapper.selectMonthPaidAmount(
                USER_ID + 1, thisMonth, thisMonth.plusMonths(1));

        assertEquals(0, BigDecimal.ZERO.compareTo(amount),
                "COALESCE 让空结果返回 0；返回 null 会让调用方在比较时抛 NPE");
    }

    @Test
    @DisplayName("月累计：被逻辑删除的订单不计入")
    void monthPaidAmount_excludesDeleted() {
        LocalDateTime start = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        Order order = paidOrder(start.plusHours(1), "88.00");
        orderMapper.insert(order);
        orderMapper.deleteById(order.getId());

        BigDecimal amount = orderMapper.selectMonthPaidAmount(USER_ID, start, start.plusMonths(1));

        assertEquals(0, BigDecimal.ZERO.compareTo(amount),
                "手写 SQL 必须自己带 deleted = 0 —— 全局配置管不到注解里的 SQL");
    }

    // ==================================================================
    // 在店时长统计（「我的」页的累计 / 本月时长）
    // ==================================================================

    @Test
    @DisplayName("时长统计：只算已支付，且与计费时长各算各的")
    void stayMinutes_countsOnlyPaid() {
        LocalDateTime start = LocalDate.now().withDayOfMonth(1).atStartOfDay();

        // 在店 150 分钟、但计费时长为 0 —— 包场单的样子。若 SQL 误用了
        // day_minutes + night_minutes，这里会算成 0 而不报任何错
        Order paid = paidOrder(start.plusHours(1), "100.00");
        paid.setStayMinutes(150);
        paid.setDayMinutes(0);
        orderMapper.insert(paid);

        Order pending = orderOf(start.plusHours(2), OrderStatus.PENDING_PAYMENT, "50.00");
        pending.setStayMinutes(999);
        orderMapper.insert(pending);

        assertEquals(150L, orderMapper.selectTotalStayMinutes(USER_ID).longValue(),
                "欠着费的那一单不算 —— 计进去会让「我的」页的累计时长每刷新一次就跳一次");
    }

    @Test
    @DisplayName("时长统计：归月口径与月累计消费逐字一致（按 end_time 的半开区间）")
    void stayMinutes_groupsByEndTimeLikeMonthPaidAmount() {
        LocalDateTime thisMonth = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        LocalDateTime lastMonth = thisMonth.minusMonths(1);
        LocalDateTime nextMonth = thisMonth.plusMonths(1);

        Order last = paidOrder(lastMonth.plusDays(14), "200.00");
        last.setStayMinutes(60);
        orderMapper.insert(last);

        // 离场时刻紧挨着下月起点之前一秒 —— 半开区间下仍属于本月
        Order edge = paidOrder(nextMonth.minusHours(2), "66.00");
        edge.setEndTime(nextMonth.minusSeconds(1));
        edge.setStayMinutes(30);
        orderMapper.insert(edge);

        assertEquals(30L, orderMapper.selectMonthStayMinutes(
                        USER_ID, thisMonth, nextMonth).longValue(),
                "两个数字在「我的」页并排显示，归月口径必须与 selectMonthPaidAmount 一致 —— "
                        + "一个按 start_time、另一个按 end_time 的话，跨月那一刻就错位了");
        assertEquals(60L, orderMapper.selectMonthStayMinutes(
                        USER_ID, lastMonth, thisMonth).longValue(),
                "上个月那单应当算在上个月");
        assertEquals(90L, orderMapper.selectTotalStayMinutes(USER_ID).longValue(),
                "累计是全部历史，不受月份区间影响");
    }

    @Test
    @DisplayName("时长统计：没有任何记录时返回 0 而不是 null")
    void stayMinutes_returnsZeroWhenEmpty() {
        LocalDateTime start = LocalDate.now().withDayOfMonth(1).atStartOfDay();

        assertEquals(0L, orderMapper.selectTotalStayMinutes(USER_ID + 1).longValue(),
                "COALESCE 让空结果返回 0；返回 null 会让调用方在累加时抛 NPE");
        assertEquals(0L, orderMapper.selectMonthStayMinutes(
                        USER_ID + 1, start, start.plusMonths(1)).longValue(),
                "本月同理");
    }

    // ==================================================================
    // 状态守卫
    // ==================================================================

    @Test
    @DisplayName("标记已支付：只对「待支付」的订单生效")
    void markPaid_respectsStatusGuard() {
        Order pending = orderOf(LocalDateTime.now().minusHours(2), OrderStatus.PENDING_PAYMENT, "22.00");
        orderMapper.insert(pending);

        int affected = orderMapper.markPaid(pending.getId(), "WXPAY_H5", "TX-1",
                LocalDateTime.now(), null);
        assertEquals(1, affected, "待支付的订单应当能被标记");

        // 再标记一次 —— 模拟回调重推
        int again = orderMapper.markPaid(pending.getId(), "WXPAY_H5", "TX-1",
                LocalDateTime.now(), null);
        assertEquals(0, again,
                "第二次拿到 0 行 —— 这正是回调幂等的关键一道，"
                        + "调用方据此跳过累加用户消费额那一步");

        Order loaded = orderMapper.selectById(pending.getId());
        assertEquals(OrderStatus.PAID.name(), loaded.getStatus(), "状态已流转");
        assertNull(loaded.getConfirmedBy(), "线上回调是系统自动确认，核销人留空");
    }

    @Test
    @DisplayName("写入结算结果：只对「使用中」的订单生效")
    void updateSettlement_respectsStatusGuard() {
        Order inUse = newOrder(OrderStatus.IN_USE);
        orderMapper.insert(inUse);

        int affected = orderMapper.updateSettlement(inUse.getId(), LocalDateTime.now(), 120,
                120, new BigDecimal("16.00"), 0, BigDecimal.ZERO,
                new BigDecimal("16.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("16.00"), OrderStatus.PENDING_PAYMENT.name(), null);
        assertEquals(1, affected, "使用中的订单应当能被结算");

        // 再结算一次 —— 模拟用户重复点击
        int again = orderMapper.updateSettlement(inUse.getId(), LocalDateTime.now(), 120,
                120, new BigDecimal("16.00"), 0, BigDecimal.ZERO,
                new BigDecimal("16.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("16.00"), OrderStatus.PENDING_PAYMENT.name(), null);
        assertEquals(0, again, "第二次应当拿到 0 行，而不是把账单重算一遍");
    }

    @Test
    @DisplayName("账单快照：JSON 列原样往返，分段明细读回来不走样")
    void updateSettlement_writesBillSnapshot() throws Exception {
        Order inUse = newOrder(OrderStatus.IN_USE);
        orderMapper.insert(inUse);

        // 与 OrderService 真正写进去的形状一致：枚举名 + 带格式的时间戳
        String snapshot = """
                {"bill":{"totalMinutes":120,"segments":[{"period":"DAY","units":4,
                "startTime":"2026-10-01 10:00:00"}],"discounted":false},"freeByBooking":false}""";

        orderMapper.updateSettlement(inUse.getId(), LocalDateTime.now(), 120,
                120, new BigDecimal("16.00"), 0, BigDecimal.ZERO,
                new BigDecimal("16.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("16.00"), OrderStatus.PENDING_PAYMENT.name(), snapshot);

        Order loaded = orderMapper.selectById(inUse.getId());
        assertNotNull(loaded.getBillSnapshot(),
                "快照写不进去时接口报的是「成功」、列上却是 null —— 只有查回来才知道");
        // ⚠️ 逐字比较不行：MySQL 的 JSON 列会规范化存储（键排序、补空格），
        //    所以按 JSON 语义比较
        assertEquals(objectMapper.readTree(snapshot), objectMapper.readTree(loaded.getBillSnapshot()),
                "分段明细是订单详情展示的唯一数据源，往返丢一个字段页面上就少一块");
    }

    @Test
    @DisplayName("在店时长：新列能落库并读出，且与计费时长各记各的")
    void updateSettlement_writesStayMinutesSeparately() {
        Order inUse = newOrder(OrderStatus.IN_USE);
        orderMapper.insert(inUse);

        // 结算为「在店 180 分钟、但计费只有 0 分钟」—— 这正是包场单的样子：
        // 一行代码算错就会让这一列悄悄变成 null 或与计费时长混同，而不会报任何错
        orderMapper.updateSettlement(inUse.getId(), LocalDateTime.now(), 180,
                0, BigDecimal.ZERO, 0, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                OrderStatus.PAID.name(), null);

        Order loaded = orderMapper.selectById(inUse.getId());
        assertEquals(180, loaded.getStayMinutes(),
                "新列是模块 8 后加的，列名映射写错或忘了加字段都不会报错，只有查回来才知道");
        assertEquals(0, loaded.getDayMinutes(), "计费时长照旧，两个口径不能互相覆盖");
    }

    @Test
    @DisplayName("人工调整：未付款的两种状态都可以，已支付的不行")
    void updateAdjustment_respectsStatusGuard() {
        Order pending = orderOf(LocalDateTime.now().minusHours(3), OrderStatus.PENDING_PAYMENT, "22.00");
        orderMapper.insert(pending);
        Order paid = orderOf(LocalDateTime.now().minusHours(3), OrderStatus.PAID, "22.00");
        orderMapper.insert(paid);

        int adjusted = orderMapper.updateAdjustment(pending.getId(),
                LocalDateTime.now().minusHours(1), 90, 90, new BigDecimal("12.00"),
                0, BigDecimal.ZERO, new BigDecimal("12.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("12.00"),
                OrderStatus.PENDING_PAYMENT.name(), 9L, "监控核实已离场", null);
        assertEquals(1, adjusted, "待支付的订单可以调整");

        int rejected = orderMapper.updateAdjustment(paid.getId(),
                LocalDateTime.now().minusHours(1), 90, 90, new BigDecimal("12.00"),
                0, BigDecimal.ZERO, new BigDecimal("12.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("12.00"),
                OrderStatus.PENDING_PAYMENT.name(), 9L, "监控核实已离场", null);
        assertEquals(0, rejected,
                "已支付的不允许调整 —— 那必然涉及退款，属于人工运营流程");

        Order loaded = orderMapper.selectById(pending.getId());
        assertEquals(1, loaded.getAdjusted(), "调整标记要落库");
        assertEquals(9L, loaded.getAdjustedBy(), "调整人要落库");
        assertNotNull(loaded.getAdjustedAt(), "调整时间要落库");
        assertEquals("监控核实已离场", loaded.getAdjustReason(), "调整原因要落库");
    }

    // ==================================================================
    // 查询
    // ==================================================================

    @Test
    @DisplayName("分页：同一时刻创建的订单也能稳定翻页")
    void selectPageByUser_isStableWhenCreatedAtTies() {
        // 同一秒内连插几条 —— 只按 created_at 排序时它们的相对顺序是不确定的
        for (int i = 0; i < 5; i++) {
            orderMapper.insert(newOrder(OrderStatus.PAID));
        }

        Page<Order> firstPage = new Page<>(1, 2);
        orderMapper.selectPageByUser(firstPage, USER_ID, null);
        Page<Order> secondPage = new Page<>(2, 2);
        orderMapper.selectPageByUser(secondPage, USER_ID, null);

        List<Long> firstIds = firstPage.getRecords().stream().map(Order::getId).toList();
        List<Long> secondIds = secondPage.getRecords().stream().map(Order::getId).toList();
        assertTrue(firstIds.stream().noneMatch(secondIds::contains),
                "两页的记录不能重叠 —— 只按 created_at 排序时，同一秒的行会来回漂，"
                        + "翻页就会看到重复或漏掉的行");
    }

    @Test
    @DisplayName("查询进行中的订单：只认使用中状态")
    void selectActiveByUser_findsOnlyInUse() {
        orderMapper.insert(orderOf(LocalDateTime.now().minusHours(5), OrderStatus.PAID, "22.00"));
        Order inUse = newOrder(OrderStatus.IN_USE);
        orderMapper.insert(inUse);

        Order active = orderMapper.selectActiveByUser(USER_ID);

        assertNotNull(active, "有一笔进行中的订单应当查得到");
        assertEquals(inUse.getId(), active.getId(), "拿到的应当是那笔使用中的");
    }

    @Test
    @DisplayName("未结清查询：待支付的订单算「未结清」，而 current 用的那条查询看不见它")
    void selectUnsettledByUser_coversPendingPayment() {
        orderMapper.insert(paidOrder(LocalDateTime.now().minusDays(1), "22.00"));
        Order pending = newOrder(OrderStatus.PENDING_PAYMENT);
        orderMapper.insert(pending);

        Order active = orderMapper.selectActiveByUser(USER_ID);
        Order unsettled = orderMapper.selectUnsettledByUser(USER_ID);

        assertNull(active,
                "首页的「当前订单」只认使用中：待支付的单已结算、密码已撤销，"
                        + "返回它会给出一个点不动的「查看密码」");
        assertNotNull(unsettled, "待支付的订单属于「未结清」—— 防连点要连它一起拦");
        assertEquals(pending.getId(), unsettled.getId(), "拿到的应当是那笔待支付的");
    }

    @Test
    @DisplayName("未结清查询：同时有使用中与待支付时取最近一条")
    void selectUnsettledByUser_picksLatestOne() {
        Order pending = newOrder(OrderStatus.PENDING_PAYMENT);
        orderMapper.insert(pending);
        Order inUse = newOrder(OrderStatus.IN_USE);
        orderMapper.insert(inUse);

        Order unsettled = orderMapper.selectUnsettledByUser(USER_ID);

        assertNotNull(unsettled);
        assertEquals(inUse.getId(), unsettled.getId(),
                "同一个人同时挂着两种未结清状态（修复前的老数据才会这样）时取最近的一条，"
                        + "提示用户先处理它");
    }

    @Test
    @DisplayName("未结清查询：已结清的历史订单不返回")
    void selectUnsettledByUser_ignoresPaid() {
        orderMapper.insert(paidOrder(LocalDateTime.now().minusHours(3), "16.00"));

        Order unsettled = orderMapper.selectUnsettledByUser(USER_ID);

        assertNull(unsettled, "付过的单是已经了结的 —— 算进去会让所有老顾客都进不了门");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 构造一条使用中的订单。
     *
     * @param status 状态
     * @return 订单实体（未落库）
     */
    private Order newOrder(OrderStatus status) {
        return orderOf(LocalDateTime.now().minusHours(1), status, null);
    }

    /**
     * 构造一条指定状态的订单。
     *
     * <p><b>非「使用中」的订单一律补上离场时刻</b>（{@code startTime + 1 小时}）——
     * 「已结算的单必有 {@code end_time}」是系统的不变式，而月度归集正是按它归集的，
     * 测试数据不照这个造的话，那些单会被<b>静默漏掉</b>（统计偏小、不报错）。
     * 需要跨月这类特殊场景时，调用方拿回对象后自行覆盖 {@code endTime}。
     *
     * @param startTime 计费起点
     * @param status    状态
     * @param payable   应付金额，可为 null
     * @return 订单实体（未落库）
     */
    private Order orderOf(LocalDateTime startTime, OrderStatus status, String payable) {
        Order order = new Order();
        // 单号用纳秒拼出来，保证同一用例内多次构造不会撞唯一索引
        order.setOrderNo("OD" + System.nanoTime());
        order.setUserId(USER_ID);
        order.setStoreId(STORE_ID);
        order.setLockId(LOCK_ID);
        order.setStartTime(startTime);
        order.setStatus(status.name());
        order.setDiscountAmount(BigDecimal.ZERO);
        if (status != OrderStatus.IN_USE) {
            // 进行中的那一单还没离场，end_time 天然为空
            order.setEndTime(startTime.plusHours(1));
        }
        if (payable != null) {
            order.setTotalAmount(new BigDecimal(payable));
            order.setPayableAmount(new BigDecimal(payable));
        }
        return order;
    }

    /**
     * 构造一条已支付的订单。
     *
     * @param startTime 计费起点
     * @param payable   实付金额
     * @return 订单实体（未落库）
     */
    private Order paidOrder(LocalDateTime startTime, String payable) {
        Order order = orderOf(startTime, OrderStatus.PAID, payable);
        order.setPaidAt(startTime.plusHours(1));
        return order;
    }
}
