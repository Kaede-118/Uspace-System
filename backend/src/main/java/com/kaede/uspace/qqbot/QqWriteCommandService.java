package com.kaede.uspace.qqbot;

import com.kaede.uspace.common.config.WebProperties;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.order.OneTimePasscodeService;
import com.kaede.uspace.order.OrderService;
import com.kaede.uspace.order.OrderStatus;
import com.kaede.uspace.order.PaymentTargetType;
import com.kaede.uspace.order.dto.CreateOrderRequest;
import com.kaede.uspace.order.dto.OneTimePasscodeVo;
import com.kaede.uspace.order.dto.OrderOpenVo;
import com.kaede.uspace.order.dto.OrderSettleVo;
import com.kaede.uspace.order.dto.OrderVo;
import com.kaede.uspace.product.ProductService;
import com.kaede.uspace.product.dto.CreateProductOrderRequest;
import com.kaede.uspace.product.dto.ProductOrderVo;
import com.kaede.uspace.product.dto.ProductVo;
import com.kaede.uspace.user.entity.SysUser;
import com.kaede.uspace.user.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 两条写指令的编排（模块 11）：{@code /开门} 与 {@code /结账}。
 *
 * <p>它是 {@code qqbot} 包里<b>唯一会改变业务状态</b>的类，所以这里的每一条规矩
 * 都是为了让「群里说一句话」这件事不至于变成一笔没人看得见的账。
 *
 * <h3>规矩一：没有播报就不执行</h3>
 *
 * <p>{@code uspace.qqbot.broadcast.enabled=false} 时这两条指令<b>直接拒绝</b>，
 * 不建单、不结算、不碰门锁云。理由与「机器人只做只读」是同一个：
 * <b>群里的公示正是这套设计敢把写操作放进群里的依据</b> ——
 * 开门与结账都会产生账单，而播报是「谁在什么时候进出了、花了多少」
 * 唯一的公开记录。播报关掉时它们就成了无声的操作，那还不如去网页端做。
 *
 * <h3>规矩二：写操作全部走订单模块的事务方法</h3>
 *
 * <p>本类<b>不碰任何 Mapper</b>，只用 {@link OrderService}、
 * {@link OneTimePasscodeService} 与 {@link ProductService} 这几个 Service ——
 * 建单与结算各自是一个事务，由它们各自的模块保证「要么全成、要么全不成」。
 * 商品之所以也走 Service 而不是自己拼 SQL：库存与可售量的口径
 * （「库存 − 未支付的待支付单」）只有一处定义，群里报的数才不会与网页上打架。
 *
 * <p>⚠️ <b>但整条 {@code /开门} 不是一个事务</b>，这是刻意的：
 * 「取一次性密码」要调门锁云（外部调用，无法回滚）。把它包进事务里的话，
 * 失败回滚会留下「订单没了、但锁上密码已经下发」的状态 ——
 * 用户收到一句失败提示，手上却有一串能开门的密码，比现在
 * 「订单在、密码没取到，回一句话让他去网页端看固定密码」更难收拾。
 *
 * <h3>规矩三：密码与金额的边界</h3>
 *
 * <ul>
 *   <li><b>一次性密码</b>只出现在发出指令的那个群（群里所有人都看得见，这是已知取舍）</li>
 *   <li><b>固定限时密码</b>只走私聊，群里永远不出现；私聊发不出去时靠群回复里的
 *       网页端指路兜底</li>
 *   <li><b>金额</b>只在「本人主动发起」的回复里出现（{@code /结账} 的应付、
 *       {@code /商品名-数量} 的合计），且受
 *       {@link QqbotProperties#isSelfAmountVisible()} 控制 ——
 *       系统主动推的播报走的是另一条（按群分级），两者不要互相推断</li>
 * </ul>
 *
 * @see QqCommandService#handle 的上游分发
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "uspace.qqbot.enabled", havingValue = "true")
public class QqWriteCommandService {

    /** 没开播报时的拒绝话术。它必须解释清楚「为什么不让用」，否则用户只会以为机器人坏了 */
    private static final String NO_BROADCAST_HINT =
            "群播报没有开启，开门与结账暂时不能从群里操作 —— "
                    + "这两件事要在群里留痕，看不到播报就不执行。可以到网页端操作。";

    /** 总开关关掉时的拒绝话术 */
    private static final String WRITE_DISABLED_HINT =
            "群里没有开放「开门 / 结账」这两个指令，到网页端操作吧。";

    private final QqbotProperties properties;

    private final OneBotClient client;

    private final SysUserMapper sysUserMapper;

    private final OrderService orderService;

    private final OneTimePasscodeService oneTimePasscodeService;

    private final ProductService productService;

    private final QqPaymentProofService paymentProofService;

    private final WebProperties webProperties;

    /** 时钟。由 {@code QqVerifyConfig} 提供的 bean 注入，测试时可拨钟 */
    private final Clock clock;

    /** 每个 QQ 上一次写操作的时刻，用于冷却。只在内存里，重启即清 */
    private final Map<Long, LocalDateTime> lastWriteAt = new ConcurrentHashMap<>();

    public QqWriteCommandService(QqbotProperties properties,
                                 OneBotClient client,
                                 SysUserMapper sysUserMapper,
                                 OrderService orderService,
                                 OneTimePasscodeService oneTimePasscodeService,
                                 ProductService productService,
                                 QqPaymentProofService paymentProofService,
                                 WebProperties webProperties,
                                 Clock clock) {
        this.properties = properties;
        this.client = client;
        this.sysUserMapper = sysUserMapper;
        this.orderService = orderService;
        this.oneTimePasscodeService = oneTimePasscodeService;
        this.productService = productService;
        this.paymentProofService = paymentProofService;
        this.webProperties = webProperties;
        this.clock = clock;
    }

    // ==================================================================
    // /开门
    // ==================================================================

    /**
     * 开门计时：给这个人一串能开门的密码，必要时先帮他建一笔订单。
     *
     * <p>三种情况合成一个动作，用户不必知道区别：
     * <ol>
     *   <li>没有进行中的订单 → 建单（会触发到店播报）+ 发密码</li>
     *   <li>已有进行中的订单 → <b>不建新单</b>，直接发密码
     *       （他在网页端开的单、或刚才发过一次 {@code /开门}）</li>
     *   <li>上一单还没付款 → 订单模块会拒绝，这里把拒绝理由翻译成人话</li>
     * </ol>
     *
     * @param groupId 发出指令的群（密码只回这个群）
     * @param qq      发送者的 QQ 号，来自事件而非消息文本
     */
    public void openDoor(Long groupId, Long qq) {
        if (!checkAllowed(groupId, qq)) {
            return;
        }
        SysUser user = sysUserMapper.selectByQq(String.valueOf(qq));
        if (user == null) {
            client.sendGroupMessage(groupId, QqReplyText.NOT_BOUND);
            return;
        }
        Long userId = user.getId();

        OrderVo order = currentOrder(userId);
        boolean newlyCreated = false;
        Long orderId;
        if (order != null) {
            orderId = order.getId();
        } else {
            BizResult<OrderOpenVo> opened = orderService.createOrder(userId, new CreateOrderRequest());
            if (!opened.isSuccess()) {
                // 竞态兜底：两条 /开门 挤在一起时，第二条会拿到「已有进行中的订单」。
                // 那【不是错误】—— 用户要的就是密码，重新查一次订单走发密码那条路
                OrderVo retry = opened.getError() == ErrorCode.ORDER_ALREADY_ACTIVE
                        ? currentOrder(userId) : null;
                if (retry == null) {
                    client.sendGroupMessage(groupId, QqReplyText.openFailure(
                            opened.getError(), opened.resolveMessage(), ordersUrl()));
                    return;
                }
                order = retry;
                orderId = retry.getId();
            } else {
                orderId = opened.getData().getOrderId();
                newlyCreated = true;
            }
        }

        // 固定密码走私聊，群里永远不出现 —— 它的有效期覆盖整个订单，泄露面比一次性那串大得多
        sendFixedPasscode(qq, userId, orderId);

        BizResult<OneTimePasscodeVo> issued = oneTimePasscodeService.issue(userId, orderId);
        if (!issued.isSuccess()) {
            // ⚠️ 这里【必须】回话：订单已经建好了（用户正在被计费），
            // 群里静悄悄的话他只会站在门口等一串永远不来的密码
            client.sendGroupMessage(groupId, "已开始计时，但门锁密码没取到："
                    + issued.resolveMessage() + "\n固定密码已私聊发你，也可以到网页端看："
                    + orderUrl(orderId));
            log.error("[QQ机器人] /开门 取一次性密码失败 orderId={} qq={}", orderId, qq);
            return;
        }

        client.sendGroupMessage(groupId, QqReplyText.openPasscode(
                issued.getData().getPasscode(), newlyCreated,
                stayMinutes(order), orderUrl(orderId)));
        log.info("[QQ机器人] /开门 完成 orderId={} 新建={} qq={}", orderId, newlyCreated, qq);
    }

    // ==================================================================
    // /结账
    // ==================================================================

    /**
     * 停止计时并结算；<b>已经停过表的</b>则把待付入口重新给一遍。
     *
     * <p>两种情况收在同一条指令下，因为对用户来说是同一件事 ——「我要把这笔账了结」：
     * 还在计时就停表出账，已经停过表就把待付金额与入口重新给他
     * （见 {@link #replyPendingIfAny}）。
     *
     * <p><b>群里不承载任何资金动作</b> —— 只停表出账、只给链接，钱始终在用户与
     * 支付平台之间走。这一条是「机器人不承载资金动作」的落点：支付要的是支付平台侧的
     * 凭证，而群消息这层鉴权只有「QQ 号 ↔ 账号」的绑定关系，比 Web 端弱。
     * （付款截图可以从群里发，但它<b>只是凭证</b>，真正认账的仍是管理员的复核。）
     *
     * <p>结算成功后还会撑起「等他发付款截图」那道闸，见 {@link QqPaymentProofService} ——
     * 那是群内传图唯一的安全边界，两条分支都必须撑（少了它，群里的表情包
     * 会被当成凭证、订单会被误标成已支付）。
     *
     * @param groupId 发出指令的群
     * @param qq      发送者的 QQ 号
     */
    public void settle(Long groupId, Long qq) {
        if (!checkAllowed(groupId, qq)) {
            return;
        }
        SysUser user = sysUserMapper.selectByQq(String.valueOf(qq));
        if (user == null) {
            client.sendGroupMessage(groupId, QqReplyText.NOT_BOUND);
            return;
        }
        Long userId = user.getId();

        OrderVo order = currentOrder(userId);
        if (order == null) {
            // 没有在计时的订单，但可能有一笔「已停表、待付款」的 —— 那说明他结过账了
            // （网页端结的、刚才在这边结的、或截图等超时了）。见 replyPendingIfAny
            replyPendingIfAny(groupId, user, userId);
            return;
        }

        BizResult<OrderSettleVo> settled = orderService.settleOrder(userId, order.getId());
        if (!settled.isSuccess()) {
            client.sendGroupMessage(groupId, "结账失败：" + settled.resolveMessage()
                    + "\n也可以到网页端结束使用：" + orderUrl(order.getId()));
            return;
        }

        OrderSettleVo vo = settled.getData();
        // 金额取分段账单的合计（实收，已扣月卡与活动减免）——
        // 与订单详情页展示的是同一个数，不必另算一遍
        BigDecimal amount = vo.getBill() == null ? null : vo.getBill().getTotalAmount();
        client.sendGroupMessage(groupId, QqReplyText.settleDone(
                amount, properties.isSelfAmountVisible(), orderUrl(order.getId())));

        // 结算成功了才登记「等他发付款截图」—— 这道闸是那条路唯一的安全边界，
        // 没有它，群里的表情包会被当成凭证、订单会被误标成已支付（见 QqPaymentProofService）。
        // ⚠️ 传的是 user 而不是 userId：等待表以 QQ 号为键（入站事件只给得出它），
        // 而载入目标要的是用户 ID —— 两个 ID 都由实体带着，写不反
        paymentProofService.expect(user, PaymentTargetType.ORDER, order.getId());

        log.info("[QQ机器人] /结账 完成 orderId={} qq={}", order.getId(), qq);
    }

    // ==================================================================
    // /商品名-数量
    // ==================================================================

    /**
     * 下单买商品：找到名字对应的那件、建一笔待支付购买单，并把付款入口给出去。
     *
     * <p><b>名字是顾客打的，不是 ID</b> —— 群里没地方填 ID。这就要求商品名唯一，
     * 由 {@code biz_product} 上那条唯一键 {@code uk_name} 保证
     * （加它的缘由见建表脚本里的说明）。
     *
     * <p><b>数量</b>：{@code /买个可乐} 是 1 件（量词本身说明了数量），
     * {@code /买2个可乐}、{@code /可乐-2} 照他写的 —— 范围（1~99）由商品模块判，
     * 与网页端同一个上限。
     *
     * <p>⚠️ <b>待付款的旧单不拦</b>：商品可以买多笔，不像房间使用费那样
     * 「上一单没结清不给开新单」。拦了等于替顾客做了「不买了」的决定 ——
     * 与「超时的待支付单不自动关闭」是同一条理由。
     *
     * @param groupId  发出指令的群
     * @param qq       发送者的 QQ 号
     * @param name     商品名（解析器原样带出来的，<b>可能不存在</b>）
     * @param quantity 他写明的数量；{@code /买个X} 那种写法传 null，按 1 件算
     */
    public void orderProduct(Long groupId, Long qq, String name, Integer quantity) {
        if (!checkAllowed(groupId, qq)) {
            return;
        }
        SysUser user = sysUserMapper.selectByQq(String.valueOf(qq));
        if (user == null) {
            client.sendGroupMessage(groupId, QqReplyText.NOT_BOUND);
            return;
        }
        Long userId = user.getId();

        BizResult<ProductVo> found = productService.findLiveByName(name);
        ProductVo product = found.isSuccess() ? found.getData() : null;
        if (product == null) {
            client.sendGroupMessage(groupId, QqReplyText.productNotFound(name));
            return;
        }

        CreateProductOrderRequest request = new CreateProductOrderRequest();
        request.setProductId(product.getId());
        request.setQuantity(quantity == null ? 1 : quantity);
        BizResult<ProductOrderVo> created = productService.createOrder(userId, request);
        if (!created.isSuccess()) {
            client.sendGroupMessage(groupId, QqReplyText.productOrderFailure(
                    created.getError(), created.resolveMessage(), mallUrl()));
            log.info("[QQ机器人] 群内下单失败 qq={} 商品={} 数量={} 原因={}",
                    qq, name, request.getQuantity(), created.getError());
            return;
        }

        ProductOrderVo order = created.getData();
        client.sendGroupMessage(groupId, QqReplyText.productOrdered(
                order, properties.isSelfAmountVisible(), productOrdersUrl()));
        // 建单成功才撑起「等他发付款截图」那道闸 —— 与 /结账 是同一条安全边界：
        // 少了它，群里的表情包会被当成凭证，一笔没收到钱的单子当场变成已支付
        paymentProofService.expect(user, PaymentTargetType.PRODUCT, order.getId());

        log.info("[QQ机器人] 群内下单完成 productOrderId={} 单号={} qq={}",
                order.getId(), order.getOrderNo(), qq);
    }

    // ==================================================================
    // 前置检查与内部工具
    // ==================================================================

    /**
     * 写指令的三道前置检查：播报开着、写指令开着、这个人没在连点。
     *
     * <p>任何一道不过都<b>不执行任何写操作</b>，并且已经把拒绝理由发到群里。
     *
     * @param groupId 目标群
     * @param qq      发送者 QQ
     * @return 全部通过返回 true；被拒时返回 false
     */
    private boolean checkAllowed(Long groupId, Long qq) {
        if (!properties.getBroadcast().isEnabled()) {
            client.sendGroupMessage(groupId, NO_BROADCAST_HINT);
            log.info("[QQ机器人] 播报未开启，写指令被拒 qq={}", qq);
            return false;
        }
        if (!properties.isWriteEnabled()) {
            client.sendGroupMessage(groupId, WRITE_DISABLED_HINT);
            return false;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        Duration cooldown = properties.getWriteCommandCooldown();
        LocalDateTime last = lastWriteAt.get(qq);
        if (last != null && cooldown != null && !cooldown.isZero() && last.plus(cooldown).isAfter(now)) {
            long waitSeconds = Duration.between(now, last.plus(cooldown)).getSeconds() + 1;
            client.sendGroupMessage(groupId, "操作太频繁了，请 " + waitSeconds + " 秒后再试。");
            return false;
        }
        lastWriteAt.put(qq, now);
        return true;
    }

    /**
     * 查这个人当前进行中的订单。
     *
     * <p>查询失败与「确实没有」都返回 null —— 调用方对两者的处理相同
     * （没有就建单），而区分它们没有意义。
     *
     * @param userId 用户 ID
     * @return 进行中的订单；没有或查不到时为 null
     */
    private OrderVo currentOrder(Long userId) {
        BizResult<OrderVo> result = orderService.findCurrentOrder(userId);
        return result.isSuccess() ? result.getData() : null;
    }

    /**
     * 没有在计时的订单时，看看有没有「已停表、待付款」的那一笔。
     *
     * <p>⚠️ <b>这条分支是截图那条路的回头路，不是客套话</b>：等图的窗口只有 5 分钟
     * （{@code QqPaymentProofService.WAIT_WINDOW}），超时那句提示写着
     * 「重新发一次 /结账」—— 而那时订单已经<b>不是</b> {@code IN_USE} 了，
     * 按原路走只会得到「你没有在计时的订单」，等图的闸再也撑不起来，
     * 用户发多少张图都毫无反应。所以这里要做两件事：<b>把闸重新撑上</b>
     * （{@link QqPaymentProofService#expect}），并把待付金额与入口重新给一遍。
     *
     * <p>「已停表、还欠着钱」在系统里是常态而非异常 —— 本系统是信任制，
     * 先离场后付款，所以「结过账但还没付」会一直挂着，直到他付掉为止。
     *
     * @param groupId 目标群
     * @param user    发指令的这个人（等图登记要用它的 QQ 与用户 ID）
     * @param userId  用户 ID
     */
    private void replyPendingIfAny(Long groupId, SysUser user, Long userId) {
        BizResult<OrderVo> result = orderService.findUnsettledOrder(userId);
        OrderVo pending = result.isSuccess() ? result.getData() : null;
        if (pending == null || !OrderStatus.PENDING_PAYMENT.name().equals(pending.getStatus())) {
            // 一条未结清的都没有（或那一笔还在计时中，那是另一条分支的事）
            client.sendGroupMessage(groupId,
                    "你现在没有未结清的订单。要开始计时就发 /开门。");
            return;
        }

        paymentProofService.expect(user, PaymentTargetType.ORDER, pending.getId());
        client.sendGroupMessage(groupId, QqReplyText.settleAlreadyStopped(
                pending.getOrderNo(), pending.getPayableAmount(),
                properties.isSelfAmountVisible(), orderUrl(pending.getId())));
        log.info("[QQ机器人] /结账 命中待付款订单，已重新撑起截图入口 orderId={} qq={}",
                pending.getId(), user.getQq());
    }

    /**
     * 把固定限时密码私聊给本人。
     *
     * <p>⚠️ <b>失败只记日志、不改群回复</b>：群回复里本来就带着「没收到就上网页端看」的
     * 兜底指路，而 {@code sendPrivateMessage} 的 true 只代表帧发出去了、
     * 不代表送达（非好友是异步失败的）。这里做不了什么补救，也不必做。
     *
     * @param qq      目标 QQ
     * @param userId  用户 ID
     * @param orderId 订单 ID
     */
    private void sendFixedPasscode(Long qq, Long userId, Long orderId) {
        BizResult<OrderOpenVo> result = orderService.currentPasscode(userId, orderId);
        if (!result.isSuccess() || result.getData() == null) {
            log.warn("[QQ机器人] 取固定密码失败，跳过私聊 orderId={}", orderId);
            return;
        }
        String text = "你的固定门锁密码：" + result.getData().getPasscode()
                + "\n订单期内可反复使用，随时能在网页端查看：" + orderUrl(orderId);
        if (!client.sendPrivateMessage(qq, text)) {
            log.info("[QQ机器人] 私聊固定密码没发出去（可能不是好友）qq={}", qq);
        }
    }

    /**
     * 算这个人已经在店里待了多久。
     *
     * <p>自己算而不是读 {@code stay_minutes} 列 —— 那一列在订单结束前是 NULL，
     * 口径与「在店名册」一致（都是 {@code now − startTime} 向下取整）。
     *
     * @param order 进行中的订单，可为 null
     * @return 分钟数；订单为 null 或没有开始时间时返回 null
     */
    private Integer stayMinutes(OrderVo order) {
        if (order == null || order.getStartTime() == null) {
            return null;
        }
        return (int) Duration.between(order.getStartTime(), LocalDateTime.now(clock)).toMinutes();
    }

    /**
     * 订单详情页地址。
     *
     * <p>⚠️ <b>结算后指向这里而不是 {@code /orders/:id/settle}</b>：后者是「停止计时」的
     * <b>结算预览页</b>，订单一停它就只剩「0 分钟 / ¥0.00」——而 {@code /结账} 恰恰
     * 已经把它停掉了。详情页才是该去的地方：那上面直接挂着付款面板
     * （{@code OrderDetailView} 里的 {@code PaymentPanel}），点开就能付。
     *
     * <p>这个错法 2026-10-04 现场撞到过：群里说「应付 ¥9.00」，点链接进去却显示
     * 「合计 ¥0.00」，用户只会以为账算错了。
     *
     * @param orderId 订单 ID
     * @return 形如 {@code http://host/#/orders/2335}
     */
    private String orderUrl(Long orderId) {
        return baseUrl() + "/#/orders/" + orderId;
    }

    /** @return 订单列表页地址（待支付的单也在那儿） */
    private String ordersUrl() {
        return baseUrl() + "/#/orders";
    }

    /**
     * 商品订单页地址。
     *
     * <p>⚠️ 是 {@code /product-orders} 而不是商城 {@code /mall}：下单之后该去的是
     * <b>那一单的付款入口</b>，而商城页只能重新挑商品。付款面板挂在商品订单页上
     * （那条「去支付」按钮就长在待支付的单子上）。
     *
     * @return 商品订单页地址
     */
    private String productOrdersUrl() {
        return baseUrl() + "/#/product-orders";
    }

    /** @return 商城页地址 —— 下单失败时「换个东西买」的出口 */
    private String mallUrl() {
        return baseUrl() + "/#/mall";
    }

    /**
     * 取站点基地址。
     *
     * <p>逻辑已上收到 {@code WebProperties#normalizedBaseUrl} ——
     * 本类与 {@code QqCommandService} 各写一份，加上后来要发提醒的
     * {@code QqBroadcastListener} 正好是第三处，按项目惯例该合了。
     *
     * @return 形如 {@code https://xxx.com}；没配置时返回空串（链接退化成站内路径）
     */
    private String baseUrl() {
        return webProperties.normalizedBaseUrl();
    }
}
