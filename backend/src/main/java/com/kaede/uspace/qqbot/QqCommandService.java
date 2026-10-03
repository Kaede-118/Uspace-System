package com.kaede.uspace.qqbot;

import com.kaede.uspace.billing.BillingProperties;
import com.kaede.uspace.billing.dto.BillingRulesVo;
import com.kaede.uspace.common.config.WebProperties;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.device.DeviceService;
import com.kaede.uspace.device.dto.EquipmentTypeVo;
import com.kaede.uspace.order.InstoreService;
import com.kaede.uspace.order.OrderProperties;
import com.kaede.uspace.order.OrderService;
import com.kaede.uspace.order.OrderStatus;
import com.kaede.uspace.order.dto.InstoreUserVo;
import com.kaede.uspace.order.dto.MonthSpentVo;
import com.kaede.uspace.order.dto.OrderPreviewVo;
import com.kaede.uspace.order.dto.OrderStatsVo;
import com.kaede.uspace.order.dto.OrderVo;
import com.kaede.uspace.product.ProductService;
import com.kaede.uspace.product.dto.ProductVo;
import com.kaede.uspace.promotion.MonthlyCardService;
import com.kaede.uspace.promotion.entity.MonthlyCard;
import com.kaede.uspace.qqbot.protocol.OneBotEvent;
import com.kaede.uspace.space.BookingService;
import com.kaede.uspace.space.StoreService;
import com.kaede.uspace.space.dto.BookingScheduleVo;
import com.kaede.uspace.space.dto.StoreStatusVo;
import com.kaede.uspace.user.QqVerifyService;
import com.kaede.uspace.user.entity.SysUser;
import com.kaede.uspace.user.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 处理群消息指令（模块 11）。
 *
 * <p>它是「群消息进来」这条链路的终点：校验来源、去重、解析、取数、回话。
 * 两条<b>写指令</b>的编排不在这里，转发给 {@link QqWriteCommandService}；
 * 所有文案组装都在 {@link QqReplyText} 里（纯静态、可脱离 Spring 单测）。
 *
 * <h3>处理顺序不能换</h3>
 *
 * <ol>
 *   <li><b>只处理群消息</b> —— 私聊、通知、请求一律不管</li>
 *   <li><b>过滤机器人自己发的</b> —— 防的是 NapCat 打开 {@code reportSelfMessage}
 *       之后「机器人回复自己、再触发一次回复」的死循环</li>
 *   <li><b>群白名单</b> —— 不在名单里的群<b>静默忽略</b>，连一个字都不回。
 *       回一句「本群未授权」等于告诉对方「这里有个机器人，去别的群试试」</li>
 *   <li><b>幂等</b> —— 见 {@link MessageDedup}</li>
 *   <li><b>解析并执行</b></li>
 * </ol>
 *
 * <h3>⚠️ 一条贯穿全类的纪律：执行必有回响</h3>
 *
 * <p>2026-10-04 与用户确认：<b>凡是被执行的指令，群里必须看到一条消息</b>。
 * 允许的「没有任何回应」只有两种，且都不是静默执行：
 * <ul>
 *   <li><b>确实没调用</b> —— 闲聊（{@link QqCommand.Kind#IGNORE}）、未授权群、
 *       重复消息、以及验证码不匹配（那串数字根本不是这个人的验证码）</li>
 *   <li><b>NapCat 挂了</b> —— 连接断了，消息发不出去；那种情况下指令也收不到</li>
 * </ul>
 * 因此每个 {@code reply*} 方法、以及 {@link QqWriteCommandService} 里
 * 每一条失败分支，<b>都必须回一句</b>，哪怕只是「查不到，稍后再试」。
 * 中间态尤其不能漏：比如「订单建好了但密码没取到」—— 那时人在被计费，
 * 群里静悄悄的话他只会站在门口等一串永远不来的密码。
 *
 * <h3>依赖方向</h3>
 *
 * <p>本类调 {@link InstoreService}（模块 8）、{@link DeviceService}（模块 4）、
 * {@link BookingService}（模块 3）、{@link StoreService}（模块 3）、
 * {@link MonthlyCardService}（模块 9）、{@link OrderService}（模块 8），
 * 都是<b>只读查询</b>。反方向（那些包 import 本包）是禁止的，见包注释。
 *
 * <p>{@code DeviceService} 那条依赖是为「偏好」而引的：{@code InstoreUserVo.preference}
 * 里存的是 {@code PAIPAI} 这样的字典 code，中文名要另外映射 ——
 * 那个 VO 的注释写着「中文名由前端映射，后端为此引入依赖边不划算」，
 * 但那条理由针对的是 {@code order → device}（会与既有的 {@code device → space} 交织）。
 * 本包在最下游，加这些边不产生任何环，所以这里直接映射，把中文名送给群。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "uspace.qqbot.enabled", havingValue = "true")
public class QqCommandService {

    private final QqbotProperties properties;

    private final MessageDedup dedup;

    private final OneBotClient client;

    private final InstoreService instoreService;

    private final QqVerifyService qqVerifyService;

    private final DeviceService deviceService;

    private final BookingService bookingService;

    private final StoreService storeService;

    private final MonthlyCardService monthlyCardService;

    private final OrderService orderService;

    private final ProductService productService;

    private final SysUserMapper sysUserMapper;

    private final BillingProperties billingProperties;

    private final OrderProperties orderProperties;

    private final WebProperties webProperties;

    private final QqWriteCommandService writeCommandService;

    private final QqPaymentProofService paymentProofService;

    public QqCommandService(QqbotProperties properties,
                            MessageDedup dedup,
                            OneBotClient client,
                            InstoreService instoreService,
                            QqVerifyService qqVerifyService,
                            DeviceService deviceService,
                            BookingService bookingService,
                            StoreService storeService,
                            MonthlyCardService monthlyCardService,
                            OrderService orderService,
                            ProductService productService,
                            SysUserMapper sysUserMapper,
                            BillingProperties billingProperties,
                            OrderProperties orderProperties,
                            WebProperties webProperties,
                            QqWriteCommandService writeCommandService,
                            QqPaymentProofService paymentProofService) {
        this.properties = properties;
        this.dedup = dedup;
        this.client = client;
        this.instoreService = instoreService;
        this.qqVerifyService = qqVerifyService;
        this.deviceService = deviceService;
        this.bookingService = bookingService;
        this.storeService = storeService;
        this.monthlyCardService = monthlyCardService;
        this.orderService = orderService;
        this.productService = productService;
        this.sysUserMapper = sysUserMapper;
        this.billingProperties = billingProperties;
        this.orderProperties = orderProperties;
        this.webProperties = webProperties;
        this.writeCommandService = writeCommandService;
        this.paymentProofService = paymentProofService;
    }

    /**
     * 处理一条入站事件。
     *
     * <p>非群消息、机器人自己发的、未授权群的、重复的 —— 四种情况都直接返回，
     * 不产生任何回复。这是「确实没调用」，不是静默执行：<b>此时业务代码一行都没跑</b>。
     * 除此之外的每一条路径都会往群里说话（见类注释里那条纪律）。
     *
     * @param event 入站事件。调用方保证非 null
     */
    public void handle(OneBotEvent event) {
        if (!event.isGroupMessage()) {
            return;
        }
        Long groupId = event.getGroupId();
        if (!properties.isGroupAllowed(groupId)) {
            log.warn("[QQ机器人] 收到未授权群的消息，已忽略 groupId={} sender={}",
                    groupId, event.getUserId());
            return;
        }
        if (dedup.isDuplicate(event.getMessageId())) {
            return;
        }
        // 带图的消息先看一眼：它可能是「结账后发来的付款截图」。
        // ⚠️ 必须放在解析【之前】—— 图片消息的 raw_message 只有一段 [CQ:image,...]，
        // 解析出来必然是闲聊，交给指令那条路等于白跑一趟。
        // 是不是凭证由 QqPaymentProofService 自己判（没在等就静默，见那边的类注释）
        if (event.hasImage()) {
            paymentProofService.handleImages(event);
            return;
        }

        QqCommand command = QqCommandParser.parse(event.getRawMessage());

        // 自己这一侧发的消息（含「运营者用同一个 QQ 在手机上发指令」，
        // 两者在协议字段上无法区分）：只有【解析器认不出来】才忽略。
        //
        // ⚠️ 判据是「解析结果是不是闲聊」，**不是**「有没有前缀」—— 后者是本类
        // 踩过的一个真实故障：验证码当时不带前缀，于是同号登录 bot 时，
        // 运营者在手机上发的验证码被当成「机器人自己发的话」挡掉，
        // 表现是取码正常、发到群里也正常，但**验证永远通不过，且两端都没有任何报错**。
        //
        // 换成这个判据之后，回环仍然被切断：机器人发出去的那几种文本
        // （`pong`、在店名册、帮助、验证结果）没有一种能解析成指令，
        // 全都落到 IGNORE 上。而带前缀的真指令一律放行。
        if (event.isSelfSent() && command.kind() == QqCommand.Kind.IGNORE) {
            log.debug("[QQ机器人] 自己发的消息且解析为闲聊，忽略（防回环）");
            return;
        }
        switch (command.kind()) {
            case PING -> client.sendGroupMessage(groupId, "pong");
            case INSTORE -> replyInstore(groupId);
            case HELP -> replyHelp(groupId);
            case VERIFY_CODE -> replyVerifyCode(groupId, event, command.argument());
            case BOOKING_SCHEDULE -> replyBookingSchedule(groupId);
            case ME -> replyMe(groupId, event.getUserId());
            case CURRENT_ORDER -> replyNow(groupId, event.getUserId());
            case STORE_STATUS -> replyStoreStatus(groupId);
            case PRICE -> replyPrice(groupId);
            case WEB -> replyWeb(groupId);
            case PRODUCT_MENU -> replyMenu(groupId);
            case PRODUCT_ORDER -> writeCommandService.orderProduct(
                    groupId, event.getUserId(), command.argument(), command.quantity());
            case OPEN_DOOR -> writeCommandService.openDoor(groupId, event.getUserId());
            case SETTLE -> writeCommandService.settle(groupId, event.getUserId());
            // ⚠️ 文案与「商品名没匹配上、又没写数量」共用一份，见 QqReplyText.unknownCommand
            case UNKNOWN_COMMAND ->
                    client.sendGroupMessage(groupId, QqReplyText.unknownCommand());
            case IGNORE -> {
                // 闲聊，按设计静默 —— 这是「确实没调用」
            }
        }
    }

    // ==================================================================
    // 各指令的回复
    // ==================================================================

    /**
     * 回复当前在店名册。
     *
     * <p>数据源是模块 8 的 {@link InstoreService#listInstoreUsers()} ——
     * 与 Web 端「在店用户」页<b>同一个方法</b>，所以两处的口径必然一致，
     * 不会出现「群里说 3 人、网页说 2 人」。
     *
     * @param groupId 目标群号
     */
    private void replyInstore(Long groupId) {
        BizResult<List<InstoreUserVo>> result = instoreService.listInstoreUsers();
        if (!result.isSuccess() || result.getData() == null) {
            log.error("[QQ机器人] 查在店名册失败：{}",
                    result.resolveMessage() == null ? "未知原因" : result.resolveMessage());
            client.sendGroupMessage(groupId, "查不到在店信息，稍后再试");
            return;
        }
        client.sendGroupMessage(groupId, QqReplyText.instore(
                result.getData(), loadPreferenceLabels(), QqReplyText.DEFAULT_MAX_LISTED));
    }

    /**
     * 回复指令列表。
     *
     * <p>两条写指令只在<b>确实可用</b>时才列出来（写开关与播报开关都开着）——
     * 列出来却发不动，用户只会以为机器人坏了。
     *
     * @param groupId 目标群号
     */
    private void replyHelp(Long groupId) {
        boolean writeUsable = properties.isWriteEnabled() && properties.getBroadcast().isEnabled();
        client.sendGroupMessage(groupId, QqReplyText.help(writeUsable));
    }

    /**
     * 处理疑似验证码的消息。
     *
     * <p>⚠️ <b>这两个参数的分工是整个 QQ 验证的地基</b>：
     * 发送者 QQ 取自<b>事件</b>（NapCat 推来的，用户伪造不了），
     * 验证码取自<b>消息文本</b>（群里所有人都看得见，本身不是凭证）。
     * {@link QqVerifyService#confirm} 按发送者 QQ 查表 ——
     * 改成「拿验证码遍历所有记录找匹配」的话，谁看到那串数字谁就能验证，
     * <b>而那种写法一样能跑通全部演示</b>。
     *
     * <p>验证码不匹配时 {@code confirm} 返回的 reply 是空串，本方法据此<b>保持沉默</b> ——
     * 那条消息并没有触发任何业务动作（他发的是别人的验证码、或随手打的数字），
     * 属于「确实没调用」。回一句「验证码不正确」只会把群刷得很难看。
     *
     * @param groupId 目标群号
     * @param event   入站事件，发送者 QQ 从它取
     * @param code    解析出的 6 位数字
     */
    private void replyVerifyCode(Long groupId, OneBotEvent event, String code) {
        QqVerifyService.ConfirmResult result =
                qqVerifyService.confirm(String.valueOf(event.getUserId()), code);
        if (!result.reply().isEmpty()) {
            client.sendGroupMessage(groupId, result.reply());
        }
    }

    /**
     * 回复近期包场时间表。
     *
     * <p>提前量取自 {@link OrderProperties}，不写死 15 ——
     * 那是规则的一部分，而规则只有一处定义。
     *
     * @param groupId 目标群号
     */
    private void replyBookingSchedule(Long groupId) {
        BizResult<List<BookingScheduleVo>> result = bookingService.listSchedule(null);
        if (!result.isSuccess() || result.getData() == null) {
            log.error("[QQ机器人] 查包场时间表失败：{}",
                    result.resolveMessage() == null ? "未知原因" : result.resolveMessage());
            client.sendGroupMessage(groupId, "查不到包场安排，稍后再试");
            return;
        }
        client.sendGroupMessage(groupId, QqReplyText.bookingSchedule(
                result.getData(), orderProperties.getBookingLeadDuration(), LocalDate.now()));
    }

    /**
     * 回复「看看自己」：资料、月卡、消费，在店时补上实时状态。
     *
     * <p>在店时长复用 {@code InstoreUserVo.stayMinutes}（与在店名册同源、实时算），
     * 预估金额用结账预览那个<b>零副作用</b>的查询 —— 不用结算，
     * 看一眼不会停表、也不会撤销密码。
     *
     * @param groupId 目标群号
     * @param qq      发送者 QQ
     */
    private void replyMe(Long groupId, Long qq) {
        SysUser user = sysUserMapper.selectByQq(String.valueOf(qq));
        if (user == null) {
            client.sendGroupMessage(groupId, QqReplyText.NOT_BOUND);
            return;
        }
        Long userId = user.getId();

        MonthlyCard card = monthlyCardService.findActiveCard(userId, LocalDate.now());
        BizResult<MonthSpentVo> month = orderService.monthSpent(userId);
        BizResult<OrderStatsVo> stats = orderService.stats(userId);
        BizResult<OrderVo> unsettled = orderService.findUnsettledOrder(userId);

        client.sendGroupMessage(groupId, QqReplyText.me(user, card,
                month.isSuccess() ? month.getData() : null,
                stats.isSuccess() ? stats.getData() : null,
                unsettled.isSuccess() ? unsettled.getData() : null,
                preferenceText(user.getPreference()),
                currentPreview(userId), properties.isSelfAmountVisible()));
    }

    /**
     * 看当前这一单：<b>只预览、不停表</b>。
     *
     * <p>数据源是 {@link OrderService#previewOrder} —— 网页端点「结账」进去看到的那一屏，
     * <b>纯查询、零副作用</b>（计时照走、状态不变、绝不撤销门锁密码）。
     * 想真的结账要再发 {@code /结账}：两个动作分开，是为了防
     * 「只是想看一眼多少钱、结果把表停了」。
     *
     * <p>没有在计时的单时，把待付款那一笔告诉他 —— 那时他问的其实也是这件事。
     *
     * @param groupId 目标群号
     * @param qq      发送者 QQ
     */
    private void replyNow(Long groupId, Long qq) {
        SysUser user = sysUserMapper.selectByQq(String.valueOf(qq));
        if (user == null) {
            client.sendGroupMessage(groupId, QqReplyText.NOT_BOUND);
            return;
        }
        Long userId = user.getId();

        OrderPreviewVo preview = currentPreview(userId);
        if (preview == null) {
            replyPendingPayment(groupId, userId);
            return;
        }
        client.sendGroupMessage(groupId, QqReplyText.currentOrder(
                preview, properties.isSelfAmountVisible()));
    }

    /**
     * 取当前这一单的结账预览；不在店时为 null。
     *
     * <p>「看看自己」与「当前订单」两条共用它 —— 各写一份的话，
     * 一个走 {@code findCurrentOrder}、另一个忘了判断、或者一处改了口径，
     * 表现是同一个人在群里问两次得到两个不同的数。
     *
     * @param userId 用户 ID
     * @return 预览；不在店、或查不到时返回 null
     */
    private OrderPreviewVo currentPreview(Long userId) {
        BizResult<OrderVo> active = orderService.findCurrentOrder(userId);
        if (!active.isSuccess() || active.getData() == null) {
            return null;
        }
        BizResult<OrderPreviewVo> preview =
                orderService.previewOrder(userId, active.getData().getId());
        return preview.isSuccess() ? preview.getData() : null;
    }

    /**
     * 没有在计时的单时：把待付款那一笔告诉他，一条都没有就说一句人话。
     *
     * @param groupId 目标群号
     * @param userId  用户 ID
     */
    private void replyPendingPayment(Long groupId, Long userId) {
        BizResult<OrderVo> result = orderService.findUnsettledOrder(userId);
        OrderVo pending = result.isSuccess() ? result.getData() : null;
        if (pending != null && OrderStatus.PENDING_PAYMENT.name().equals(pending.getStatus())) {
            client.sendGroupMessage(groupId, QqReplyText.noActiveOrder(pending.getOrderNo(),
                    pending.getPayableAmount(), properties.isSelfAmountVisible(), ordersUrl()));
            return;
        }
        client.sendGroupMessage(groupId, "你现在没有在计时的订单。发 /开门 开始计时。");
    }

    /**
     * 取偏好中文名。
     *
     * <p>映射交给 {@link QqReplyText#preference}（名册用的是同一个方法）——
     * 两处各写一份的话，同一个偏好会在群里出现两种写法。
     *
     * @param preference 逗号分隔的类型 code 串，可为 null
     * @return 顿号分隔的中文名；没设偏好、或一个都映射不出时返回 null
     */
    private String preferenceText(String preference) {
        if (preference == null || preference.isBlank()) {
            return null;
        }
        return QqReplyText.preference(preference, loadPreferenceLabels());
    }

    /**
     * 回复门店营业状态。
     *
     * <p>在店人数只是附加信息：查不到就省掉那一句，状态本身照常报。
     *
     * @param groupId 目标群号
     */
    private void replyStoreStatus(Long groupId) {
        BizResult<StoreStatusVo> result = storeService.getStoreStatus();
        if (!result.isSuccess() || result.getData() == null) {
            log.error("[QQ机器人] 查门店状态失败：{}",
                    result.resolveMessage() == null ? "未知原因" : result.resolveMessage());
            client.sendGroupMessage(groupId, "查不到门店状态，稍后再试");
            return;
        }
        Integer instoreCount = null;
        BizResult<List<InstoreUserVo>> instore = instoreService.listInstoreUsers();
        if (instore.isSuccess() && instore.getData() != null) {
            instoreCount = instore.getData().size();
        }
        client.sendGroupMessage(groupId, QqReplyText.storeStatus(result.getData(), instoreCount));
    }

    /**
     * 回复计费规则摘要。
     *
     * <p>价目表直接由 {@link BillingRulesVo#from} 从配置算出，不走 HTTP ——
     * 与用户端「计费规则」页是同一份数据，群里的数与网页上的数不会分岔。
     *
     * @param groupId 目标群号
     */
    private void replyPrice(Long groupId) {
        client.sendGroupMessage(groupId, QqReplyText.price(
                BillingRulesVo.from(billingProperties)));
    }

    /**
     * 回复商城菜单：店里卖的东西与价格。
     *
     * <p>数据源是 {@link ProductService#listOnSale()} —— 与用户端商城页<b>同一个方法</b>，
     * 所以群里报的价与网页上看到的必然一致，不会出现「群里说 3 块、下单变 5 块」。
     *
     * <p>菜单只报价格与售罄，<b>下单仍然只在网页端</b> —— 买商品要扣库存、要付款，
     * 那条链路走的是模块 8 的统一支付入口，与房间时长计费是两套账。
     *
     * @param groupId 目标群号
     */
    private void replyMenu(Long groupId) {
        BizResult<List<ProductVo>> result = productService.listOnSale();
        if (!result.isSuccess() || result.getData() == null) {
            log.error("[QQ机器人] 查商城商品失败：{}",
                    result.resolveMessage() == null ? "未知原因" : result.resolveMessage());
            client.sendGroupMessage(groupId, "查不到商品，稍后再试");
            return;
        }
        client.sendGroupMessage(groupId, QqReplyText.menu(
                result.getData(), baseUrl() + "/#/mall"));
    }

    /**
     * 回复网页端地址。
     *
     * <p>群里新来的人第一句常问「在哪儿下单」，而答案永远是一个网址。
     *
     * <p><b>地址取自 {@code uspace.web.base-url}</b>（与包场邀请链接同一项）——
     * 它是<b>唯一那一处</b>要改的地方：投产时把它写成域名，本机开发时写成
     * 局域网地址（如 {@code http://192.168.1.118:5173}），否则发出去的
     * {@code localhost} 只会在对方自己的手机上打开。
     *
     * <p><b>不做自动探测</b>：一台机器上可能挂着 VPN、WSL、虚拟机的网卡，
     * 「哪个才连得上」没有确定答案，而一个猜出来的地址错了比配错了更难查 ——
     * 配错了看得出来，猜错了要等到有人点不开才知道。
     *
     * @param groupId 目标群号
     */
    private void replyWeb(Long groupId) {
        client.sendGroupMessage(groupId, QqReplyText.web(baseUrl()));
    }

    /**
     * 取站点基地址并去掉末尾斜杠。
     *
     * <p>与 {@code QqWriteCommandService} 里那个是同一份三行逻辑（那是第二处出现，
     * 按项目惯例到第三处再抽）。配置为空时退化成站内相对路径 —— 链接点不开，
     * 但至少不会拼出一串 {@code null/...}。
     *
     * @return 形如 {@code https://xxx.com}；没配置时返回空串
     */
    private String baseUrl() {
        String url = webProperties.getBaseUrl();
        if (url == null || url.isBlank()) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * 订单列表页地址（待支付的单也在那儿）。
     *
     * <p>与 {@code QqWriteCommandService} 里同名那个是<b>同一份三行逻辑</b>
     * （那是第二处出现，按本项目惯例到第三处再抽）。{@code baseUrl()} 同理。
     *
     * @return 形如 {@code http://host/#/orders}
     */
    private String ordersUrl() {
        return baseUrl() + "/#/orders";
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /**
     * 取类型字典的 code → 中文名映射。
     *
     * <p>每次查询都取一次，<b>不缓存</b>：这张表只有几行、改动的频率以月计，
     * 而缓存会引入「管理员改了类型名、群里还是旧名字」这种要等到有人注意到
     * 才能发现的不一致。
     *
     * @return 映射；取字典失败时返回空表（此时偏好一栏整体不显示，不影响名册本身）
     */
    private Map<String, String> loadPreferenceLabels() {
        BizResult<List<EquipmentTypeVo>> result = deviceService.listSelectableTypes();
        if (!result.isSuccess() || result.getData() == null) {
            log.warn("[QQ机器人] 取设备类型字典失败，本次名册不显示偏好");
            return Map.of();
        }
        Map<String, String> labels = new HashMap<>();
        for (EquipmentTypeVo type : result.getData()) {
            labels.put(type.getCode(), type.getName());
        }
        return labels;
    }
}
