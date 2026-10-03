package com.kaede.uspace.qqbot;

import com.kaede.uspace.billing.dto.BillingRulesVo;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.order.OrderStatus;
import com.kaede.uspace.order.dto.InstoreUserVo;
import com.kaede.uspace.order.dto.MonthSpentVo;
import com.kaede.uspace.order.dto.OrderPreviewVo;
import com.kaede.uspace.order.dto.OrderStatsVo;
import com.kaede.uspace.order.dto.OrderVo;
import com.kaede.uspace.product.dto.ProductOrderVo;
import com.kaede.uspace.product.dto.ProductVo;
import com.kaede.uspace.promotion.MonthlyCardType;
import com.kaede.uspace.promotion.entity.MonthlyCard;
import com.kaede.uspace.space.dto.BookingScheduleVo;
import com.kaede.uspace.space.dto.StoreStatusVo;
import com.kaede.uspace.user.entity.SysUser;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 群消息的文案组装（模块 11）。
 *
 * <p><b>纯静态、无状态、不依赖 Spring</b>，可以脱离容器单测。
 * 它与 {@link QqCommandParser} 是同一类风险：<b>写错了不会报错</b>，
 * 只会答非所问或把数字说错 —— 那看起来像「机器人变笨了」，不像一个 bug。
 *
 * <p>所有从别处搬来的格式化逻辑（时长、偏好、在店名册的排版）也集中在这里：
 * 让 {@link QqBroadcastListener} 与 {@code QqCommandService} 各写一份的话，
 * 「改了这处忘了那处」的表现就是同一个时长在两处说法不一致。
 *
 * <p>⚠️ <b>本类的产出全部会发到群里（所有人可见）</b>，因此：
 * <ul>
 *   <li>密码只在 {@link #openPasscode} 里出现 —— 那是设计允许的唯一出口</li>
 *   <li>金额只在 {@code /看看自己}、{@code /结账} 的回复里出现，
 *       而那两条都是<b>本人主动查自己</b>（2026-10-04 与用户确认的口径）；
 *       系统主动推的播报仍受 {@code QqbotProperties#isAmountVisible} 约束</li>
 * </ul>
 */
public final class QqReplyText {

    /** 名册一次最多列几个人。群消息有长度上限，而名单是随人数线性增长的 */
    public static final int DEFAULT_MAX_LISTED = 30;

    /**
     * 这个 QQ 还没绑定账号时的提示。
     *
     * <p>两条写指令与「看看自己」共用同一句 —— 三处各写一份的话，
     * 改文案时漏掉一处，同一个原因在群里会看到三种说法。
     */
    public static final String NOT_BOUND =
            "这个 QQ 还没绑定账号。先在网页端注册并完成 QQ 验证，之后就能用了。";

    /** 时间格式，如 {@code 14:00} */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** 日期格式，如 {@code 10月4日} */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M月d日");

    /** 日期时间格式，如 {@code 10月4日 16:00} */
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("M月d日 HH:mm");

    /**
     * 「付完可以发群里」那句 —— 群内传截图那条路的<b>唯一入口说明</b>。
     *
     * <p>两条结账回复共用它：不写出来没人会想到可以直接发图，而各写一份的话，
     * 改了时限（{@code QqPaymentProofService.WAIT_WINDOW}）必然漏掉一处，
     * 表现为群里说的时限与系统实际受理的时限不一致。
     */
    private static final String PAY_BY_GROUP_HINT =
            "\n付完把付款截图发到群里也行（5 分钟内有效）。";

    /**
     * 认不出指令时的提示。
     *
     * <p>商品那条路是<b>封闭</b>的（只认 {@code 买…个…} 与 {@code 名-数量} 两种写法），
     * 所以拼错的指令（{@code /在店铺}、{@code /开门吧}）都会落到这里 ——
     * 不会被错怪成「没有这个商品」。这也是它值得单独一个常量的原因：
     * 它守着一类「答非所问」的体验。
     */
    private static final String UNKNOWN_COMMAND =
            "没认出这条指令。发 /帮助 看看能问什么";

    /**
     * 工具类，不实例化。
     */
    private QqReplyText() {
    }

    // ==================================================================
    // 查询类指令
    // ==================================================================

    /**
     * 在店名册。
     *
     * <p>数据源与 Web 端「在店用户」页、播报里的「当前 N 人在店」是同一个方法，
     * 所以三处的口径必然一致，不会出现「群里说 3 人、网页说 2 人」。
     *
     * @param users            在店顾客，按进店时刻升序
     * @param preferenceLabels 设备类型 code → 中文名
     * @param maxListed        一次最多列几个人
     * @return 多行文本；没有人时回一句人话而不是空表
     */
    public static String instore(List<InstoreUserVo> users, Map<String, String> preferenceLabels,
                                 int maxListed) {
        if (users == null || users.isEmpty()) {
            return "现在店里没人。";
        }

        StringBuilder text = new StringBuilder("当前 ").append(users.size()).append(" 人在店：\n");
        int listed = Math.min(users.size(), maxListed);
        for (int i = 0; i < listed; i++) {
            text.append(i + 1).append(". ")
                    .append(formatUser(users.get(i), preferenceLabels))
                    .append('\n');
        }
        if (users.size() > listed) {
            text.append("……还有 ").append(users.size() - listed).append(" 人\n");
        }
        return text.toString().stripTrailing();
    }

    /**
     * 近期包场时间表。
     *
     * <p>只有时段、没有包场人 —— 与 Web 端的匿名时间表同一条披露边界
     * （{@link BookingScheduleVo} 里压根没有那个字段）。
     *
     * @param items       包场时段，按开始时间升序
     * @param leadDuration 包场开始前多久停止接待新顾客（取自配置，不写死）
     * @param today       今天，用于把日期说成「今天/明天」
     * @return 多行文本；没有包场时回一句人话
     */
    public static String bookingSchedule(List<BookingScheduleVo> items, Duration leadDuration,
                                         LocalDate today) {
        if (items == null || items.isEmpty()) {
            return "近期没有包场安排，随时可以来。";
        }

        StringBuilder text = new StringBuilder("近期包场安排：\n");
        for (BookingScheduleVo item : items) {
            text.append(dayLabel(item.getStartAt().toLocalDate(), today))
                    .append(' ')
                    .append(TIME.format(item.getStartAt()))
                    .append(" – ")
                    .append(TIME.format(item.getEndAt()));
            if (item.isOngoing()) {
                text.append("（进行中）");
            }
            text.append('\n');
        }
        text.append("包场开始前 ").append(leadDuration.toMinutes()).append(" 分钟停止接待新顾客。");
        return text.toString();
    }

    /**
     * 看看自己：资料、月卡、消费、时长，以及在店时的实时状态。
     *
     * <p><b>不含任何密码</b> —— 要看密码去网页端或私聊。
     *
     * <p>塞进来的这几样各有各的用处（2026-10-04 扩充，逐条想过再加的）：
     * <b>月卡</b>决定今天要不要付钱；<b>本月消费与还差多少</b>决定下一单走不走优惠价；
     * <b>累计与本月在店时长</b>是「我一共玩了多久」的答案（网页端「我的」页也给了这两个数）；
     * <b>待付款那一笔</b>是他最常来问的事 —— 欠着费连门都开不了；
     * <b>偏好</b>是名册上会显示的那个自报标签，自己核对一下有没有填错。
     * 这条回复是<b>他自己问的</b>，长一点没关系；反过来说，别往播报里塞这些。
     *
     * @param user          用户实体（昵称与三列累计消费）
     * @param card          当前有效的月卡，可为 null
     * @param month         本月累计消费与优惠资格，可为 null
     * @param stats         累计与本月在店时长，可为 null
     * @param pending       未结清的那一笔，可为 null。<b>只有「待支付」才报出来</b> ——
     *                      它也会返回进行中的那一单，而那笔在上面的「当前在店」里已经说过了
     * @param preference    偏好中文名（已映射好），可为 null
     * @param current       当前这一单的预览；<b>不在店时传 null</b>
     * @param amountVisible 金额是否显示（{@code uspace.qqbot.self-amount-visible}）
     * @return 多行文本
     */
    public static String me(SysUser user, MonthlyCard card, MonthSpentVo month,
                            OrderStatsVo stats, OrderVo pending, String preference,
                            OrderPreviewVo current, boolean amountVisible) {
        StringBuilder text = new StringBuilder(displayName(user.getNickname(), user.getId()));

        if (card != null) {
            text.append("\n月卡：").append(MonthlyCardType.labelOf(card.getCardType()))
                    .append("（有效至 ").append(DATE.format(card.getEndDate())).append("）");
        }

        if (amountVisible && month != null) {
            text.append("\n本月消费 ").append(yuan(month.getMonthSpent()));
            if (month.isDiscounted()) {
                text.append("，已享优惠价");
            } else if (month.getRemaining() != null && month.getRemaining().signum() > 0) {
                text.append("，再消费 ").append(yuan(month.getRemaining())).append(" 即可享优惠价");
            }
        }

        if (amountVisible && user.getTotalPaid() != null) {
            text.append("\n累计消费 ").append(yuan(user.getTotalPaid()));
        }

        if (stats != null) {
            text.append("\n累计在店 ").append(duration((int) stats.getTotalMinutes()));
            if (stats.getMonthMinutes() > 0) {
                text.append(" · 本月 ").append(duration((int) stats.getMonthMinutes()));
            }
        }

        // 只报「待支付」：进行中的那一单归下一条说，两处都报就成了同一个数字说两遍
        if (pending != null && OrderStatus.PENDING_PAYMENT.name().equals(pending.getStatus())) {
            text.append("\n有一笔还没付款：");
            if (amountVisible && pending.getPayableAmount() != null) {
                text.append(yuan(pending.getPayableAmount())).append('，');
            }
            text.append("单号 ").append(pending.getOrderNo());
            text.append("\n（要重新拿到付款入口，发一次 /结账）");
        }

        if (current != null) {
            text.append("\n当前在店 ").append(duration((int) current.getStayMinutes()));
            if (amountVisible && current.getBill() != null) {
                text.append("，预计 ").append(yuan(current.getBill().getTotalAmount()));
            }
            text.append("\n（看更多的发 /当前订单）");
        }

        if (preference != null && !preference.isBlank()) {
            text.append("\n偏好：").append(preference);
        }

        return text.toString();
    }

    /**
     * 当前这一单的预览。
     *
     * <p>⚠️ <b>只是看一眼，不停表</b>：数据源与网页端「结账」按钮点进去那一屏同源
     * （{@code OrderPreviewVo}，纯查询、零副作用）。所以末尾必须明说
     * 「计时照走」并指去 {@code /结账} —— 不写的话，用户会以为发一条指令就把账结了，
     * 然后接着玩、然后被账单吓一跳。
     *
     * <p>跳档预告（{@link OrderPreviewVo#getNextChangeText()}）里的金额<b>不受
     * 金额开关约束</b>：那是价目表上的档位价，群里任何人发 {@code /价格} 都查得到，
     * 不是这位顾客自己的消费额。
     *
     * @param preview       结账预览（含分段账单与跳档预告）
     * @param amountVisible 金额是否显示（{@code uspace.qqbot.self-amount-visible}）
     * @return 多行文本
     */
    public static String currentOrder(OrderPreviewVo preview, boolean amountVisible) {
        StringBuilder text = new StringBuilder("当前订单 ").append(preview.getOrderNo());
        text.append("\n进场 ").append(DATE_TIME.format(preview.getStartTime()))
                .append(" · 已玩 ").append(duration((int) preview.getStayMinutes()));

        if (preview.isWillAutoSettle()) {
            // 0 元单不要引导去付款：压根没有可付的通道
            text.append("\n现在结账无需支付，会直接结清。");
        } else if (amountVisible && preview.getBill() != null) {
            text.append("\n当前应付 ").append(yuan(preview.getBill().getTotalAmount()));
            if (preview.isCappedNow()) {
                text.append("（已到封顶价）");
            }
        }

        if (preview.isFreeByBooking()) {
            text.append("\n（包场时段不计费）");
        }

        if (preview.getNextChangeText() != null) {
            text.append("\n");
            if (preview.getNextChangeInSeconds() != null) {
                text.append("还有 ").append(remaining(preview.getNextChangeInSeconds())).append('，');
            }
            text.append(preview.getNextChangeText());
        }

        text.append("\n（只是看一眼，计时照走。要结账发 /结账）");
        return text.toString();
    }

    /**
     * 没有在计时的单时的回复，附带待付款那一笔。
     *
     * <p>⚠️ <b>刻意不带「付款截图可以发群里」那句</b>：那是 {@link #settleAlreadyStopped}
     * 的待遇 —— 发过 {@code /结账} 才会撑起「等一张图」那道闸（见 {@code QqPaymentProofService}）。
     * 只看一眼当前订单并不会撑闸，说了那句话只会让用户发一张石沉大海的图。
     *
     * @param orderNo       待付款的订单号
     * @param amount        应付金额
     * @param amountVisible 金额是否显示
     * @param ordersUrl     网页端订单页地址
     * @return 回复文本
     */
    public static String noActiveOrder(String orderNo, BigDecimal amount,
                                       boolean amountVisible, String ordersUrl) {
        StringBuilder text = new StringBuilder("你现在没有在计时的订单。");
        text.append("\n有一笔还没付款：");
        if (amountVisible && amount != null) {
            text.append(yuan(amount)).append('，');
        }
        text.append("单号 ").append(orderNo);
        text.append("\n去支付：").append(ordersUrl);
        return text.toString();
    }

    /**
     * 门店营业状态。
     *
     * @param status        门店状态（含中文文案与结束时刻）
     * @param instoreCount  当前在店人数；查不到时传 null，这一句就省掉
     * @return 多行文本
     */
    public static String storeStatus(StoreStatusVo status, Integer instoreCount) {
        StringBuilder text = new StringBuilder();
        if (status.getStore() != null && status.getStore().getName() != null) {
            text.append(status.getStore().getName()).append('\n');
        }
        text.append(status.getStatusText());

        if (status.getStatusEndAt() != null) {
            text.append("（至 ").append(DATE_TIME.format(status.getStatusEndAt())).append("）");
        } else if (instoreCount != null) {
            // 只有正常营业时才报人数 —— 停业/包场时报人数没有意义
            text.append(" · 当前 ").append(instoreCount).append(" 人在店");
        }
        return text.toString();
    }

    /**
     * 计费规则摘要。
     *
     * @param rules 价目表（与用户端「计费规则」页同一个来源）
     * @return 多行文本
     */
    public static String price(BillingRulesVo rules) {
        StringBuilder text = new StringBuilder("计费规则：\n");
        text.append("日场 ").append(rules.getDayStart()).append('–').append(rules.getDayEnd())
                .append("：").append(plain(rules.getDayPricePerHour())).append(" 元/小时，封顶 ")
                .append(plain(rules.getDayCap())).append(" 元\n");
        text.append("夜场 ").append(rules.getDayEnd()).append('–').append("次日 ")
                .append(rules.getDayStart())
                .append("：").append(plain(rules.getNightPricePerHour())).append(" 元/小时，封顶 ")
                .append(plain(rules.getNightCap())).append(" 元\n");
        text.append("前 ").append(rules.getGraceMinutes()).append(" 分钟免费，之后每 ")
                .append(rules.getUnitMinutes()).append(" 分钟一档。");

        if (rules.isDiscountEnabled()) {
            text.append("\n当月累计消费满 ").append(yuan(rules.getDiscountThreshold()))
                    .append(" 后，本月的后续订单按优惠价：日场 ")
                    .append(plain(rules.getDiscountDayPricePerHour())).append(" 元/小时、夜场 ")
                    .append(plain(rules.getDiscountNightPricePerHour())).append(" 元/小时。");
        }
        return text.toString();
    }

    /**
     * 商城菜单：店里卖的东西与价格。
     *
     * <p>只列<b>上架中</b>的商品（数据源是 {@code ProductService.listOnSale}）——
     * 下架的不该出现在群里的菜单上。售罄的照列但标出来：
     * 顾客看到「售罄」才会问「什么时候补」，直接藏起来他只会以为店里没这个东西。
     *
     * @param products 上架商品，已按后台排的序号排好
     * @param mallUrl  网页端商城地址（群里的菜单只报价格，下单还是回网页）
     * @return 多行文本；没有商品时回一句人话
     */
    public static String menu(List<ProductVo> products, String mallUrl) {
        if (products == null || products.isEmpty()) {
            return "店里暂时没有上架的商品。";
        }
        StringBuilder text = new StringBuilder("店里卖这些（共 ")
                .append(products.size()).append(" 种）：\n");
        for (ProductVo product : products) {
            text.append("· ").append(product.getName())
                    .append(' ').append(yuan(product.getPrice()));
            if (Boolean.TRUE.equals(product.getSoldOut())) {
                text.append("（售罄）");
            }
            text.append('\n');
        }
        text.append("要买的话到网页端商城下单：").append(mallUrl);
        return text.toString();
    }

    /**
     * 网页端地址。
     *
     * <p>地址原样来自配置（{@code uspace.web.base-url}），这里不加工 ——
     * 加工过一次的话，群里看到的地址与配置里那一项就对不上了，
     * 排查时反而多一层要怀疑的东西。
     *
     * @param url 完整地址；没配置时给一句提示而不是留空白
     * @return 回复文本
     */
    public static String web(String url) {
        if (url == null || url.isBlank()) {
            return "网页端地址还没配置，问一下店主吧。";
        }
        return "网页端：" + url + "\n下单、查看账单、买月卡都在这里。";
    }

    /**
     * 指令列表。
     *
     * <p>顺带写明前缀要求 —— 这是「必须带前缀」那条规矩唯一的说明处，
     * 少了它用户会照着指令名裸发，然后什么都得不到。
     *
     * @param writeEnabled 两条写指令是否启用（关掉时不能列出来，
     *                     否则用户发了没反应，而原因无处可查）
     * @return 多行文本
     */
    public static String help(boolean writeEnabled) {
        StringBuilder text = new StringBuilder("可用指令（前面加 / 或 fw，例如 /在店）：\n");
        text.append("/在店 或 /看看里面 —— 看看店里现在有谁\n");
        text.append("/包场 —— 看看近期的包场安排\n");
        text.append("/营业 —— 门店现在开着吗\n");
        text.append("/价格 —— 怎么计费\n");
        text.append("/菜单 —— 看看店里卖什么\n");
        text.append("/web 或 /网址 —— 网页端地址（手机下单、看账单都在这儿）\n");
        text.append("/看看自己 —— 我的月卡、消费与时长\n");
        text.append("/当前订单 或 /now —— 正在计时的这一单现在多少钱（只看，不停表）\n");
        text.append("/ping —— 看看机器人在不在\n");
        text.append("/帮助 —— 显示这条消息\n");

        if (writeEnabled) {
            text.append("\n/开门 —— 开始计时，并拿到门锁密码\n");
            text.append("/结账 —— 停止计时并去付款\n");
            text.append("/买个商品名 —— 买一件，如 /买个可乐\n");
            text.append("/买N个商品名 —— 买 N 件，如 /买2个可乐（也可写成 /可乐-2）\n");
        }

        text.append("\n注册时网页会给你一条「/验证 」开头的指令，复制发到群里就能把 QQ 号绑到账号上。");
        return text.toString();
    }

    // ==================================================================
    // 写指令
    // ==================================================================

    /**
     * 开门成功的回复。
     *
     * <p>⚠️ <b>只发播报里没有的那部分</b>：新建订单会有「到店播报」，
     * 所以这里不再说「已开始计时」（那样群里会出现两条说同一件事的消息）。
     * 但<b>已有进行中订单时不会有播报</b>，那时要把状态补上，
     * 否则用户只看到一串密码，不知道自己在计时中。
     *
     * @param passcode     一次性门锁密码
     * @param newlyCreated 这次是不是才建的订单（决定要不要自报状态）
     * @param stayMinutes  已在店时长（分钟）；刚刚建单时为 0
     * @param ordersUrl    网页端订单页地址，作为私聊失败时的兜底
     * @return 回复文本
     */
    public static String openPasscode(String passcode, boolean newlyCreated,
                                      Integer stayMinutes, String ordersUrl) {
        StringBuilder text = new StringBuilder();
        if (!newlyCreated && stayMinutes != null) {
            text.append("你已在计时中（").append(duration(stayMinutes)).append("）。\n");
        }
        text.append("门锁密码：").append(passcode).append("\n");
        text.append("用一次即作废，请勿转发。")
                .append("固定密码已私聊发你，没收到就去网页端看：").append(ordersUrl);
        return text.toString();
    }

    /**
     * 开门失败的回复。
     *
     * <p>按错误码分派文案 —— 每一条都要给用户一个<b>能做的动作</b>，
     * 而不是一句「操作失败」。这条原则与 Web 端的错误提示是同一条。
     *
     * @param error     错误码，可为 null（未知错误）
     * @param message   错误码之外的自定义文案（取自 {@code BizResult.resolveMessage()}），可为 null
     * @param ordersUrl 网页端订单页地址
     * @return 回复文本
     */
    public static String openFailure(ErrorCode error, String message, String ordersUrl) {
        if (error == null) {
            return "开门失败" + (message == null ? "，请稍后再试。" : "：" + message);
        }
        return switch (error) {
            case STORE_CLOSED -> "现在店里暂停营业，暂时开不了门。";
            case BOOKING_ACCESS_DENIED -> "这个时段被包场了，只有包场人和受邀者能进。";
            case BOOKING_PREPARING -> "这个时段马上有包场，暂时进不去了。";
            case ORDER_UNPAID_EXISTS -> "你有一笔还没付款的订单，先付掉再来开门：" + ordersUrl;
            case LOCK_NOT_CONFIGURED, STORE_NOT_FOUND -> "门店还没配置好，请联系管理员。";
            case LOCK_CLOUD_UNAVAILABLE -> "门锁云暂时不可用，过一会儿再发一次 /开门。";
            default -> "开门失败：" + (message == null ? "请稍后再试。" : message);
        };
    }

    /**
     * 结账成功的回复。
     *
     * <p>⚠️ <b>不分「播报已覆盖」两种版本</b>：播报里不含金额（金额按群分级，
     * 顾客群看不到），而结账的人需要知道付多少 —— 所以金额在这一条里必须说。
     *
     * @param amount       本次应付金额
     * @param amountVisible 金额是否显示（{@code uspace.qqbot.self-amount-visible}）
     * @param settleUrl    Web 结算页地址
     * @return 回复文本
     */
    public static String settleDone(BigDecimal amount, boolean amountVisible, String settleUrl) {
        if (amount == null || amount.signum() == 0) {
            return "已停止计时，本次无需支付，已结清。";
        }
        if (!amountVisible) {
            // 金额被关掉时（uspace.qqbot.self-amount-visible=false）不报数 ——
            // 打开结算页自然看得到，而这里报了就是全群可见
            return "已停止计时，去支付：" + settleUrl + PAY_BY_GROUP_HINT;
        }
        return "已停止计时，本次应付 " + yuan(amount) + "。\n去支付：" + settleUrl + PAY_BY_GROUP_HINT;
    }

    /**
     * 再发一次 {@code /结账} 时，账其实已经停过表了的回复。
     *
     * <p>⚠️ <b>这条回复是一条回头路，不是客套</b>：群内传截图有 5 分钟时限，
     * 超时那句提示写着「重新发一次 /结账」—— 而那时订单早已不是 {@code IN_USE}
     * 了，按原来那条分支走只会得到「你没有在计时的订单」，等图的闸再也撑不起来，
     * 用户发多少张图都不会有反应。所以这一条必须<b>把待付金额与入口重新给一遍</b>。
     *
     * @param orderNo       待支付的订单号
     * @param amount        应付金额（{@code OrderVo.payableAmount}）
     * @param amountVisible 金额是否显示（{@code uspace.qqbot.self-amount-visible}）
     * @param orderUrl      网页端该订单的详情页地址
     * @return 回复文本
     */
    public static String settleAlreadyStopped(String orderNo, BigDecimal amount,
                                              boolean amountVisible, String orderUrl) {
        StringBuilder text = new StringBuilder("这笔账已经停过表了，还没有付款。");
        if (amountVisible && amount != null) {
            text.append("\n应付 ").append(yuan(amount));
        }
        text.append("\n订单 ").append(orderNo).append(" · 去支付：").append(orderUrl);
        return text.append(PAY_BY_GROUP_HINT).toString();
    }

    /**
     * 认不出指令时的提示。
     *
     * <p>见 {@code UNKNOWN_COMMAND} 上那段说明 —— 它与「商品名没匹配上、
     * 又没写数量」共用同一句话。
     *
     * @return 提示文本
     */
    public static String unknownCommand() {
        return UNKNOWN_COMMAND;
    }

    /**
     * 商品名没匹配上，而用户<b>写明了数量</b>。
     *
     * <p>这种情况下他确实在报一个商品名（不写数量的那种走 {@link #unknownCommand()}），
     * 所以直接告诉他名字不对，并指去 {@code /菜单} —— 那里的名字可以直接复制，
     * 而名字对不上多半是因为商品名里那个空格或全角括号（如「王老吉 250ml（绿）」）。
     *
     * @param name 他打出来的那个名字（原样回显，让他一眼看出差在哪）
     * @return 提示文本
     */
    public static String productNotFound(String name) {
        return "没有叫「" + name + "」的商品。发 /菜单 看看店里卖什么，"
                + "名字要一模一样（含空格与括号）。";
    }

    /**
     * 群里下单成功的回复。
     *
     * <p>金额、数量、单号都报出来 —— 他得核对自己买对了没有，
     * 而这几项都是<b>他刚说的那件事</b>的回显，不含别人的信息。
     *
     * <p>尾句说明「到店自取」：本系统不做核销（无人值守店里没有店员），
     * 付完钱自己拿 —— 不写这一句，第一次买的人会站在店里等店员递给他。
     *
     * @param order         刚建好的购买单（含下单时的名称与单价快照）
     * @param amountVisible 金额是否显示（{@code uspace.qqbot.self-amount-visible}）
     * @param ordersUrl     网页端「商品订单」页地址，付款入口在那儿
     * @return 回复文本
     */
    public static String productOrdered(ProductOrderVo order, boolean amountVisible,
                                        String ordersUrl) {
        StringBuilder text = new StringBuilder("已下单：")
                .append(order.getProductName()).append(" ×").append(order.getQuantity());
        if (amountVisible) {
            // 与 /结账 同一口径：金额受 self-amount-visible 控制（回复是群消息、全群可见），
            // 关掉时不报数 —— 付款页上本来就看得到
            text.append("，共 ").append(yuan(order.getAmount()));
        }
        text.append("\n单号 ").append(order.getOrderNo())
                .append("\n付款入口：").append(ordersUrl);
        text.append(PAY_BY_GROUP_HINT);
        text.append("\n付完到店自取即可。");
        return text.toString();
    }

    /**
     * 群内下单失败的回复。
     *
     * <p>按错误码分派 —— 每条都要给一个<b>能做的动作</b>：售罄就少买点或等补货、
     * 下架就换一样、数量不合适就改数量。与 {@link #openFailure} 同一条原则。
     *
     * @param error   错误码，可为 null（未知错误）
     * @param message 错误码之外的自定义文案（取自 {@code BizResult.resolveMessage()}），可为 null
     * @param menuUrl 网页端商城地址，作为「换个东西买」的出口
     * @return 回复文本
     */
    public static String productOrderFailure(ErrorCode error, String message, String menuUrl) {
        if (error == null) {
            return "下单失败" + (message == null ? "，请稍后再试。" : "：" + message);
        }
        return switch (error) {
            case PRODUCT_SOLD_OUT -> "这件商品卖光了" + (message == null ? "" : "（" + message + "）")
                    + "。发 /菜单 看看别的吧：" + menuUrl;
            case PRODUCT_STATUS_INVALID -> "这件商品现在不卖了。发 /菜单 看看别的吧：" + menuUrl;
            case PARAM_INVALID -> message == null ? "这个数量不合适，改一下再发。" : message + "。";
            default -> "下单失败：" + (message == null ? "请稍后再试。" : message);
        };
    }

    /**
     * 群内付款截图已受理。
     *
     * <p>识别到的金额与单号原样报出来 —— 它们是<b>辅助线索</b>，用户看一眼就知道
     * 系统读对没有；读错了也不影响提交（复核永远是人做的）。
     *
     * @param outTradeNo 商户单号（房间订单是 {@code OD…}、商品是 {@code PD…}）
     * @param amount     识别到的金额，可为 null（没识别出）
     * @param paymentNo  识别到的流水号，可为 null
     * @return 回复文本
     */
    public static String proofAccepted(String outTradeNo, BigDecimal amount, String paymentNo) {
        // 文案里说的是「单号」而不是「订单」：这条路既收房间订单的凭证，也收商品的
        StringBuilder text = new StringBuilder("已收到付款截图，提交为单号 ")
                .append(outTradeNo).append(" 的凭证。");
        if (amount != null) {
            text.append("\n识别到金额 ").append(yuan(amount));
        }
        if (paymentNo != null && !paymentNo.isBlank()) {
            text.append("，单号 ").append(paymentNo);
        }
        text.append("\n管理员会再核对一遍。");
        return text.toString();
    }

    // ==================================================================
    // 共用工具
    // ==================================================================

    /**
     * 把分钟数拼成人话。
     *
     * @param minutes 分钟数
     * @return 形如 {@code 35 分钟} / {@code 1 小时 20 分} / {@code 2 小时}
     */
    public static String duration(int minutes) {
        if (minutes < 60) {
            return minutes + " 分钟";
        }
        int hours = minutes / 60;
        int rest = minutes % 60;
        return rest == 0 ? hours + " 小时" : hours + " 小时 " + rest + " 分";
    }

    /**
     * 把剩余秒数说成人话。
     *
     * <p>跳档预告给的是<b>秒数</b>（时间点会带上客户端与服务端的时钟偏差，
     * 见 {@code OrderPreviewVo#getNextChangeInSeconds}），而群里报秒数没人看得懂 ——
     * 这里负责翻成「12 分 30 秒」。
     *
     * @param seconds 剩余秒数
     * @return 形如 {@code 45 秒} / {@code 12 分 30 秒} / {@code 2 小时 5 分}
     */
    private static String remaining(long seconds) {
        if (seconds < 60) {
            return seconds + " 秒";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            long rest = seconds % 60;
            return rest == 0 ? minutes + " 分钟" : minutes + " 分 " + rest + " 秒";
        }
        // 一小时以上只说分钟：到那个粒度，秒本来就没有意义了
        return duration((int) minutes);
    }

    /**
     * 把一位在店顾客拼成一行。
     *
     * <p><b>身份与月卡挂在名字前面当标记</b>（2026-10-04 改），形如：
     * <pre>
     *   [STAFF][全天月卡] 张三 · 1 小时 20 分 · 偏好 拍拍机
     *   [夜间月卡] 李四 · 35 分钟
     *   王五 · 刚进店
     * </pre>
     *
     * <p>⚠️ <b>为什么从「行尾的 · 全天月卡」挪到「行首的 [全天月卡]」</b>：
     * 名册是一列名字，读者扫的是<b>每行的开头</b> —— 原来的写法里，
     * 「谁是店员、谁今天免单」这两条最要紧的信息要读到行尾才看得见，
     * 而那两件事恰恰是「我要不要过去打招呼 / 这台机器是不是他占着」判断的依据。
     * 标记放行首之后，一屏扫下来就分得清。与 Web 端在店名册的做法一致
     * （那边的卡片也是「头像 · STAFF · 昵称」）。
     *
     * @param user             名册上的一行
     * @param preferenceLabels 类型 code → 中文名
     * @return 形如 {@code [STAFF][全天月卡] 张三 · 1 小时 20 分 · 偏好 拍拍机}
     */
    private static String formatUser(InstoreUserVo user, Map<String, String> preferenceLabels) {
        StringBuilder line = new StringBuilder();
        if (isStaff(user)) {
            line.append("[STAFF]");
        }
        if (user.getCardTypeLabel() != null && !user.getCardTypeLabel().isBlank()) {
            line.append('[').append(user.getCardTypeLabel()).append(']');
        }
        // 标记与名字之间留一个空格：不加的话「[全天月卡]张三」会挤成一团
        if (line.length() > 0) {
            line.append(' ');
        }
        line.append(displayName(user.getNickname(), user.getUserId()));

        Integer stayMinutes = user.getStayMinutes();
        line.append(" · ").append(stayMinutes == null || stayMinutes <= 0
                ? "刚进店" : duration(stayMinutes));
        String preference = preference(user.getPreference(), preferenceLabels);
        if (preference != null) {
            line.append(" · 偏好 ").append(preference);
        }
        return line.toString();
    }

    /**
     * 是不是店员。
     *
     * <p>判据与 Web 端在店名册<b>逐字一致</b>（{@code role === 'ADMIN'}，
     * 见 {@code UserCard} 的 {@code isStaff}）—— 两处各写一套的话，
     * 会出现「网页上挂着 STAFF、群里没挂」这种同一个人两种身份的矛盾。
     *
     * <p>⚠️ <b>它纯粹是展示标记，不承载任何权限判断</b>：权限只认服务端每个请求
     * 重新读取的 {@code sys_user.role}。这里判错的后果仅仅是少挂或多挂一个方括号。
     *
     * @param user 名册上的一行
     * @return 是店员返回 true
     */
    private static boolean isStaff(InstoreUserVo user) {
        return "ADMIN".equals(user.getRole());
    }

    /**
     * 取展示用的名字。
     *
     * <p>昵称为空时用「用户{ID}」兜底而不是跳过这一行 ——
     * 名册上有几个人就得说几个人，把查不到资料的静默抹掉，
     * 会让「3 人在店」下面只列出 2 个名字。
     *
     * @param nickname 昵称，可为 null
     * @param userId   用户 ID
     * @return 展示名
     */
    private static String displayName(String nickname, Long userId) {
        if (nickname == null || nickname.isBlank()) {
            return "用户" + userId;
        }
        return nickname;
    }

    /**
     * 把偏好 code 串翻成中文名串。
     *
     * <p>⚠️ <b>映射不出来的 code 直接丢弃，不原样显示</b>：字典里查不到说明
     * 那个类型已被停用（{@code listSelectableTypes} 只给启用中的），
     * 把 {@code PAIPAI} 这种内部代号甩到群里，用户只会以为系统出错了。
     * 代价是「设了 3 个偏好只显示 2 个」，而那种情况本来就少见。
     *
     * <p>包级可见：{@code QqCommandService} 组装「看看自己」时也要用它
     * （名册与自查询两处若各写一份，同一个偏好会在群里出现两种写法）。
     *
     * @param preference 逗号分隔的 code 串，可为 null
     * @param labels     code → 中文名
     * @return 顿号分隔的中文名；一个都映射不出时返回 null
     */
    public static String preference(String preference, Map<String, String> labels) {
        if (preference == null || preference.isBlank() || labels == null || labels.isEmpty()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (String code : preference.split(",")) {
            String name = labels.get(code.trim());
            if (name != null) {
                names.add(name);
            }
        }
        return names.isEmpty() ? null : String.join("、", names);
    }

    /**
     * 把日期说成「今天 / 明天 / 10月5日」。
     *
     * @param date  目标日期
     * @param today 今天
     * @return 日期标签
     */
    private static String dayLabel(LocalDate date, LocalDate today) {
        if (date.equals(today)) {
            return "今天";
        }
        if (date.equals(today.plusDays(1))) {
            return "明天";
        }
        return DATE.format(date);
    }

    /**
     * 金额展示：保留两位小数。
     *
     * <p>钱一律两位小数（{@code ¥12.00} 而不是 {@code ¥12}）——
     * 这是账单的口径，而价目表里的单价用 {@link #plain} 去零展示。
     *
     * @param amount 金额，可为 null
     * @return 形如 {@code ¥12.00}
     */
    private static String yuan(BigDecimal amount) {
        if (amount == null) {
            return "¥0.00";
        }
        return "¥" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 数值展示：去掉多余的零。
     *
     * <p>⚠️ {@code stripTrailingZeros()} 会把 {@code 40.00} 变成 {@code 4E+1}，
     * 必须用 {@code toPlainString()} 拉回来 —— 少了这一步，群里会冒出
     * 「封顶 4E+1 元」这种东西。
     *
     * @param amount 数值，可为 null
     * @return 形如 {@code 8} / {@code 3.5}
     */
    private static String plain(BigDecimal amount) {
        if (amount == null) {
            return "0";
        }
        return amount.stripTrailingZeros().toPlainString();
    }
}
