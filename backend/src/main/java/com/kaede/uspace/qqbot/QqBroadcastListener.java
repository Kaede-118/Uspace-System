package com.kaede.uspace.qqbot;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.order.InstoreService;
import com.kaede.uspace.order.dto.InstoreUserVo;
import com.kaede.uspace.order.event.OrderEnteredEvent;
import com.kaede.uspace.order.event.OrderLeftEvent;
import com.kaede.uspace.user.entity.SysUser;
import com.kaede.uspace.user.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Set;

/**
 * 把订单事件播报到 QQ 群（模块 11）。
 *
 * <p>订单模块发布 {@link OrderEnteredEvent} / {@link OrderLeftEvent}，
 * 这里监听并推群。编译期 {@code order} 完全不认识 {@code qqbot}，靠事件解耦 ——
 * 将来把机器人拆成独立服务，本类跟着走即可。
 *
 * <h3>⚠️ 为什么必须是 AFTER_COMMIT，不能是普通的 {@code @EventListener}</h3>
 *
 * <p>发布点（{@code OrderService.createOrder} / {@code applySettlement}）都在
 * {@code @Transactional} 方法里。普通监听器会在<b>事务提交之前</b>同步执行，
 * 此时它回查「现在店里有几个人」<b>读不到刚插入的那条订单</b> ——
 * 于是播报会说「张三到店了，当前 0 人在店」，而<b>不报任何错</b>。
 *
 * <p>{@code AFTER_COMMIT} 之后事务已提交，读到的就是最新状态。
 *
 * <h3>⚠️ 方法体为什么整个包在 try/catch 里</h3>
 *
 * <p>Spring 的 {@code AbstractPlatformTransactionManager} 在触发
 * {@code afterCommit} 回调时<b>没有针对单个监听器的保护</b>（对比：
 * {@code afterCompletion} 那一路是有的）。所以这里抛出的任何一个 RuntimeException
 * 都会沿着 {@code publishEvent} 一路冒回 {@code createOrder} 的调用方，
 * 让用户看到<b>「开门失败」，而门其实已经开了、密码也下发了</b>。
 * 他去点第二次，拿到的是「已有进行中的订单」。
 *
 * <p>这是本模块最严重的一个坑，而且触发条件（NapCat 掉线、查库失败、
 * 某个 Service 抛异常）完全在日常范围内。所以两个监听方法都<b>整包 try/catch</b>，
 * 捕获 {@code Throwable} 而不只是 {@code Exception} —— 代价是零，收益是
 * 「播报永远不可能弄坏一笔已经成功的订单」。
 *
 * <h3>⚠️ 绝对不要在这个类里写库</h3>
 *
 * <p>监听器执行时，事务虽然提交了，但<b>数据库连接还没有归还连接池</b>
 * （Spring 的顺序是「提交 → 触发 afterCommit → 清理」）。
 * 此时 {@code DataSourceUtils} 会把那条<b>已经提交、正等着归还的连接</b>
 * 再借给你，于是你的写入既不在任何事务里、也不会被提交 ——
 * 连接归还时被 rollback，<b>一声不吭</b>。
 *
 * <p>本模块是只读的，所以天然安全。但这正是「只读」这条纪律的技术依据，
 * 而不是一句写在文档里的口号 —— 谁将来想在这里加一个「播报计数」，
 * 得先知道为什么不能。
 *
 * <h3>播报为什么按群分别发送，而不是拼一条群发</h3>
 *
 * <p>因为每个群看到的内容可能不同：店主群带消费金额，顾客群不带。
 * 逐群算一次 {@link QqbotProperties#isAmountVisible}，各发各的。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "uspace.qqbot.enabled", havingValue = "true")
public class QqBroadcastListener {

    private final QqbotProperties properties;

    private final OneBotClient client;

    private final InstoreService instoreService;

    private final SysUserMapper sysUserMapper;

    public QqBroadcastListener(QqbotProperties properties,
                               OneBotClient client,
                               InstoreService instoreService,
                               SysUserMapper sysUserMapper) {
        this.properties = properties;
        this.client = client;
        this.instoreService = instoreService;
        this.sysUserMapper = sysUserMapper;
    }

    /**
     * 有人到店了。
     *
     * <p>这条播报<b>不带金额</b> —— 此刻订单刚开，费用要到结算才算得出来。
     * 所以它对所有群是同一份文本，不必分版本。
     *
     * @param event 到店事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderEntered(OrderEnteredEvent event) {
        try {
            if (!properties.getBroadcast().isEnabled()) {
                return;
            }
            String text = displayName(event.userId()) + " 已到店";
            Integer instoreCount = countInstorePeople();
            if (instoreCount != null) {
                text = text + "，当前 " + instoreCount + " 人在店";
            }
            broadcast(text, null);
            log.debug("[QQ机器人] 已播报到店 orderNo={}", event.orderNo());
        } catch (Throwable t) {
            // 见类注释：这里的异常绝不能冒到订单那边去
            log.error("[QQ机器人] 到店播报失败 orderNo={}", event.orderNo(), t);
        }
    }

    /**
     * 有人离店了。
     *
     * <p>这条有<b>两个版本</b>：含金额的只发给店主群，不含金额的发给其余群。
     * 分叉只由 {@link QqbotProperties#isAmountVisible} 决定，
     * 绝不从「这条事件带了金额」之类的线索去推断 —— 事件永远带金额，
     * 那样写等于全员可见，而它照样能跑通全部演示。
     *
     * <p>⚠️ <b>这条播报刻意不报「当前还有几人在店」</b>：包场清场在一个事务里
     * 循环结算 N 单，N 个事件都在提交后才触发，此时所有人都已经结算完了 ——
     * 逐条读取会得到 N 条一模一样的「还有 0 人在店」。
     * 少说一句「还有几人」没有任何损失，而多说一句错的会让人以为系统坏了。
     * （到店播报没有这个问题：开门不在循环里，一次请求一条事件。）
     *
     * @param event 离店事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderLeft(OrderLeftEvent event) {
        try {
            if (!properties.getBroadcast().isEnabled()) {
                return;
            }
            String name = displayName(event.userId());
            String head = leftHeadline(name, event.source())
                    + "，本次 " + QqReplyText.duration(event.stayMinutes());

            // 不含金额的版本：能说的是「要不要付」，不能说「付多少」
            String plain = event.free() ? head + "（无需支付）" : head + "，请到网页端完成支付";
            // 含金额的版本，只进店主群
            String withAmount = head + "，消费 ¥" + event.amount().toPlainString()
                    + (event.free() ? "（无需支付）" : "，请到网页端完成支付");

            broadcast(plain, withAmount);
            log.debug("[QQ机器人] 已播报离店 orderNo={} source={}", event.orderNo(), event.source());
        } catch (Throwable t) {
            log.error("[QQ机器人] 离店播报失败 orderNo={}", event.orderNo(), t);
        }
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /**
     * 往所有生效群各发一条。
     *
     * <p>{@code adminText} 为 null 表示这条播报没有「含金额」的版本（如到店），
     * 所有群发同一份 {@code plainText}。
     *
     * @param plainText 不含金额的文本
     * @param adminText 含金额的文本，可为 null
     */
    private void broadcast(String plainText, String adminText) {
        Set<Long> groups = properties.effectiveGroups();
        if (groups.isEmpty()) {
            log.debug("[QQ机器人] 没有生效的群，播报跳过");
            return;
        }
        for (Long groupId : groups) {
            boolean withAmount = adminText != null && properties.isAmountVisible(groupId);
            client.sendGroupMessage(groupId, withAmount ? adminText : plainText);
        }
    }

    /**
     * 离店播报的抬头。
     *
     * <p>⚠️ <b>三条路径的措辞必须分叉</b>，尤其是
     * {@link OrderLeftEvent.Source#ADMIN_ADJUST}：那是管理员<b>事后补录</b>
     * 「昨晚忘了点结束使用」的单子，发一句「张三已离店」是<b>假消息</b>。
     * 在有人长期看播报的群里，假消息比没消息更糟 —— 它会让人开始怀疑每一条播报。
     *
     * @param name   顾客展示名
     * @param source 结算是哪条路径产生的
     * @return 播报抬头
     */
    private static String leftHeadline(String name, OrderLeftEvent.Source source) {
        return switch (source) {
            case USER -> name + " 已离店";
            case BOOKING_CLEAR -> name + " 因包场开始已自动结算离场";
            case ADMIN_ADJUST -> name + " 的离店记录已由管理员补录";
        };
    }

    /**
     * 取展示用的名字。
     *
     * <p>从<b>库里现查</b>而不是从事件里带 —— 事件里的昵称会是一个「发布时刻的快照」，
     * 用户改了名播报还念旧的。查不到时退化成「用户{ID}」而不是丢弃这条播报：
     * 播报的价值在于「有人进出」，名字是次要的。
     *
     * @param userId 用户 ID
     * @return 展示名
     */
    private String displayName(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || user.getNickname() == null || user.getNickname().isBlank()) {
            return "用户" + userId;
        }
        return user.getNickname();
    }

    /**
     * 数一下此刻店里还有几个人。
     *
     * <p>复用 {@link InstoreService}（与 Web 端在店名册、群里 {@code /在店} 同一个方法），
     * 所以三处的口径必然一致。
     *
     * @return 在店人数；查询失败时返回 null，调用方据此省掉这一句，而不是播报一个 0
     */
    private Integer countInstorePeople() {
        BizResult<List<InstoreUserVo>> result = instoreService.listInstoreUsers();
        if (!result.isSuccess() || result.getData() == null) {
            log.warn("[QQ机器人] 播报时查在店人数失败，本次不报人数");
            return null;
        }
        return result.getData().size();
    }

    /**
     * 把分钟数拼成人话。
     *
     * <p>原本这里有一份与 {@code QqCommandService} 重复的实现，注释写着
     * 「第三次出现时抽」—— 加了 {@code /看看自己} 与 {@code /结账} 之后
     * 第三次如约而至，已统一到 {@link QqReplyText#duration(int)}。
     * 两处各写一份的代价是「改了这处忘了那处」，表现是同一个时长在两处说法不一致。
     *
     * @param minutes 分钟数
     * @return 形如 {@code 35 分钟} / {@code 1 小时 20 分}
     */
    private static String formatDuration(int minutes) {
        return QqReplyText.duration(minutes);
    }
}
