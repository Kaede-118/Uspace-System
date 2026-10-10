package com.kaede.uspace.qqbot;

import com.kaede.uspace.billing.BillingProperties;
import com.kaede.uspace.billing.dto.BillingRulesVo;
import com.kaede.uspace.common.config.UploadProperties;
import com.kaede.uspace.common.config.WebProperties;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.device.DeviceService;
import com.kaede.uspace.device.dto.DeviceGroupVo;
import com.kaede.uspace.device.dto.EquipmentTypeVo;
import com.kaede.uspace.notice.NoticeService;
import com.kaede.uspace.notice.dto.NoticeVo;
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
import com.kaede.uspace.product.dto.ProductOrderVo;
import com.kaede.uspace.product.dto.ProductVo;
import com.kaede.uspace.promotion.MonthlyCardService;
import com.kaede.uspace.promotion.dto.CardPurchaseVo;
import com.kaede.uspace.promotion.dto.CardTypeVo;
import com.kaede.uspace.promotion.entity.MonthlyCard;
import com.kaede.uspace.qqbot.protocol.OneBotEvent;
import com.kaede.uspace.space.BookingService;
import com.kaede.uspace.space.StoreService;
import com.kaede.uspace.space.dto.BookingScheduleVo;
import com.kaede.uspace.space.dto.BookingVo;
import com.kaede.uspace.space.dto.StoreStatusVo;
import com.kaede.uspace.user.QqVerifyService;
import com.kaede.uspace.user.entity.SysUser;
import com.kaede.uspace.user.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * {@link NoticeService}（公告包）、{@link BookingService}（模块 3）、
 * {@link StoreService}（模块 3）、{@link MonthlyCardService}（模块 9）、
 * {@link OrderService}（模块 8）、{@link ProductService}（商品包），
 * 都是<b>只读查询</b>。反方向（那些包 import 本包）是禁止的，见包注释。
 *
 * <p>{@code DeviceService} 那条依赖最初是为「偏好」而引的：{@code InstoreUserVo.preference}
 * 里存的是 {@code MAIMAI} 这样的字典 code，中文名要另外映射 ——
 * 那个 VO 的注释写着「中文名由前端映射，后端为此引入依赖边不划算」，
 * 但那条理由针对的是 {@code order → device}（会与既有的 {@code device → space} 交织）。
 * 本包在最下游，加这些边不产生任何环，所以这里直接映射，把中文名送给群。
 * 如今它还多供一处：{@code fw机台} 的陈列列表（{@link DeviceService#listForDisplay}
 * 与用户端「店内设施」页同一个方法）。
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

    private final NoticeService noticeService;

    private final BookingService bookingService;

    private final StoreService storeService;

    private final MonthlyCardService monthlyCardService;

    private final OrderService orderService;

    private final ProductService productService;

    private final SysUserMapper sysUserMapper;

    private final BillingProperties billingProperties;

    private final OrderProperties orderProperties;

    private final WebProperties webProperties;

    /**
     * 名册图的图片读取器：把 {@code /uploads/…} 映射到本地上传目录。
     *
     * <p>构造时装配一次（闭包了目录与前缀）—— 连不上/读不到的图它返回 null，
     * 渲染回落首字母底（见 {@code InstoreCardRenderer}），这里不再管失败。
     */
    private final InstoreCardRenderer.ImageLoader instoreImageLoader;

    private final QqWriteCommandService writeCommandService;

    private final QqPaymentProofService paymentProofService;

    public QqCommandService(QqbotProperties properties,
                            MessageDedup dedup,
                            OneBotClient client,
                            InstoreService instoreService,
                            QqVerifyService qqVerifyService,
                            DeviceService deviceService,
                            NoticeService noticeService,
                            BookingService bookingService,
                            StoreService storeService,
                            MonthlyCardService monthlyCardService,
                            OrderService orderService,
                            ProductService productService,
                            SysUserMapper sysUserMapper,
                            BillingProperties billingProperties,
                            OrderProperties orderProperties,
                            WebProperties webProperties,
                            UploadProperties uploadProperties,
                            QqWriteCommandService writeCommandService,
                            QqPaymentProofService paymentProofService) {
        this.properties = properties;
        this.dedup = dedup;
        this.client = client;
        this.instoreService = instoreService;
        this.qqVerifyService = qqVerifyService;
        this.deviceService = deviceService;
        this.noticeService = noticeService;
        this.bookingService = bookingService;
        this.storeService = storeService;
        this.monthlyCardService = monthlyCardService;
        this.orderService = orderService;
        this.productService = productService;
        this.sysUserMapper = sysUserMapper;
        this.billingProperties = billingProperties;
        this.orderProperties = orderProperties;
        this.webProperties = webProperties;
        this.instoreImageLoader = uploadProperties.getDir() == null
                ? url -> null
                : InstoreCardRenderer.localFileLoader(
                        Path.of(uploadProperties.getDir()), uploadProperties.getUrlPrefix());
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
            case HELP -> replyHelp(groupId, event.getUserId());
            case VERIFY_CODE -> replyVerifyCode(groupId, event, command.argument());
            case BOOKING_SCHEDULE -> replyBookingSchedule(groupId);
            case NOTICE_LIST -> replyNotices(groupId);
            case ME -> replyMe(groupId, event.getUserId());
            case CURRENT_ORDER -> replyNow(groupId, event.getUserId());
            case UNPAID_BILLS -> replyUnpaid(groupId, event.getUserId());
            case STORE_STATUS -> replyStoreStatus(groupId);
            case PRICE -> replyPrice(groupId);
            case CARD_TYPES -> replyCardTypes(groupId);
            case WEB -> replyWeb(groupId);
            case PRODUCT_MENU -> replyMenu(groupId);
            case DEVICE_LIST -> replyDeviceList(groupId);
            case PRODUCT_ORDER -> writeCommandService.orderProduct(
                    groupId, event.getUserId(), command.argument(), command.quantity());
            // 调整库存：解析层不认人，是不是管理员由 writeCommandService 查库判
            case STOCK_ADJUST -> writeCommandService.adjustStock(
                    groupId, event.getUserId(), command.argument(), command.quantity());
            // 调整机台状况：同上，权限与同名判定都在 writeCommandService 里
            case DEVICE_STATUS -> writeCommandService.adjustDeviceStatus(
                    groupId, event.getUserId(), command.argument(), command.status());
            case OPEN_DOOR -> writeCommandService.openDoor(groupId, event.getUserId());
            case SETTLE -> writeCommandService.settle(groupId, event.getUserId());
            // 取消未付款单：解析层只做了单号的形状校验，
            // 按前缀路由、归属校验与状态守卫都在 writeCommandService 里
            case CANCEL_ORDER -> writeCommandService.cancelOrder(
                    groupId, event.getUserId(), command.argument());
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
        List<InstoreUserVo> users = result.getData();
        Map<String, String> labels = loadPreferenceLabels();

        // 格式是「每四人一张图、合成一条消息」（2026-10-10 由用户定）：
        // 人数写在句子里，图内只有卡片。画不出 / 发不出都回落到
        // 纯文本名册 —— 指令永远要有回音，见类注释
        if (properties.isInstoreImageEnabled() && sendInstoreImages(groupId, users, labels)) {
            return;
        }
        client.sendGroupMessage(groupId, QqReplyText.instore(
                users, labels, QqReplyText.DEFAULT_MAX_LISTED));
    }

    /**
     * 回复指令列表。
     *
     * <p>写指令那几条只在<b>确实可用</b>时才列出来（写开关与播报开关都开着）——
     * 列出来却发不动，用户只会以为机器人坏了。
     *
     * <p>「调整库存」那条还多一道：<b>只对管理员列</b>。它对别人本来就发不动
     * （执行时会拒绝），列出来只会让人问「为什么我不能用」。
     * 为此多查一次 {@code sys_user} —— {@code fw帮助} 是低频指令，这个代价划算。
     *
     * @param groupId 目标群号
     * @param qq      发送者 QQ（用于判定要不要列出管理员指令）
     */
    private void replyHelp(Long groupId, Long qq) {
        client.sendGroupMessage(groupId,
                QqReplyText.help(writeUsable(), writeCommandService.isAdmin(qq), baseUrl()));
    }

    /**
     * 写指令此刻能不能用。
     *
     * <p>与 {@code QqWriteCommandService#checkAllowed} 同一条判据：两个开关都要开 ——
     * 播报关掉时写指令也不执行（群里看不到播报，就等于一次没人看见的账）。
     * 这里的用途是<b>决定要不要在回复里提那几条指令</b>：提了却发不动，
     * 用户只会以为机器人坏了。
     *
     * @return 可以执行写指令返回 true
     */
    private boolean writeUsable() {
        return properties.isWriteEnabled() && properties.getBroadcast().isEnabled();
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
     * 回复最近的门店公告（{@code fw公告}）。
     *
     * <p>数据源是 {@link NoticeService#listForUser} —— 与网页端「全部公告」页
     * <b>同一个方法</b>（含置顶次序），所以群里看到的与网页上一致。
     * 只取前 {@value QqReplyText#NOTICE_LIST_MAX} 条，其余指路网页端。
     *
     * @param groupId 目标群号
     */
    private void replyNotices(Long groupId) {
        BizResult<PageResult<NoticeVo>> result =
                noticeService.listForUser(1, QqReplyText.NOTICE_LIST_MAX);
        if (!result.isSuccess() || result.getData() == null) {
            log.error("[QQ机器人] 查公告失败：{}",
                    result.resolveMessage() == null ? "未知原因" : result.resolveMessage());
            client.sendGroupMessage(groupId, "查不到公告，稍后再试。");
            return;
        }
        PageResult<NoticeVo> page = result.getData();
        client.sendGroupMessage(groupId, QqReplyText.notices(
                page.getRecords(), page.getTotal(), baseUrl() + "/#/notices"));
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
     * 想真的结账要再发 {@code fw结账}：两个动作分开，是为了防
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
        client.sendGroupMessage(groupId, "你当前没有正在计时的订单。发送 fw开门 可开始计时。");
    }

    /**
     * 回复本人全部未付款的单子（{@code fw未付款}）。
     *
     * <p>数据来自 {@link #collectUnpaidBills}，文案交给
     * {@link QqReplyText#unpaidBills}。
     *
     * @param groupId 目标群号
     * @param qq      发送者 QQ
     */
    private void replyUnpaid(Long groupId, Long qq) {
        SysUser user = sysUserMapper.selectByQq(String.valueOf(qq));
        if (user == null) {
            client.sendGroupMessage(groupId, QqReplyText.NOT_BOUND);
            return;
        }
        List<UnpaidBill> bills = collectUnpaidBills(user.getId());
        if (bills == null) {
            log.error("[QQ机器人] 查未付款清单失败：四类里至少一类查询未成功 qq={}", qq);
            client.sendGroupMessage(groupId, "查不到未付款信息，稍后再试。");
            return;
        }
        client.sendGroupMessage(groupId, QqReplyText.unpaidBills(
                bills, properties.isSelfAmountVisible(), ordersUrl()));
    }

    /**
     * 汇集某人四类未付款单（计时 → 包场 → 月卡 → 商品）。
     *
     * <p><b>四个来源各查各的</b>：它们分属四个模块、四张表，没有现成的汇总查询。
     * 顺序按「下一步动作最要紧」排 —— 欠着的计时订单排在最前。
     *
     * <p>⚠️ <b>任何一类查询失败都返回 null，不降级</b>：调用方会回一句
     * 「查不到」并记 error。少了这一类还说「你没有未付款单」是个
     * <b>错误结论</b> —— 用户会以为账已经清了。
     *
     * @param userId 用户 ID
     * @return 清单；任何一类查询未成功时返回 null
     */
    private List<UnpaidBill> collectUnpaidBills(Long userId) {
        List<UnpaidBill> bills = new ArrayList<>();

        BizResult<List<OrderVo>> orders = orderService.findUnpaidOrders(userId);
        if (!orders.isSuccess() || orders.getData() == null) {
            return null;
        }
        for (OrderVo order : orders.getData()) {
            // 计时订单不可取消（欠费不能自消）—— 见 QqCommand.Kind#CANCEL_ORDER
            bills.add(new UnpaidBill("计时", order.getOrderNo(), order.getPayableAmount(),
                    unpaidStatusText(order.getStatus(), order.getStatusText()), false));
        }

        BizResult<List<BookingVo>> bookings = bookingService.listUnpaidBookings(userId);
        if (!bookings.isSuccess() || bookings.getData() == null) {
            return null;
        }
        for (BookingVo booking : bookings.getData()) {
            // 查询只取 PENDING_PAYMENT，所以状态文案是固定的
            bills.add(new UnpaidBill("包场", booking.getBookingNo(), booking.getPrice(),
                    "待付款", true));
        }

        BizResult<CardPurchaseVo> card = monthlyCardService.findPendingPurchase(userId);
        if (!card.isSuccess()) {
            return null;
        }
        if (card.getData() != null) {
            bills.add(new UnpaidBill("月卡", card.getData().getOrderNo(),
                    card.getData().getPrice(), card.getData().getStatusLabel(), true));
        }

        BizResult<List<ProductOrderVo>> products = productService.listUnpaidOrders(userId);
        if (!products.isSuccess() || products.getData() == null) {
            return null;
        }
        for (ProductOrderVo order : products.getData()) {
            bills.add(new UnpaidBill("商品", order.getOrderNo(), order.getAmount(),
                    unpaidStatusText(order.getStatus(), order.getStatusLabel()), true));
        }
        return bills;
    }

    /**
     * 未付款单的状态文案。
     *
     * <p>{@code REJECTED}（凭证未通过）多补一句「请重新上传付款截图」——
     * 只报「凭证未通过」的话，用户知道出了事但不知道下一步做什么，
     * 而下一步恰恰只有一件事可做。
     *
     * <p>判据用的是 {@link OrderStatus#REJECTED} 的名字，但它同时覆盖商品的
     * {@code ProductOrderStatus.REJECTED} —— 两个枚举的常量名一样，
     * 且这是唯一一个需要特殊说明的状态。将来若有一方改名，这里要跟着改
     *（编译期发现不了，只有该状态的单子在群里显示会变回原样）。
     *
     * @param statusName 状态枚举名（{@code OrderVo.status} / 商品单同名字段）
     * @param label      模块给出的中文名
     * @return 展示用状态文案
     */
    private static String unpaidStatusText(String statusName, String label) {
        return OrderStatus.REJECTED.name().equals(statusName)
                ? label + "，请重新上传付款截图" : label;
    }

    /**
     * 发在店名册图 —— 每 4 人一张、2×2 网格，<b>全部塞在同一条消息里</b>
     * （2026-10-10 由用户定：多张图不逐条发，不刷屏）。
     *
     * <p>文本（「店内目前有 X 人」+ 可能的截断提示）在前、图依次在后，
     * 客户端渲染成一条消息里的图集。图内不写人数（见
     * {@link InstoreCardRenderer} 的类注释）。
     *
     * <p>返回值语义：渲染任何一张失败、或发送失败都返回 false，
     * 调用方回落纯文本名册 —— 一条消息要么整份发出去、要么整份不发，
     *「半份图集 + 一份全量文字」只会更乱。空店也返回 false ——
     * 一屏空网格没有信息量，由文字那句「店内目前无人」说清。
     *
     * @param groupId 目标群
     * @param users   在店顾客（全量，按进店时刻升序）
     * @param labels  偏好中文名映射
     * @return 整份发出去了返回 true
     */
    private boolean sendInstoreImages(Long groupId, List<InstoreUserVo> users,
                                      Map<String, String> labels) {
        if (users.isEmpty()) {
            return false;
        }
        int total = Math.min(users.size(), QqReplyText.DEFAULT_MAX_LISTED);
        List<byte[]> cards = new ArrayList<>();
        for (int i = 0; i < total; i += 4) {
            List<InstoreUserVo> page = users.subList(i, Math.min(i + 4, total));
            byte[] card = InstoreCardRenderer.render(page, labels, instoreImageLoader);
            if (card == null) {
                // 任何一张画不出就整份回落文字 —— 半份图集比没有更让人困惑
                return false;
            }
            cards.add(card);
        }
        String text = QqReplyText.instoreCount(users.size());
        // 截断时不静默：与文字版同一条纪律（超过上限的部分必须有交代）
        if (users.size() > total) {
            text += "\n……还有 " + (users.size() - total) + " 人";
        }
        return client.sendGroupMessageImages(groupId, cards, text);
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
     * 回复月卡说明：有哪几种卡、各多少钱、覆盖什么时段。
     *
     * <p><b>与 {@code fw价格} 分开是刻意的</b>：那条讲「按时长怎么算钱」，
     * 这条讲「包月怎么买」—— 两笔账的算法完全不同，塞进一条消息里两边都说不清。
     *
     * <p>数据源是 {@link MonthlyCardService#cardTypes()}（与用户端月卡页同一个方法），
     * 价格与有效期都取自配置，调价后群里立刻跟着变。
     *
     * @param groupId 目标群号
     */
    private void replyCardTypes(Long groupId) {
        BizResult<List<CardTypeVo>> result = monthlyCardService.cardTypes();
        if (!result.isSuccess() || result.getData() == null) {
            log.error("[QQ机器人] 查月卡卡种失败：{}",
                    result.resolveMessage() == null ? "未知原因" : result.resolveMessage());
            client.sendGroupMessage(groupId, "查不到月卡信息，稍后再试");
            return;
        }
        client.sendGroupMessage(groupId, QqReplyText.cardTypes(
                result.getData(), baseUrl() + "/#/cards"));
    }

    /**
     * 回复商城菜单：店里卖的东西与价格。
     *
     * <p>数据源是 {@link ProductService#listOnSale()} —— 与用户端商城页<b>同一个方法</b>，
     * 所以群里报的价与网页上看到的必然一致，不会出现「群里说 3 块、下单变 5 块」。
     *
     * <p>菜单报价格与库存（可售量），<b>下单仍然只在网页端</b> —— 买商品要扣库存、
     * 要付款，那条链路走的是模块 8 的统一支付入口，与房间时长计费是两套账。
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
                result.getData(), baseUrl() + "/#/mall", writeUsable()));
    }

    /**
     * 回复店内设施列表（{@code fw机台}）。
     *
     * <p>数据源是 {@link DeviceService#listForDisplay()}（与用户端「店内设施」页
     * <b>同一个方法</b>，所以群里的台数与状况和网页上看到的必然一致）。
     * 含维护中的机台 —— 陈列的目的就是让人知道哪台在修，藏起来会造成搬走了的误解。
     *
     * <p>⚠️ 与 {@code fw拍拍机 1 号维护中}（改状况）是两条不同的指令：
     * 这一条是查询、谁都能发；那一条是写指令、仅管理员。
     *
     * @param groupId 目标群号
     */
    private void replyDeviceList(Long groupId) {
        BizResult<List<DeviceGroupVo>> result = deviceService.listForDisplay();
        if (!result.isSuccess() || result.getData() == null) {
            log.error("[QQ机器人] 查店内设施失败：{}",
                    result.resolveMessage() == null ? "未知原因" : result.resolveMessage());
            client.sendGroupMessage(groupId, "查不到店内设施，稍后再试。");
            return;
        }
        client.sendGroupMessage(groupId, QqReplyText.deviceList(
                result.getData(), baseUrl() + "/#/devices"));
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
        // 两条：一条讲解、一条纯网址（网址单独一条才好长按复制，见 QqReplyText#web）
        for (String message : QqReplyText.web(baseUrl())) {
            client.sendGroupMessage(groupId, message);
        }
    }

    /**
     * 取站点基地址。
     *
     * <p>逻辑已上收到 {@code WebProperties#normalizedBaseUrl} ——
     * 本类与 {@code QqWriteCommandService} 各写一份，加上后来要发提醒的
     * {@code QqBroadcastListener} 正好是第三处，按项目惯例该合了。
     *
     * @return 形如 {@code https://xxx.com}；没配置时返回空串
     */
    private String baseUrl() {
        return webProperties.normalizedBaseUrl();
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
