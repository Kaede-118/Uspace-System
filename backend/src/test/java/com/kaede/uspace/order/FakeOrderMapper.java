package com.kaede.uspace.order;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.order.entity.Order;
import com.kaede.uspace.order.mapper.OrderMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 内存版的 {@link OrderMapper}，让模块 8 的单元测试不依赖数据库。
 *
 * <p>写法与既有的假 Mapper 一致：动态代理 + 按方法名分发。
 * <b>类声明为 public</b>，因为模块 8 的多个测试类（订单、支付、集成前的服务测试）
 * 都要用它 —— 与 {@code FakeSysUserMapper} 是同一个理由。
 *
 * <p><b>三处必须与真实 SQL 逐字对齐的语义</b>，错一处测出来的结论就是假的：
 *
 * <ol>
 *   <li><b>更新方法的状态守卫</b> —— 真实 SQL 带着
 *       {@code AND status = '...'}。这不只是并发保护，更是幂等的关键一道：
 *       支付回调重推时，第二次会因为状态已变而拿到 0 行受影响。
 *       假实现若忽略状态直接改，就会把「重复回调只记账一次」这条测成永远通过。</li>
 *   <li><b>月度累计只算 PAID、按 start_time 归集</b> ——
 *       写成按 {@code paidAt} 归集的话，跨零点结算的夜单会跳到下个月，
 *       而这种错在单测里完全看不出来。</li>
 *   <li><b>逻辑删除过滤</b> —— 真实库里由全局配置与手写 SQL 里的
 *       {@code deleted = 0} 共同保证。</li>
 * </ol>
 */
public class FakeOrderMapper implements InvocationHandler {

    /** 模拟数据表。用 LinkedHashMap 保持插入顺序，便于调试时观察 */
    private final Map<Long, Order> rows = new LinkedHashMap<>();

    /** 模拟自增主键 */
    private long nextId = 1;

    /**
     * 生成接口代理，交给 Service 使用。
     *
     * @return OrderMapper 的假实现
     */
    public OrderMapper asMapper() {
        return (OrderMapper) Proxy.newProxyInstance(
                OrderMapper.class.getClassLoader(),
                new Class<?>[]{OrderMapper.class},
                this);
    }

    /**
     * 预置一条订单，模拟「库里已经有这张单」。
     *
     * @param order 订单，id 为空时自动分配
     * @return 同一个对象，便于链式构造
     */
    public Order seed(Order order) {
        if (order.getId() == null) {
            order.setId(allocateId());
        }
        if (order.getDeleted() == null) {
            order.setDeleted(0);
        }
        rows.put(order.getId(), order);
        return order;
    }

    /**
     * 按 ID 取出表中的当前状态，供测试断言。
     *
     * <p>注意返回的是<b>表里的那个对象本身</b>，而非副本 ——
     * 假实现让 Service 直接改内存对象，断言看到的就是最终状态。
     *
     * @param id 订单 ID
     * @return 订单；不存在时返回 null
     */
    public Order get(Long id) {
        return rows.get(id);
    }

    /** 取当前表里的记录条数 */
    public int size() {
        return rows.size();
    }

    /**
     * 分配一个未被占用的自增主键。
     *
     * @return 可用的主键
     */
    private long allocateId() {
        while (rows.containsKey(nextId)) {
            nextId++;
        }
        return nextId++;
    }

    /**
     * 方法分发。
     *
     * @param proxy  代理对象（未使用）
     * @param method 被调用的方法
     * @param args   调用参数
     * @return 方法返回值
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "insert" -> insert((Order) args[0]);
            case "selectById" -> selectById((Long) args[0]);
            case "deleteById" -> deleteById((Long) args[0]);
            case "selectByOrderNo" -> selectByOrderNo((String) args[0]);
            case "selectActiveByUser" -> selectActiveByUser((Long) args[0]);
            case "selectUnsettledByUser" -> selectUnsettledByUser((Long) args[0]);
            case "selectActiveByStore" -> selectActiveByStore((Long) args[0]);
            case "selectMonthPaidAmount" -> selectMonthPaidAmount(args);
            case "selectTotalStayMinutes" -> selectTotalStayMinutes(args);
            case "selectMonthStayMinutes" -> selectMonthStayMinutes(args);
            case "selectPageByUser" -> selectPageByUser(args);
            case "selectPageForAdmin" -> selectPageForAdmin(args);
            case "updatePasscode" -> updatePasscode(args);
            case "updateSettlement" -> updateSettlement(args);
            case "updateAdjustment" -> updateAdjustment(args);
            case "markPaid" -> markPaid(args);
            case "updatePaymentProof" -> updatePaymentProof(args);
            default -> throw new UnsupportedOperationException(
                    "假 Mapper 未实现方法 " + method.getName()
                            + " —— 出现这个错误说明 Service 调用了预期之外的方法，"
                            + "请在 FakeOrderMapper 中补上对应实现");
        };
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 插入。模拟自增主键与审计字段的自动填充。
     *
     * @param order 待插入的订单
     * @return 受影响行数，恒为 1
     */
    private int insert(Order order) {
        order.setId(allocateId());
        if (order.getDeleted() == null) {
            order.setDeleted(0);
        }
        if (order.getCreatedAt() == null) {
            // 真库里由 AuditMetaObjectHandler 填充，这里模拟同一行为
            order.setCreatedAt(LocalDateTime.now());
        }
        order.setUpdatedAt(LocalDateTime.now());
        rows.put(order.getId(), order);
        return 1;
    }

    /**
     * 按 ID 查询，过滤已逻辑删除的行。
     *
     * @param id 订单 ID
     * @return 订单；不存在或已删除时返回 null
     */
    private Order selectById(Long id) {
        Order order = rows.get(id);
        return isAlive(order) ? order : null;
    }

    /**
     * 逻辑删除。
     *
     * @param id 订单 ID
     * @return 受影响行数
     */
    private int deleteById(Long id) {
        Order order = selectById(id);
        if (order == null) {
            return 0;
        }
        order.setDeleted(1);
        return 1;
    }

    /**
     * 按单号查询，过滤已逻辑删除的行。
     *
     * @param orderNo 订单号
     * @return 订单；不存在时返回 null
     */
    private Order selectByOrderNo(String orderNo) {
        return rows.values().stream()
                .filter(FakeOrderMapper::isAlive)
                .filter(o -> Objects.equals(o.getOrderNo(), orderNo))
                .findFirst()
                .orElse(null);
    }

    /**
     * 查询某人进行中的订单，取 id 最大的一条。
     *
     * <p>与真实 SQL 的 {@code ORDER BY id DESC LIMIT 1} 对齐 ——
     * 万一历史数据里同一人有两条使用中的订单，返回最新的那条。
     *
     * @param userId 用户 ID
     * @return 订单；没有则返回 null
     */
    private Order selectActiveByUser(Long userId) {
        return rows.values().stream()
                .filter(FakeOrderMapper::isAlive)
                .filter(o -> Objects.equals(o.getUserId(), userId))
                .filter(o -> OrderStatus.IN_USE.name().equals(o.getStatus()))
                .max(Comparator.comparing(Order::getId))
                .orElse(null);
    }

    /**
     * 查询某人最近一条未结清的订单（使用中或待支付），取 id 最大的一条。
     *
     * <p>与真实 SQL 的 {@code status IN ('IN_USE', 'PENDING_PAYMENT')}
     * + {@code ORDER BY id DESC LIMIT 1} 逐条对齐。
     *
     * <p><b>「待支付」那一半不能少</b>：少了它，假 Mapper 就复刻了修复前的老行为，
     * 「欠费的账号还能再开一单」这条会测成永远通过 —— 而那正是本次要挡的。
     * 反过来，「已支付」必须排除：付过的单是已经了结的，不该拦着用户开新单。
     *
     * <p>状态直接交给 {@link OrderStatus#isUnpaid} 判，<b>不在外面加判空</b>：
     * 它的契约就是「认不出（含 null）返回 false」，2026-09-30 已修好。
     * 外面再包一层 {@code status != null} 反而有害 ——
     * 万一它又被改回会抛 NPE 的写法，那层守卫会把问题盖住，测试照样全绿。
     *
     * @param userId 用户 ID
     * @return 订单；没有则返回 null
     */
    private Order selectUnsettledByUser(Long userId) {
        return rows.values().stream()
                .filter(FakeOrderMapper::isAlive)
                .filter(o -> Objects.equals(o.getUserId(), userId))
                .filter(o -> OrderStatus.isUnpaid(o.getStatus()))
                .max(Comparator.comparing(Order::getId))
                .orElse(null);
    }


    /**
     * 查某门店全部进行中的订单，按开门时刻升序 —— 与真实 SQL 的 {@code ORDER BY start_time} 对齐。
     *
     * <p><b>「某门店」这个条件不能省</b>：真实 SQL 带着 {@code store_id = #{storeId}}，
     * 少了它，多门店场景下会把别家的顾客一起清场结算。
     *
     * @param storeId 门店 ID
     * @return 进行中的订单；没有则返回空列表
     */
    private List<Order> selectActiveByStore(Long storeId) {
        return rows.values().stream()
                .filter(FakeOrderMapper::isAlive)
                .filter(o -> Objects.equals(o.getStoreId(), storeId))
                .filter(o -> OrderStatus.IN_USE.name().equals(o.getStatus()))
                .sorted(Comparator.comparing(Order::getStartTime))
                .toList();
    }

    /**
     * 汇总某人某月已支付订单的实付额。
     *
     * <p>逐条复刻真实 SQL 的四个条件：只算 PAID、按 start_time 归集、
     * 半开区间、排除已删除。少任何一条，都会让「优惠门槛」的判定在单测里失真。
     *
     * @param args 依次为 userId、from、to
     * @return 实付额之和；无记录时返回 0
     */
    private BigDecimal selectMonthPaidAmount(Object[] args) {
        Long userId = (Long) args[0];
        LocalDateTime from = (LocalDateTime) args[1];
        LocalDateTime to = (LocalDateTime) args[2];

        return rows.values().stream()
                .filter(FakeOrderMapper::isAlive)
                .filter(o -> Objects.equals(o.getUserId(), userId))
                .filter(o -> OrderStatus.PAID.name().equals(o.getStatus()))
                .filter(o -> o.getStartTime() != null
                        && !o.getStartTime().isBefore(from)
                        && o.getStartTime().isBefore(to))
                .map(Order::getPayableAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 分页查询某人的订单。
     *
     * @param args 依次为 IPage、userId、status
     * @return 分页结果
     */
    @SuppressWarnings("unchecked")
    private IPage<Order> selectPageByUser(Object[] args) {
        IPage<Order> page = (IPage<Order>) args[0];
        Long userId = (Long) args[1];
        String status = (String) args[2];

        List<Order> matched = rows.values().stream()
                .filter(FakeOrderMapper::isAlive)
                .filter(o -> Objects.equals(o.getUserId(), userId))
                .filter(o -> status == null || status.isEmpty() || status.equals(o.getStatus()))
                .sorted(Comparator.comparing(Order::getId).reversed())
                .toList();
        return fillPage(page, matched);
    }

    /**
     * 后台分页查询。
     *
     * @param args 依次为 IPage、userId、status、from、to、adjusted
     * @return 分页结果
     */
    @SuppressWarnings("unchecked")
    private IPage<Order> selectPageForAdmin(Object[] args) {
        IPage<Order> page = (IPage<Order>) args[0];
        Long userId = (Long) args[1];
        String status = (String) args[2];
        LocalDateTime from = (LocalDateTime) args[3];
        LocalDateTime to = (LocalDateTime) args[4];
        Integer adjusted = (Integer) args[5];

        List<Order> matched = rows.values().stream()
                .filter(FakeOrderMapper::isAlive)
                .filter(o -> userId == null || Objects.equals(o.getUserId(), userId))
                .filter(o -> status == null || status.isEmpty() || status.equals(o.getStatus()))
                .filter(o -> from == null || (o.getStartTime() != null && !o.getStartTime().isBefore(from)))
                .filter(o -> to == null || (o.getStartTime() != null && o.getStartTime().isBefore(to)))
                .filter(o -> adjusted == null || Objects.equals(o.getAdjusted(), adjusted))
                .sorted(Comparator.comparing(Order::getId).reversed())
                .toList();
        return fillPage(page, matched);
    }

    // ==================================================================
    // 更新
    // ==================================================================

    /**
     * 续期密码。带「使用中」状态守卫。
     *
     * @param args 依次为 id、passcode、startTime、endTime
     * @return 受影响行数
     */
    private int updatePasscode(Object[] args) {
        Order order = guard((Long) args[0], OrderStatus.IN_USE.name()::equals);
        if (order == null) {
            return 0;
        }
        order.setPasscode((String) args[1]);
        order.setPasscodeStart((LocalDateTime) args[2]);
        order.setPasscodeEnd((LocalDateTime) args[3]);
        return 1;
    }

    /**
     * 写入结算结果。带「使用中」状态守卫。
     *
     * @param args 依次为 id、endTime、日场分钟、日场金额、夜场分钟、夜场金额、
     *             合计、优惠额、月卡免单额、应付、目标状态
     * @return 受影响行数
     */
    private int updateSettlement(Object[] args) {
        Order order = guard((Long) args[0], OrderStatus.IN_USE.name()::equals);
        if (order == null) {
            return 0;
        }
        applyAmounts(order, args, 1);
        order.setStatus((String) args[11]);
        return 1;
    }

    /**
     * 人工调整时长与金额。带「未付款」状态守卫，并写下四个调整字段。
     *
     * @param args 依次为 id、endTime、日场分钟、日场金额、夜场分钟、夜场金额、
     *             合计、优惠额、月卡免单额、应付、目标状态、调整人、调整原因
     * @return 受影响行数
     */
    private int updateAdjustment(Object[] args) {
        Order order = guard((Long) args[0], OrderStatus::isUnpaid);
        if (order == null) {
            return 0;
        }
        applyAmounts(order, args, 1);
        order.setStatus((String) args[11]);
        order.setAdjusted(1);
        order.setAdjustedBy((Long) args[12]);
        order.setAdjustReason((String) args[13]);
        order.setAdjustedAt(LocalDateTime.now());
        return 1;
    }

    /**
     * 标记已支付。带「待支付」状态守卫 —— 这是回调幂等的关键一道。
     *
     * @param args 依次为 id、paymentMethod、paymentNo、paidAt、confirmedBy
     * @return 受影响行数
     */
    private int markPaid(Object[] args) {
        Order order = guard((Long) args[0], OrderStatus.PENDING_PAYMENT.name()::equals);
        if (order == null) {
            return 0;
        }
        order.setStatus(OrderStatus.PAID.name());
        order.setPaymentMethod((String) args[1]);
        order.setPaymentNo((String) args[2]);
        order.setPaidAt((LocalDateTime) args[3]);
        order.setConfirmedBy((Long) args[4]);
        return 1;
    }

    /**
     * 写入支付凭证。带「待支付」状态守卫。
     *
     * @param args 依次为 id、paymentProof
     * @return 受影响行数
     */
    private int updatePaymentProof(Object[] args) {
        Order order = guard((Long) args[0], OrderStatus.PENDING_PAYMENT.name()::equals);
        if (order == null) {
            return 0;
        }
        order.setPaymentProof((String) args[1]);
        order.setPaymentMethod("QR_UPLOAD");
        return 1;
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 取出记录并检查状态守卫，模拟真实 SQL 里 {@code WHERE ... AND status = ...} 的效果。
     *
     * @param id    订单 ID
     * @param guard 状态判定
     * @return 通过守卫的订单；不通过时返回 null（调用方据此返回 0 行受影响）
     */
    private Order guard(Long id, Predicate<String> guard) {
        Order order = selectById(id);
        if (order == null || !guard.test(order.getStatus())) {
            return null;
        }
        return order;
    }

    /**
     * 把金额相关的参数写进订单。
     *
     * @param order  订单
     * @param args   方法参数
     * @param offset 金额参数在参数数组里的起始下标
     */
    private static void applyAmounts(Order order, Object[] args, int offset) {
        order.setEndTime((LocalDateTime) args[offset]);
        order.setStayMinutes((Integer) args[offset + 1]);
        order.setDayMinutes((Integer) args[offset + 2]);
        order.setDayAmount((BigDecimal) args[offset + 3]);
        order.setNightMinutes((Integer) args[offset + 4]);
        order.setNightAmount((BigDecimal) args[offset + 5]);
        order.setTotalAmount((BigDecimal) args[offset + 6]);
        order.setDiscountAmount((BigDecimal) args[offset + 7]);
        order.setCardFreeAmount((BigDecimal) args[offset + 8]);
        order.setPayableAmount((BigDecimal) args[offset + 9]);
    }

    /**
     * 累计在店时长，全部历史 —— 与真实 SQL 的「只算 PAID、按 user_id 过滤、
     * 排除已删除」逐条对齐。
     *
     * <p><b>只算 PAID 这条不能少</b>：写成「所有状态」的话，「我的」页的累计时长
     * 会把在店未结账的那一单也算进去，每刷新一次数字就往上跳一次。
     *
     * @param args 依次为 userId
     * @return 累计分钟数；无记录时返回 0
     */
    private Long selectTotalStayMinutes(Object[] args) {
        Long userId = (Long) args[0];
        return rows.values().stream()
                .filter(FakeOrderMapper::isAlive)
                .filter(o -> Objects.equals(o.getUserId(), userId))
                .filter(o -> OrderStatus.PAID.name().equals(o.getStatus()))
                .map(Order::getStayMinutes)
                .filter(Objects::nonNull)
                .mapToLong(Integer::longValue)
                .sum();
    }

    /**
     * 某月的在店时长 —— 归月口径与 {@link #selectMonthPaidAmount} 逐字一致：
     * 按 {@code start_time} 的半开区间 {@code [from, to)} 归集，不是 {@code end_time}。
     *
     * <p>写成按 {@code end_time} 归集的话，跨零点结算的夜单会跳到下个月，
     * 而这种错在单测里完全看不出来。
     *
     * @param args 依次为 userId、from、to
     * @return 该月分钟数；无记录时返回 0
     */
    private Long selectMonthStayMinutes(Object[] args) {
        Long userId = (Long) args[0];
        LocalDateTime from = (LocalDateTime) args[1];
        LocalDateTime to = (LocalDateTime) args[2];

        return rows.values().stream()
                .filter(FakeOrderMapper::isAlive)
                .filter(o -> Objects.equals(o.getUserId(), userId))
                .filter(o -> OrderStatus.PAID.name().equals(o.getStatus()))
                .filter(o -> o.getStartTime() != null
                        && !o.getStartTime().isBefore(from)
                        && o.getStartTime().isBefore(to))
                .map(Order::getStayMinutes)
                .filter(Objects::nonNull)
                .mapToLong(Integer::longValue)
                .sum();
    }

    /**
     * 是否未被逻辑删除。
     *
     * @param order 订单，可为 null
     * @return 未删除返回 true
     */
    private static boolean isAlive(Order order) {
        return order != null && (order.getDeleted() == null || order.getDeleted() == 0);
    }

    /**
     * 把全量结果按分页参数切一段写回分页对象。
     *
     * @param page 分页参数
     * @param all  满足条件的全部记录
     * @return 同一个分页对象
     */
    private static IPage<Order> fillPage(IPage<Order> page, List<Order> all) {
        page.setTotal(all.size());
        int offset = (int) Math.min((page.getCurrent() - 1) * page.getSize(), all.size());
        offset = Math.max(offset, 0);
        int end = (int) Math.min(offset + page.getSize(), all.size());
        page.setRecords(new ArrayList<>(all.subList(offset, end)));
        return page;
    }
}
