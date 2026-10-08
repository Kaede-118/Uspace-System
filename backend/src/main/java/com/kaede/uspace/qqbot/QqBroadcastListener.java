package com.kaede.uspace.qqbot;

import com.kaede.uspace.billing.event.FreePeriodChangedEvent;
import com.kaede.uspace.common.config.WebProperties;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.notice.event.NoticePublishedEvent;
import com.kaede.uspace.order.InstoreService;
import com.kaede.uspace.order.dto.InstoreUserVo;
import com.kaede.uspace.order.event.BookingRevokedEvent;
import com.kaede.uspace.order.event.OrderEnteredEvent;
import com.kaede.uspace.order.event.OrderLeftEvent;
import com.kaede.uspace.order.event.PaymentProofRejectedEvent;
import com.kaede.uspace.order.event.ProductPurchasedEvent;
import com.kaede.uspace.product.ProductService;
import com.kaede.uspace.product.dto.ProductVo;
import com.kaede.uspace.space.event.BookingActivatedEvent;
import com.kaede.uspace.space.event.ClosureChangedEvent;
import com.kaede.uspace.user.entity.SysUser;
import com.kaede.uspace.user.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 把业务事件播报到 QQ 群（模块 11）。
 *
 * <p>本类监听八类事件，全部由业务模块发布、本类反向 import，
 * 编译期谁都不认识 {@code qqbot}，靠事件解耦 —— 将来把机器人拆成独立服务，
 * 本类跟着走即可：
 * <ul>
 *   <li>订单：{@link OrderEnteredEvent}（到店）、{@link OrderLeftEvent}（离店）</li>
 *   <li>包场：{@link BookingActivatedEvent}（生效）—— 另一处发布点住在
 *       {@code order} 包（付款成功），事件类却住 {@code space}，见它的类注释；
 *       {@link BookingRevokedEvent}（撤销退款成功）住 {@code order} 包，
 *       因为它只有一个发布点（钱的事归 order）</li>
 *   <li>公告：{@link NoticePublishedEvent}（发布，手写与自动都发）</li>
 *   <li>商品：{@link ProductPurchasedEvent}（购买完成）—— 发布点同样住在
 *       {@code order} 包（{@code ProductPaymentTargetHandler#markPaid}），
 *       「上传付款截图」与「线上付款成功」两条路都汇聚到那里</li>
 *   <li>时段：{@link ClosureChangedEvent}（停业新增 / 撤销）、
 *       {@link FreePeriodChangedEvent}（免费活动新增 / 撤销）——
 *       两者都是「新增与撤销成对播」，靠各自的 {@code *ChangeAction} 区分动作</li>
 * </ul>
 * 另加 {@link PaymentProofRejectedEvent}（凭证被驳回）—— 它要 @ 到具体某个人，
 * 不走 {@link #broadcast}，单独一个监听方法。
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
 * 某个 Service 抛异常）完全在日常范围内。所以每个监听方法都<b>整包 try/catch</b>，
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

    /**
     * 查商品可售量用（购买播报里的「还剩多少」）。
     *
     * <p>走 Service 而不是 Mapper：可售量的口径（库存 − 未支付的待支付单）
     * 只有 {@code product} 包一处定义，群里报的数才不会与网页上打架 ——
     * 群内下单指令（{@code QqWriteCommandService}）走的也是同一个 Service。
     */
    private final ProductService productService;

    private final WebProperties webProperties;

    public QqBroadcastListener(QqbotProperties properties,
                               OneBotClient client,
                               InstoreService instoreService,
                               SysUserMapper sysUserMapper,
                               ProductService productService,
                               WebProperties webProperties) {
        this.properties = properties;
        this.client = client;
        this.instoreService = instoreService;
        this.sysUserMapper = sysUserMapper;
        this.productService = productService;
        this.webProperties = webProperties;
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

    /**
     * 付款凭证被驳回 —— 在群里 @ 本人，请他重新提交。
     *
     * <p><b>这条与另外两条播报有个本质区别：它是针对特定某个人的。</b>
     * 到店 / 离店说的是「店里发生了一件事」，大家看看就好；而驳回说的是
     * 「<b>你</b>的凭证不行，你得再做一件事」—— 不 @ 到人，这条消息大概率会被
     * 当事人划过去，而管理员那边已经判这笔钱不成立了。
     *
     * <p><b>它补的正是「先交付后复核」那道缺口</b>：订单与商品提交即落账，
     * 驳回不回退订单状态，所以用户端看到的仍是「已支付」。少了这条提醒，
     * 那个结论永远到不了用户那里，复核环节等于白设。
     *
     * <p>⚠️ <b>它也受播报开关管</b>（与两条写指令同一条纪律）：关掉播报的
     * 部署形态下，群里一个字都不发。理由不是省事 —— 在一条从不说话的群里
     * 突然 @ 一个人，比不发更让人困惑。
     *
     * @param event 驳回事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProofRejected(PaymentProofRejectedEvent event) {
        try {
            if (!properties.getBroadcast().isEnabled()) {
                return;
            }
            String text = QqReplyText.proofRejected(event.reason(),
                    webProperties.normalizedBaseUrl() + "/#/orders");
            String qq = qqOf(event.userId());
            for (Long groupId : properties.effectiveGroups()) {
                client.sendGroupMessageAt(groupId, qq, text);
            }
            log.debug("[QQ机器人] 已提醒凭证被驳回 proofId={} userId={} 类型={}",
                    event.proofId(), event.userId(), event.targetType());
        } catch (Throwable t) {
            // 见类注释：这里的异常绝不能冒回复核那边去
            log.error("[QQ机器人] 驳回提醒失败 proofId={}", event.proofId(), t);
        }
    }

    /**
     * 一场包场生效了。
     *
     * <p><b>只播已生效的场次</b>：待付款的包场随时可能被取消，提前说就成了
     * 空头安排 —— 发布点因此选在付款成功（以及 0 元场次的建单成功）那一刻，
     * 而不是排期成功那一刻。详见 {@link BookingActivatedEvent} 的类注释。
     *
     * <p>这条播报不带金额，所有群同一份文本 —— 与到店播报同理。
     *
     * @param event 包场生效事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingActivated(BookingActivatedEvent event) {
        try {
            if (!properties.getBroadcast().isEnabled()) {
                return;
            }
            String text = QqReplyText.bookingActivated(
                    event.startAt(), event.endAt(), LocalDate.now());
            broadcast(text, null);
            log.debug("[QQ机器人] 已播报包场生效 bookingNo={}", event.bookingNo());
        } catch (Throwable t) {
            // 见类注释：这里的异常绝不能冒回付款那边去
            log.error("[QQ机器人] 包场播报失败 bookingNo={}", event.bookingNo(), t);
        }
    }

    /**
     * 有人买了商品。
     *
     * <p>发布点是购买单落账那一刻（{@code ProductPaymentTargetHandler#markPaid}）——
     * 网页端扫码付款、群里传付款截图两条路都汇聚在那里，
     * 所以「上传付款截图」与「付款成功」两种时机天然各播一次：不会重、也不会漏。
     *
     * <p>这条播报<b>不带金额</b>，所有群同一份文本 —— 与到店、包场播报同理。
     * 「还剩多少」报的是<b>可售量</b>（库存 − 未支付的待支付单占掉的），
     * 与商城页、{@code /菜单} 同一口径 —— 报实际库存会造出
     * 「群里说还剩 5 件、下单却说卖完了」。
     *
     * @param event 商品购买事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProductPurchased(ProductPurchasedEvent event) {
        try {
            if (!properties.getBroadcast().isEnabled()) {
                return;
            }
            String text = QqReplyText.productPurchased(
                    displayName(event.userId()), event.productName(),
                    event.quantity(), availableStockOf(event.productId()));
            broadcast(text, null);
            log.debug("[QQ机器人] 已播报商品购买 orderNo={}", event.orderNo());
        } catch (Throwable t) {
            // 见类注释：这里的异常绝不能冒回付款那边去
            log.error("[QQ机器人] 商品购买播报失败 orderNo={}", event.orderNo(), t);
        }
    }

    /**
     * 停业时段新增或撤销了。
     *
     * <p><b>撤销也播</b>：群里看过「明天 10:00 不营业」之后，撤销若不播，
     * 那条消息就永不过期 —— 顾客照着旧消息改了行程，到了门口才发现白跑一趟。
     *
     * <p>这条播报不带金额，所有群同一份文本。
     *
     * @param event 停业变更事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onClosureChanged(ClosureChangedEvent event) {
        try {
            if (!properties.getBroadcast().isEnabled()) {
                return;
            }
            String text = QqReplyText.closureChanged(event.action(), event.startAt(),
                    event.endAt(), event.reason(), LocalDate.now());
            broadcast(text, null);
            log.debug("[QQ机器人] 已播报停业变更 closureId={} action={}",
                    event.closureId(), event.action());
        } catch (Throwable t) {
            // 见类注释：这里的异常绝不能冒回停业那边去
            log.error("[QQ机器人] 停业播报失败 closureId={}", event.closureId(), t);
        }
    }

    /**
     * 免费活动新增或撤销了。
     *
     * <p>与停业同理<b>撤销也播</b>：「今晚免费」进了群，总会有人为它跑一趟；
     * 撤销不播的话，这些人到了才发现不免费 —— 比从没说过更让人不快。
     *
     * @param event 免费活动变更事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFreePeriodChanged(FreePeriodChangedEvent event) {
        try {
            if (!properties.getBroadcast().isEnabled()) {
                return;
            }
            String text = QqReplyText.freePeriodChanged(event.action(), event.startAt(),
                    event.endAt(), event.reason(), LocalDate.now());
            broadcast(text, null);
            log.debug("[QQ机器人] 已播报免费活动变更 periodId={} action={}",
                    event.periodId(), event.action());
        } catch (Throwable t) {
            // 见类注释：这里的异常绝不能冒回计费那边去
            log.error("[QQ机器人] 免费活动播报失败 periodId={}", event.periodId(), t);
        }
    }

    /**
     * 一场已付款的包场被撤销了。
     *
     * <p>补的是「已经播过的生效消息现在过期了」：包场生效时说过
     * 「该时段仅限包场人与被邀请者入场」，撤销之后那条消息就成了假消息，
     * 而群消息不会自己消失 —— 这一条让群里的信息自己闭合。
     *
     * <p>⚠️ <b>本监听器比别的多一个 {@code fallbackExecution = true}</b>：
     * 发布点 {@code BookingRefundService#revoke} <b>没有 {@code @Transactional}</b>
     * （占位靠 SQL 的状态守卫保证并发安全，不靠事务锁），而默认的 {@code false}
     * 在「发布时没有事务」的情况下会把事件<b>静默丢弃</b> ——
     * 表现是「撤销成功、群里一条不播、日志里一个字都没有」。
     * 加 true 之后两种情形都对：<b>有事务</b>时与另外几个监听器完全一样
     * （提交后才跑、回滚不跑）；<b>无事务</b>时监听器在发布点同步执行，
     * 而那时退款状态早已落库，「成功了才播」这条前提依然成立。
     *
     * @param event 包场撤销事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBookingRevoked(BookingRevokedEvent event) {
        try {
            if (!properties.getBroadcast().isEnabled()) {
                return;
            }
            String text = QqReplyText.bookingRevoked(
                    event.startAt(), event.endAt(), LocalDate.now());
            broadcast(text, null);
            log.debug("[QQ机器人] 已播报包场撤销 bookingNo={}", event.bookingNo());
        } catch (Throwable t) {
            // 见类注释：这里的异常绝不能冒回退款那边去
            log.error("[QQ机器人] 包场撤销播报失败 bookingNo={}", event.bookingNo(), t);
        }
    }

    /**
     * 查一件商品当前的可售量。
     *
     * <p>查不到时返回 {@code null}（商品被删了），由文案层降级成
     * 不带「还剩」的那一版 —— 「谁买了什么」是这条播报的主干，
     * 剩余量只是附加信息，不该因为查不到它把整条播报丢掉。
     *
     * @param productId 商品 ID
     * @return 可售量；商品不存在时为 null
     */
    private Integer availableStockOf(Long productId) {
        BizResult<ProductVo> result = productService.detail(productId);
        return result.isSuccess() ? result.getData().getAvailableStock() : null;
    }

    /**
     * 一条公告发布了（手写与自动都走这里）。
     *
     * <p>⚠️ <b>本监听器比其他几个多一个 {@code fallbackExecution = true}</b>：
     * {@code NoticeService#publishAuto} 刻意不标 {@code @Transactional}
     * （它是给别的模块当副作用调的，契约是「与调用方同事务」而非「自己开事务」）。
     * 默认的 {@code false} 在「发布时没有事务」的情况下会把事件<b>静默丢弃</b> ——
     * 公告落了库、群里一条不播、日志里一个字都没有，而这正是最该避免的失败方式。
     *
     * <p>加 true 之后两种情形都对：<b>有事务</b>时与另外几个监听器完全一样
     * （提交后才跑、回滚不跑）；<b>无事务</b>时监听器在发布点同步执行，
     * 而那时 insert 已经自动提交，「落库了才播」这条前提依然成立。
     *
     * @param event 公告发布事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNoticePublished(NoticePublishedEvent event) {
        try {
            if (!properties.getBroadcast().isEnabled()) {
                return;
            }
            String text = QqReplyText.noticePublished(event.publishMode(), event.title(),
                    event.content(), webProperties.normalizedBaseUrl() + "/#/notices");
            broadcast(text, null);
            log.debug("[QQ机器人] 已播报公告 noticeId={} 方式={}",
                    event.noticeId(), event.publishMode());
        } catch (Throwable t) {
            // 见类注释：这里的异常绝不能冒回公告发布那边去
            log.error("[QQ机器人] 公告播报失败 noticeId={}", event.noticeId(), t);
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
     * 取用户的 QQ 号，用于 @ 人。
     *
     * <p>查不到、或没绑 QQ 时返回 null —— 调用方据此<b>降级成一条不带 @
     * 的普通群消息，而不是干脆不发</b>：提醒本身比 @ 到人重要，
     * 何况群里的人多半能从上下文认出说的是谁。
     *
     * <p>与 {@link #displayName} 一样<b>现查库</b>而不从事件里带：
     * 事件里的 QQ 号会是一个「发布时刻的快照」，而用户可能刚改过绑定。
     *
     * @param userId 用户 ID，可为 null
     * @return QQ 号；查不到时为 null
     */
    private String qqOf(Long userId) {
        if (userId == null) {
            return null;
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || user.getQq() == null || user.getQq().isBlank()) {
            return null;
        }
        return user.getQq();
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
