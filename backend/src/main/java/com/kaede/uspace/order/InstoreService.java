package com.kaede.uspace.order;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.order.dto.InstoreSnapshotVo;
import com.kaede.uspace.order.dto.InstoreUserVo;
import com.kaede.uspace.order.dto.OrderTrafficEventVo;
import com.kaede.uspace.order.dto.OrderTrafficVo;
import com.kaede.uspace.order.entity.Order;
import com.kaede.uspace.order.mapper.OrderEventRow;
import com.kaede.uspace.order.mapper.OrderMapper;
import com.kaede.uspace.promotion.MonthlyCardService;
import com.kaede.uspace.promotion.MonthlyCardType;
import com.kaede.uspace.promotion.entity.MonthlyCard;
import com.kaede.uspace.space.entity.Store;
import com.kaede.uspace.space.mapper.StoreMapper;
import com.kaede.uspace.user.entity.SysUser;
import com.kaede.uspace.user.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 在店用户服务（模块 8）。
 *
 * <p>职责只有一件事：把「此刻店里有哪些人」组装成一份可展示的名册。
 *
 * <h3>为什么独立成一个 Service，而不是塞进 {@link OrderService}</h3>
 *
 * <p>它读的确实是订单表，但做的事与订单的生命周期无关 —— 开门、结算、查自己的订单
 * 都在 {@code OrderService}，而这里是一份<b>聚合视图</b>：一次查询给出门店当前
 * 全部在店顾客。独立之后 {@code OrderService} 那十二个依赖不必再多一个
 * （{@code SysUserMapper}），既有的订单用例也不必为装配改动。
 * 同类先例是 {@code InviteTokenService} —— 都是围绕模块 8 的一个具体场景收出来的小事。
 *
 * <h3>为什么它住在 order 包、路径却是 /api/store/instore</h3>
 *
 * <p>数据源是 {@code biz_order}，而 {@code space} 包<b>不能</b>依赖 {@code order}
 * （反方向已存在，加回去就成环了），所以「读订单」这件事只能发生在 order 包。
 * 这与 {@code UserBookingController}（住在 order 包、路径 {@code /api/bookings}）
 * 是同一个模式的镜像：<b>路径按业务归属，代码按依赖方向</b>。
 *
 * <h3>披露边界</h3>
 *
 * <p>返回体里<b>没有任何金额</b>，偏好只给 code、中文名由前端映射 ——
 * 详见 {@link InstoreUserVo} 的类注释。这份名单对<b>任何已登录用户</b>开放：
 * 它对应的是「走进店里抬头一看，谁在店里一目了然」这件事，只是把门店换成了网页。
 */
@Service
public class InstoreService {

    private final OrderMapper orderMapper;
    private final StoreMapper storeMapper;
    private final SysUserMapper sysUserMapper;
    private final MonthlyCardService monthlyCardService;

    public InstoreService(OrderMapper orderMapper,
                          StoreMapper storeMapper,
                          SysUserMapper sysUserMapper,
                          MonthlyCardService monthlyCardService) {
        this.orderMapper = orderMapper;
        this.storeMapper = storeMapper;
        this.sysUserMapper = sysUserMapper;
        this.monthlyCardService = monthlyCardService;
    }

    /**
     * 查询门店当前的全部在店顾客。
     *
     * <p>复用 {@code OrderMapper.selectActiveByStore} —— 那条查询此前只被包场清场
     * 调度器用着，它的语义（某门店全部 {@code IN_USE} 订单、按开门时刻升序）
     * 正是这里要的，不必另写一条。
     *
     * @return 成功时返回在店名册（无人在店时为空列表，不是 404）；
     *         门店不存在时返回 {@link ErrorCode#STORE_NOT_FOUND}
     */
    public BizResult<List<InstoreUserVo>> listInstoreUsers() {
        Store store = storeMapper.selectCurrent();
        if (store == null) {
            return BizResult.fail(ErrorCode.STORE_NOT_FOUND);
        }

        List<Order> orders = orderMapper.selectActiveByStore(store.getId());
        if (orders.isEmpty()) {
            return BizResult.ok(List.of());
        }

        // 取一次「此刻」贯穿整份名册：同一次响应里所有人的在店时长必须用同一个基准，
        // 否则排在后面的几位会莫名多出几毫秒，而列表是给用户逐条比对的
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

        Map<Long, SysUser> users = loadUsers(orders);

        List<InstoreUserVo> list = new ArrayList<>(orders.size());
        for (Order order : orders) {
            list.add(toVo(order, users.get(order.getUserId()), now));
        }
        return BizResult.ok(list);
    }

    // ==================================================================
    // 进出记录（后台，模块 6 的订单口径）
    // ==================================================================

    /**
     * 分页查询进出记录，每条带「到店 / 离店」两端的名册快照。
     *
     * <p><b>口径</b>：到店 = 开始计时、离店 = 停止计时（2026-10-16 由用户定）。
     *
     * <p><b>名册是怎么算出来的</b>：先取本页所有时刻的最小 / 最大值当一个窗口，
     * 再一次性查出与这个窗口<b>相交</b>的全部订单 —— 名册只能靠这一批算，
     * 少查一条就会少一个人。然后把本页订单并进去：按离店排序时，
     * 本页最早离店那条的 {@code end_time} 恰好等于窗口起点，会被
     * 「离店即不在店」的半开语义排除在窗口查询之外，而它自己的昵称还得显示。
     * 这不是缺陷，是口径的直接结果。
     *
     * @param page        页码
     * @param size        每页条数
     * @param userId      按用户筛选，可空
     * @param from        时间下界（含），可空
     * @param to          时间上界（不含），可空
     * @param byLeaveTime true 按离店时刻排序与筛选，false 按到店时刻
     * @return 分页的进出记录；门店不存在时返回 {@link ErrorCode#STORE_NOT_FOUND}
     */
    public BizResult<PageResult<OrderTrafficVo>> listTraffic(long page, long size, Long userId,
                                                             LocalDateTime from, LocalDateTime to,
                                                             boolean byLeaveTime) {
        Store store = storeMapper.selectCurrent();
        if (store == null) {
            return BizResult.fail(ErrorCode.STORE_NOT_FOUND);
        }

        IPage<Order> result = byLeaveTime
                ? orderMapper.selectTrafficPageByLeave(new Page<>(page, size), store.getId(), userId, from, to)
                : orderMapper.selectTrafficPageByArrive(new Page<>(page, size), store.getId(), userId, from, to);

        List<Order> rows = result.getRecords();
        if (rows.isEmpty()) {
            return BizResult.ok(PageResult.of(result, order -> toTraffic(order, List.of(), Map.of())));
        }

        LocalDateTime windowStart = rows.stream()
                .map(Order::getStartTime)
                .filter(Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElseThrow();
        LocalDateTime windowEnd = rows.stream()
                .flatMap(order -> Stream.of(order.getStartTime(), order.getEndTime()))
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElseThrow();

        List<Order> related = orderMapper.selectOverlappingWindow(store.getId(), windowStart, windowEnd);

        // 本页订单与窗口订单并起来查一次用户，按 id 去重 —— 前者可能不在后者里（见方法注释）
        Map<Long, Order> merged = new LinkedHashMap<>();
        related.forEach(order -> merged.put(order.getId(), order));
        rows.forEach(order -> merged.put(order.getId(), order));
        Map<Long, SysUser> users = loadUsers(List.copyOf(merged.values()));

        return BizResult.ok(PageResult.of(result, order -> toTraffic(order, related, users)));
    }

    /**
     * 分页查询进出<b>事件</b>（到店 / 离店各一条），按时间排成一条线。
     *
     * <p>与 {@link #listTraffic} 的分工：那是<b>按订单看</b>（一行一单、两端并排），
     * 这是<b>按事件看</b>（一行一件事）。后台页面用的是这一条 ——
     * 一行一单的读法「既不是到店也不是离店」，扫一屏看不出刚才谁走了
     * （2026-10-16 按使用者反馈改的）。
     *
     * <p>名册口径与 {@link #listTraffic} 完全一致，两处共用 {@link #snapshotOf}：
     * 到店那条含本人，离店那条不含（半开区间），所以「他走后还剩谁」直接读得到。
     *
     * @param page   页码
     * @param size   每页条数
     * @param userId 按当事人筛选，可空
     * @param from   事件时刻下界（含），可空
     * @param to     事件时刻上界（不含），可空
     * @return 分页的事件列表；门店不存在时返回 {@link ErrorCode#STORE_NOT_FOUND}
     */
    public BizResult<PageResult<OrderTrafficEventVo>> listTimeline(long page, long size, Long userId,
                                                                   LocalDateTime from, LocalDateTime to) {
        Store store = storeMapper.selectCurrent();
        if (store == null) {
            return BizResult.fail(ErrorCode.STORE_NOT_FOUND);
        }

        IPage<OrderEventRow> result = orderMapper.selectTimelinePage(
                new Page<>(page, size), store.getId(), userId, from, to);

        List<OrderEventRow> rows = result.getRecords();
        if (rows.isEmpty()) {
            return BizResult.ok(PageResult.of(result, row -> toEvent(row, List.of(), Map.of())));
        }

        // 名册窗口 = 本页事件的时刻跨度；只有与窗口相交的订单才参与算名册
        LocalDateTime windowStart = rows.stream()
                .map(OrderEventRow::getEventTime)
                .filter(Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElseThrow();
        LocalDateTime windowEnd = rows.stream()
                .map(OrderEventRow::getEventTime)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElseThrow();
        List<Order> related = orderMapper.selectOverlappingWindow(store.getId(), windowStart, windowEnd);

        Map<Long, SysUser> users = new LinkedHashMap<>(loadUsers(related));
        // 补上「不在窗口里、但事件本人要用昵称」的那些人：0 秒单（到店与离店同刻）
        // 恰好落在窗口边界上，会被「离店即不在店」的半开语义挡在窗口查询之外
        List<Long> missing = rows.stream()
                .map(OrderEventRow::getUserId)
                .filter(Objects::nonNull)
                .filter(id -> !users.containsKey(id))
                .distinct()
                .toList();
        if (!missing.isEmpty()) {
            sysUserMapper.selectBatchIds(missing).forEach(user -> users.put(user.getId(), user));
        }

        return BizResult.ok(PageResult.of(result, row -> toEvent(row, related, users)));
    }

    /**
     * 把一条事件行组装成时间线上的一张卡片。
     *
     * @param row     事件行
     * @param related 算名册用的订单集合
     * @param users   用户表
     * @return 事件视图
     */
    private OrderTrafficEventVo toEvent(OrderEventRow row, List<Order> related, Map<Long, SysUser> users) {
        OrderTrafficEventVo vo = new OrderTrafficEventVo();
        vo.setEventType(row.getEventType());
        vo.setEventLabel("LEAVE".equals(row.getEventType()) ? "离店" : "到店");
        vo.setEventTime(row.getEventTime());
        vo.setOrderId(row.getId());
        vo.setOrderNo(row.getOrderNo());
        vo.setUserId(row.getUserId());
        vo.setNickname(displayNameOf(row.getUserId(), users));

        SysUser user = row.getUserId() == null ? null : users.get(row.getUserId());
        if (user != null) {
            // 仅用于前端在昵称前挂 STAFF 徽章，不承载任何权限判断
            vo.setRole(user.getRole());
        }

        vo.setStatus(row.getStatus());
        vo.setStatusLabel(OrderStatus.labelOf(row.getStatus()));
        if (row.getEventTime() != null) {
            vo.setInstore(snapshotOf(row.getEventTime(), related, users));
        }
        return vo;
    }

    /**
     * 还原任意时刻的在店名册（后台复盘用）。
     *
     * <p>把窗口收成一个点（{@code from = to = at}）交给同一条查询 ——
     * 「与一个点相交」正是「此刻在店」：{@code start_time <= at} 且
     * （{@code end_time IS NULL} 或 {@code end_time > at}）。刻意不另写一条 SQL：
     * 两处口径共用一条语句，就不可能漂移。
     *
     * @param at 要还原的时刻
     * @return 该时刻的在店名册；门店不存在时返回 {@link ErrorCode#STORE_NOT_FOUND}
     */
    public BizResult<InstoreSnapshotVo> snapshotAt(LocalDateTime at) {
        Store store = storeMapper.selectCurrent();
        if (store == null) {
            return BizResult.fail(ErrorCode.STORE_NOT_FOUND);
        }
        List<Order> orders = orderMapper.selectOverlappingWindow(store.getId(), at, at);
        return BizResult.ok(snapshotOf(at, orders, loadUsers(orders)));
    }

    /**
     * 按某一时刻从一批订单里筛出在店的人，组装成快照。
     *
     * <p><b>半开语义是这里的要点</b>：判离店用 {@code end_time > at} 而不是
     * {@code >=}，所以「离店之后还剩谁」不会把刚走的那位算进去 ——
     * 这正是「谁离开了、店里还剩几人」要的答案，也是「剩余」两个字的技术含义。
     *
     * @param at     时刻
     * @param orders 参与筛选的订单（通常是与某个窗口相交的那一批）
     * @param users  用户表，用于把 ID 换成昵称
     * @return 名册快照
     */
    private InstoreSnapshotVo snapshotOf(LocalDateTime at, List<Order> orders, Map<Long, SysUser> users) {
        List<String> names = orders.stream()
                .filter(order -> order.getStartTime() != null && !order.getStartTime().isAfter(at))
                .filter(order -> order.getEndTime() == null || order.getEndTime().isAfter(at))
                .map(order -> displayNameOf(order.getUserId(), users))
                .distinct()
                .toList();
        return InstoreSnapshotVo.of(at, names);
    }

    /**
     * 把一条订单组装成进出记录。
     *
     * @param order   订单
     * @param related 算名册用的订单集合
     * @param users   用户表
     * @return 进出记录
     */
    private OrderTrafficVo toTraffic(Order order, List<Order> related, Map<Long, SysUser> users) {
        OrderTrafficVo vo = new OrderTrafficVo();
        vo.setOrderId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setUserId(order.getUserId());
        vo.setNickname(displayNameOf(order.getUserId(), users));

        SysUser user = order.getUserId() == null ? null : users.get(order.getUserId());
        if (user != null) {
            // 仅用于前端在昵称前挂 STAFF 徽章，不承载任何权限判断
            vo.setRole(user.getRole());
        }

        vo.setArriveTime(order.getStartTime());
        vo.setLeaveTime(order.getEndTime());
        vo.setStayMinutes(stayMinutesOf(order));
        vo.setStatus(order.getStatus());
        vo.setStatusLabel(OrderStatus.labelOf(order.getStatus()));

        if (order.getStartTime() != null) {
            vo.setInstoreOnArrive(snapshotOf(order.getStartTime(), related, users));
        }
        if (order.getEndTime() != null) {
            vo.setInstoreAfterLeave(snapshotOf(order.getEndTime(), related, users));
        }
        return vo;
    }

    /**
     * 在店时长：优先读结算时写下的列，没写就按两端时刻现算。
     *
     * <p>仍在店时为 null —— 那时没有离店时刻，算不出定值。页面上要显示
     * 「已经待了多久」由前端按此刻实时算，与 {@code InstoreUserVo} 的分工一致：
     * 那边给的是实时值，这里给的是事实。
     *
     * @param order 订单
     * @return 分钟数；仍在店或时刻缺失时为 null
     */
    private Integer stayMinutesOf(Order order) {
        if (order.getStayMinutes() != null) {
            return order.getStayMinutes();
        }
        if (order.getStartTime() == null || order.getEndTime() == null) {
            return null;
        }
        return (int) Duration.between(order.getStartTime(), order.getEndTime()).toMinutes();
    }

    /**
     * 取一个人在名册上显示的名字。
     *
     * <p>查不到用户记录时不把他丢掉：被逻辑删除的账号，当时人确实在店里坐着，
     * 所以用「用户{ID}」兜底，而不是让名册静默少一个人。
     *
     * @param userId 用户 ID，可为 null
     * @param users  用户表
     * @return 显示名
     */
    private String displayNameOf(Long userId, Map<Long, SysUser> users) {
        if (userId == null) {
            return "未知用户";
        }
        SysUser user = users.get(userId);
        if (user == null || user.getNickname() == null || user.getNickname().isBlank()) {
            return "用户" + userId;
        }
        return user.getNickname();
    }

    /**
     * 批量取出名册上这些人，一次查完。
     *
     * <p><b>逐条查就是 N+1</b>：在店人数虽小，但没有理由让一次查询退化成 N 次。
     * 与 {@code BookingService#listParticipants} 补昵称的手法一致。
     *
     * <p><b>查不到的人不会从名册上消失</b>：被逻辑删除的用户不在返回之列，
     * 但那条订单仍在 —— 人还坐在店里，不能因为账号没了就当他不存在。
     * 名册上那一行的昵称等字段为空，由 {@link #toVo} 兜底。
     *
     * @param orders 在店订单
     * @return 用户 ID 到用户记录的映射；查不到的人不在其中
     */
    private Map<Long, SysUser> loadUsers(List<Order> orders) {
        List<Long> userIds = orders.stream()
                .map(Order::getUserId)
                .distinct()
                .toList();
        return sysUserMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, user -> user));
    }

    /**
     * 把一条在店订单组装成名册上的一行。
     *
     * @param order 在店订单
     * @param user  下单用户；为 null 表示查不到（已注销），此时资料字段留空
     * @param now   本次响应的统一「此刻」，用于算在店时长
     * @return 名册行
     */
    private InstoreUserVo toVo(Order order, SysUser user, LocalDateTime now) {
        InstoreUserVo vo = new InstoreUserVo();
        vo.setUserId(order.getUserId());
        if (user != null) {
            vo.setNickname(user.getNickname());
            // 仅用于前端在昵称前挂 STAFF 徽章，不承载任何权限判断
            vo.setRole(user.getRole());
            vo.setAvatar(user.getAvatar());
            vo.setBanner(user.getBanner());
            vo.setPreference(user.getPreference());
        }

        // 月卡按【订单的开始日期】判定，与结算的免单判定同一个口径 ——
        // 按「今天」判的话，跨零点仍在店的那一单会出现「名册说他有卡、结账照收钱」
        LocalDate date = order.getStartTime().toLocalDate();
        MonthlyCard card = monthlyCardService.findActiveCard(order.getUserId(), date);
        if (card != null) {
            vo.setCardType(card.getCardType());
            vo.setCardTypeLabel(MonthlyCardType.labelOf(card.getCardType()));
        }

        vo.setStartTime(order.getStartTime());
        // 起点取 order.getStartTime()（真正的开门时刻），与 applySettlement 写
        // stay_minutes 列时用的是同一个字段、同样是向下取整到分钟 ——
        // 结算之后名册上的数与订单上的列对得上
        vo.setStayMinutes((int) Duration.between(order.getStartTime(), now).toMinutes());
        return vo;
    }
}
