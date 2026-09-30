package com.kaede.uspace.order;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.order.dto.InstoreUserVo;
import com.kaede.uspace.order.entity.Order;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
