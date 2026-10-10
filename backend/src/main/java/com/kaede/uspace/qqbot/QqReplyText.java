package com.kaede.uspace.qqbot;

import com.kaede.uspace.billing.dto.BillingRulesVo;
import com.kaede.uspace.billing.event.FreePeriodChangeAction;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.notice.NoticePublishMode;
import com.kaede.uspace.order.OrderStatus;
import com.kaede.uspace.order.PaymentTargetType;
import com.kaede.uspace.order.dto.InstoreUserVo;
import com.kaede.uspace.order.dto.MonthSpentVo;
import com.kaede.uspace.order.dto.OrderPreviewVo;
import com.kaede.uspace.order.dto.OrderStatsVo;
import com.kaede.uspace.order.dto.OrderVo;
import com.kaede.uspace.product.dto.ProductOrderVo;
import com.kaede.uspace.product.dto.ProductVo;
import com.kaede.uspace.promotion.MonthlyCardType;
import com.kaede.uspace.promotion.dto.CardTypeVo;
import com.kaede.uspace.promotion.entity.MonthlyCard;
import com.kaede.uspace.space.dto.BookingScheduleVo;
import com.kaede.uspace.space.dto.StoreStatusVo;
import com.kaede.uspace.space.event.ClosureChangeAction;
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
 *   <li>金额只在 {@code fw看看自己}、{@code fw结账} 的回复里出现，
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
     * <p>写指令与「看看自己」共用同一句 —— 几处各写一份的话，
     * 改文案时漏掉一处，同一个原因在群里会看到三种说法。
     */
    public static final String NOT_BOUND =
            "该 QQ 尚未绑定账号。请先在网页端注册并完成 QQ 验证，之后即可使用。";

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
            "\n付款后也可将付款截图直接发送到群里（5 分钟内有效）。";

    /**
     * 认不出指令时的提示。
     *
     * <p>商品那条路是<b>封闭</b>的（只认 {@code 买…个…} 与 {@code 名-数量} 两种写法），
     * 所以拼错的指令（{@code fw在店铺}、{@code fw开门吧}）都会落到这里 ——
     * 不会被错怪成「没有这个商品」。这也是它值得单独一个常量的原因：
     * 它守着一类「答非所问」的体验。
     */
    private static final String UNKNOWN_COMMAND =
            "无法识别该指令。可发送 fw帮助 查看可用指令。";

    /**
     * 公告正文在群里的截断长度。
     *
     * <p>群消息窗口窄，一条公告把正文全铺开会占满整屏、把后面的消息推上去，
     * 而公告正文没有长度上限（只有标题限 100 字）。所以按字符截断，末尾给链接。
     */
    private static final int NOTICE_CONTENT_MAX = 60;

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
    /**
     * 名册图前的一句话（{@code fw在店} 发图时）。
     *
     * <p>格式由用户定（2026-10-10）：<b>一句话 + 图</b>，不是只发图 ——
     * 群里先看到「店内目前有 3 人」，不必点开图才知道几人在店。
     *
     * @param count 在店人数
     * @return 一句话
     */
    public static String instoreCount(int count) {
        return count == 0 ? "店内目前无人。" : "店内目前有 " + count + " 人。";
    }

    public static String instore(List<InstoreUserVo> users, Map<String, String> preferenceLabels,
                                 int maxListed) {
        if (users == null || users.isEmpty()) {
            return "当前店内无人。";
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
            return "近期暂无包场安排。";
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
                text.append("（本月 ").append(duration((int) stats.getMonthMinutes())).append('）');
            }
        }

        // 只报「待支付」：进行中的那一单归下一条说，两处都报就成了同一个数字说两遍
        if (pending != null && OrderStatus.PENDING_PAYMENT.name().equals(pending.getStatus())) {
            text.append("\n有一笔未付款订单：");
            if (amountVisible && pending.getPayableAmount() != null) {
                text.append(yuan(pending.getPayableAmount())).append('，');
            }
            text.append("单号 ").append(pending.getOrderNo());
            text.append("\n（重新获取付款入口请发送 fw结账）");
        }

        if (current != null) {
            text.append("\n当前在店 ").append(duration((int) current.getStayMinutes()));
            if (amountVisible && current.getBill() != null) {
                text.append("，预计 ").append(yuan(current.getBill().getTotalAmount()));
            }
            text.append("\n（查看分段明细请发送 fw当前订单）");
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
     * 「计时照走」并指去 {@code fw结账} —— 不写的话，用户会以为发一条指令就把账结了，
     * 然后接着玩、然后被账单吓一跳。
     *
     * <p>跳档预告（{@link OrderPreviewVo#getNextChangeText()}）里的金额<b>不受
     * 金额开关约束</b>：那是价目表上的档位价，群里任何人发 {@code fw价格} 都查得到，
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
            text.append("\n本次结账无需支付，将直接结清。");
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

        text.append("\n（仅为预览，计时照常进行。结账请发送 fw结账）");
        return text.toString();
    }

    /**
     * 没有在计时的单时的回复，附带待付款那一笔。
     *
     * <p>⚠️ <b>刻意不带「付款截图可以发群里」那句</b>：那是 {@link #settleAlreadyStopped}
     * 的待遇 —— 发过 {@code fw结账} 才会撑起「等一张图」那道闸（见 {@code QqPaymentProofService}）。
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
        StringBuilder text = new StringBuilder("你当前没有正在计时的订单。");
        text.append("\n有一笔未付款订单：");
        if (amountVisible && amount != null) {
            text.append(yuan(amount)).append('，');
        }
        text.append("单号 ").append(orderNo);
        text.append("\n付款入口：").append(ordersUrl);
        return text.toString();
    }

    /**
     * 「fw未付款」的清单。
     *
     * <p>四类单已由调用方换算成统一的 {@link UnpaidBill}，这里只排版 ——
     * 于是本方法可以脱离 Spring 单测（与其余文案方法一致）。
     *
     * <p><b>金额按开关显示</b>（{@code uspace.qqbot.self-amount-visible}）：
     * 群消息全群可见，欠费金额与消费金额同级 —— 关掉时不报数，
     * 但单号照给（下一步动作要靠它）。
     *
     * @param bills         清单，按调用方给的顺序展示（计时 → 包场 → 月卡 → 商品）
     * @param amountVisible 金额是否显示
     * @param webUrl        网页端地址（付款入口）
     * @return 多行文本
     */
    public static String unpaidBills(List<UnpaidBill> bills, boolean amountVisible, String webUrl) {
        if (bills.isEmpty()) {
            return "你没有未付款的单子。";
        }
        StringBuilder text = new StringBuilder("你有 ").append(bills.size()).append(" 笔未付款的单子：\n");
        for (UnpaidBill bill : bills) {
            text.append("· [").append(bill.typeLabel()).append("] ").append(bill.orderNo());
            if (amountVisible && bill.amount() != null) {
                text.append(" · ").append(yuan(bill.amount()));
            }
            text.append(" · ").append(bill.statusText()).append('\n');
        }
        text.append("付款请前往网页端：").append(webUrl);
        // 计时订单不可取消（欠费不能自消），所以可取消提示只在真的存在
        // 可取消单子时出现 —— 全部都是计时订单时给这条提示就是句空话
        if (bills.stream().anyMatch(UnpaidBill::cancelable)) {
            text.append("\n不需要的单子可发送 fw取消 <单号> 取消。");
        }
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
        text.append("首 ").append(rules.getGraceMinutes()).append(" 分钟免费，此后每 ")
                .append(rules.getUnitMinutes()).append(" 分钟一档。");

        if (rules.isDiscountEnabled()) {
            text.append("\n当月消费满 ").append(yuan(rules.getDiscountThreshold()))
                    .append(" 后，本月后续订单按优惠价：日场 ")
                    .append(plain(rules.getDiscountDayPricePerHour())).append(" 元/小时、夜场 ")
                    .append(plain(rules.getDiscountNightPricePerHour())).append(" 元/小时。");
        }
        // 月卡是另一种买法（包月），不在这张表里 —— 给一句指路，
        // 否则在群里问「月卡多少钱」的人得不到任何线索
        text.append("\n月卡另行计费（含全天、夜间两种），可发送 fw月卡 查看说明。");
        return text.toString();
    }

    /**
     * 商城菜单：店里卖什么、多少钱、还剩多少。
     *
     * <p>只列<b>上架中</b>的商品（数据源是 {@code ProductService.listOnSale}）——
     * 下架的不该出现在群里的菜单上。卖完的照列（余 0）：
     * 顾客看到「余（0）」才会问「什么时候补」，直接藏起来他只会以为店里没这个东西。
     *
     * <p>⚠️ <b>库存报的是可售量（{@code availableStock}），不是实际库存</b>：
     * 它与用户端商城页、下单校验共用同一个口径（见 {@code ProductVo} 的类注释）。
     * 报实际库存的话会出现「群里说还剩 3 件、下单却说售罄」—— 那 3 件被未支付的
     * 单子占着，而这种不一致是纯展示口径造成的，本可以避免。
     *
     * @param products     上架商品，已按后台排的序号排好
     * @param mallUrl      网页端商城地址
     * @param writeEnabled 写指令此刻可不可用（决定要不要提「群里下单」那两种写法）。
     *                     ⚠️ 与 {@link #help} 同一条纪律：提示了一条发不动的指令，
     *                     用户只会以为机器人坏了 —— 所以写指令关掉时只留网页端那条路
     * @return 多行文本；没有商品时回一句人话
     */
    public static String menu(List<ProductVo> products, String mallUrl, boolean writeEnabled) {
        if (products == null || products.isEmpty()) {
            return "当前暂无在售商品。";
        }
        StringBuilder text = new StringBuilder("在售商品（共 ")
                .append(products.size()).append(" 种）：\n");
        for (ProductVo product : products) {
            text.append(product.getName())
                    .append(" --- ").append(yuan(product.getPrice()))
                    .append(" -- ").append(stockLabel(product))
                    .append('\n');
        }
        // 两种写法各占一行、并与上面的商品列表隔一个空行（2026-10-09 由用户要求）——
        // 挤在商品行末尾的话，那两种语法要读到一半才发现是「怎么下单」
        text.append(writeEnabled
                        ? "\n下单请发送 fw买个xx 或 fwxx-数量\n或前往网页端商城："
                        : "\n下单请前往网页端商城：")
                .append(mallUrl);
        return text.toString();
    }

    /**
     * 商品的余量文案。
     *
     * <p>⚠️ 读的是<b>可售量</b>（{@code availableStock}）而不是实际库存 ——
     * 与用户端商城页、下单校验同一口径，理由见 {@link #menu} 的注释。
     *
     * <p>卖完不另起一个「售罄」的说法，就是 {@code 余（0）}：一列数读下来整齐，
     * 也省掉「售罄 / 余 0」两种说法的分叉。
     *
     * @param product 商品视图
     * @return 形如 {@code 余（5）}
     */
    private static String stockLabel(ProductVo product) {
        Integer available = product.getAvailableStock();
        return "余（" + (available == null ? 0 : available) + "）";
    }

    /**
     * 月卡说明：有哪几种卡、各多少钱、覆盖什么时段。
     *
     * <p>数据源是 {@code MonthlyCardService#cardTypes()} —— 与用户端月卡页
     * <b>同一个方法</b>，所以群里报的价与网页上看到的必然一致（同 {@link #menu}）。
     * 价格与有效期都从配置现取，运营调价后群里立刻跟着变 —— 文案里不写死数字。
     *
     * <p>只讲「有哪些卡、多少钱、管哪些时段」；<b>怎么用、能省多少</b>留给
     * 网页端那一页去讲 —— 群里塞太多字，读的人反而抓不到重点。
     *
     * @param types       在售卡种，按枚举顺序（全天在前）
     * @param purchaseUrl 网页端月卡页地址（买卡在那儿）
     * @return 多行文本；没有卡种时回一句人话
     */
    public static String cardTypes(List<CardTypeVo> types, String purchaseUrl) {
        if (types == null || types.isEmpty()) {
            return "月卡暂未开放售卖，请联系门店管理员。";
        }
        StringBuilder text = new StringBuilder("月卡（有效期 ")
                .append(types.get(0).getValidDays()).append(" 天）：\n");
        for (CardTypeVo type : types) {
            text.append(type.getLabel())
                    .append(" --- ").append(yuan(type.getPrice()))
                    .append(" -- ").append(type.getPeriodText())
                    .append('\n');
        }
        text.append("有效期内所覆盖时段不计费。购买请前往：").append(purchaseUrl);
        return text.toString();
    }

    /**
     * 网页端地址。
     *
     * <p>⚠️ <b>拆成两条消息发</b>（2026-10-09 由用户要求）：一条讲解用途、一条<b>只放网址</b>。
     * 网址单独占一条才好复制 —— 混在句子里长按选中，多半会把「网页端：」这类前缀
     * 一起带进去，粘到浏览器里就打不开了。
     *
     * <p>地址原样来自配置（{@code uspace.web.base-url}），这里不加工 ——
     * 加工过一次的话，群里看到的地址与配置里那一项就对不上了，
     * 排查时反而多一层要怀疑的东西。
     *
     * @param url 完整地址；没配置时只回一条提示
     * @return 要依次发出的消息（正常两条、没配置时一条）
     */
    public static List<String> web(String url) {
        if (url == null || url.isBlank()) {
            return List.of("网页端地址尚未配置，请联系门店管理员。");
        }
        return List.of("网页端可以下单、查看账单、购买月卡。", url);
    }

    /**
     * 与 NapCat 的连接建立时的群提示。
     *
     * <p><b>它补的是「重连没有动静」这半边</b>：断开在后端有日志、在 NapCat
     * 界面上也有显示，而重连成功此前两边都悄无声息 —— 运营者只能发一条
     * {@code fwping} 去猜。这条让群里直接看到「机器人回来了」。
     *
     * <p>措辞刻意不带「重连」：对群里的人来说，机器人只有「能用」与
     * 「不能用」两种状态，内部重连了几次与他们无关；这句话出现在断线之后，
     * 本身就已经说明了是重连。
     *
     * @return 提示文本
     */
    public static String botConnected() {
        return "🤖 机器人已连接，指令可正常使用。发送 fw帮助 查看可用指令。";
    }

    /**
     * 付款凭证被驳回的提醒。
     *
     * <p>⚠️ <b>文案里不写金额</b>：群消息对所有群成员可见，而金额按群分级
     * 只进店主群（见 {@code QqbotProperties#isAmountVisible}）。这条提醒的受众
     * 就是被驳回的那个人，他不缺「自己花了多少」这个信息；要看点进网页端就有。
     *
     * <p><b>说的是「请重新上传」—— 因为驳回之后这件事真的做得到了。</b>
     * 驳回会把订单退回待支付（见 {@code PaymentProofService#reject} 与
     * {@code PaymentTargetHandler#revertDelivery}），用户点进去就能重新传一张，
     * 群里那句话与页面上的入口是同一件事。
     *
     * <p>（2026-10-04 之前这里写的是「点这里看看」，因为那时系统对
     * 「已支付 + 已驳回」明确拒绝重交 —— 喊了做不到的话不如不喊。
     * 状态回退做出来之后那条限制没有了，文案随之翻回来。）
     *
     * <p>开头那个空格是给 @ 段留的：少了它，群里会显示成
     * {@code @张三你的付款凭证……}，两截挤在一起。
     *
     * <p>⚠️ <b>「不要重复付款」这半句不能省</b>：驳回会把订单退回待支付
     *（见 {@code PaymentProofService#reject}），于是用户端看到的是「待支付 ¥8.00」——
     * 而他明明付过。不说这一句，他很可能再扫一次码，<b>真的付第二遍</b>。
     * 那笔多余的款子退起来比提醒一句麻烦得多。
     *
     * @param reason    管理员填写的驳回原因（必填），原样出现在文案里
     * @param ordersUrl 订单列表页地址（被驳回的那几笔都在那儿）
     * @return 提示文本
     */
    public static String proofRejected(String reason, String ordersUrl) {
        return " 你的付款凭证未通过复核：" + reason
                + "。请重新上传一张付款截图（如已完成付款，请勿重复支付） → " + ordersUrl;
    }

    /**
     * 新包场生效的播报。
     *
     * <p><b>只播时段、不播包场人</b> —— 与 {@link #bookingSchedule} 同一条披露边界
     * （那个 VO 里压根没有包场人字段）。也刻意<b>不播金额</b>：群消息所有人可见，
     * 而「这场收了多少钱」属于经营信息（含金额的版本只进店主群，见
     * {@code QqbotProperties#isAmountVisible}），这条播报没有分两个版本的理由。
     *
     * <p>日期与时刻的写法与 {@code fw包场} 指令<b>逐字一致</b>（含「跨零点时
     * 只报到时刻为止」这一处）—— 两处各写一份的话，同一场包场在群里会有
     * 两种说法。要改格式就两处一起改。
     *
     * @param startAt 包场开始时刻
     * @param endAt   包场结束时刻
     * @param today   今天，用于把日期说成「今天 / 明天」
     * @return 播报文本
     */
    public static String bookingActivated(LocalDateTime startAt, LocalDateTime endAt,
                                          LocalDate today) {
        return "📅 包场已安排：" + dayLabel(startAt.toLocalDate(), today)
                + " " + TIME.format(startAt) + " – " + TIME.format(endAt)
                + "，该时段仅限包场人与被邀请者入场。";
    }

    /**
     * 有人买了商品的播报，形如
     * {@code 🛒 Kaede 购买了魔爪 ×2，还剩 3 件}。
     *
     * <p><b>不含金额</b> —— 这条对所有群发同一份文本
     * （与到店、包场播报同一条披露边界）。「还剩」报的是<b>可售量</b>，
     * 与商城页、{@code fw菜单} 同一口径；为 0 时照 {@code fw菜单} 的规矩
     * 老实显示「还剩 0 件」，不另起「售罄」的说法 —— 两处口径不同的话，
     * 群里说售罄、商城里却还买得到，谁都不知道该信哪个。
     *
     * @param nickname       购买人昵称
     * @param productName    商品名（下单时的快照）
     * @param quantity       数量，null 按 1 件处理
     * @param availableStock 当前可售量；<b>为 null 时不带「还剩」</b>
     *                       （商品已被删除时查不到）—— 查不到就如实不说，
     *                       编一个数出来群里没人分得清真假
     * @return 播报文本
     */
    public static String productPurchased(String nickname, String productName,
                                          Integer quantity, Integer availableStock) {
        StringBuilder text = new StringBuilder("🛒 ")
                .append(nickname)
                .append(" 购买了")
                .append(productName)
                .append(" ×").append(quantity == null ? 1 : quantity);
        if (availableStock != null) {
            text.append("，当前剩余 ").append(availableStock).append(" 件");
        }
        return text.toString();
    }

    /**
     * 停业时段新增 / 撤销的播报。
     *
     * <p><b>撤销也带时段</b>：群里可能积着好几条停业安排，只说「已撤销」，
     * 没人知道撤的是哪一段。原因则不重复 —— 撤销时那句「设备维护」已无意义，
     * 时段本身就足以对上号。
     *
     * @param action  新增还是撤销
     * @param startAt 停业开始时刻
     * @param endAt   停业结束时刻
     * @param reason  停业原因（新增时带上，让群里知道为什么不开），可为 null
     * @param today   今天，用于把日期说成「今天 / 明天」
     * @return 播报文本
     */
    public static String closureChanged(ClosureChangeAction action, LocalDateTime startAt,
                                        LocalDateTime endAt, String reason, LocalDate today) {
        String range = rangeText(startAt, endAt, today);
        if (action == ClosureChangeAction.DELETED) {
            return "🚧 停业安排已撤销：" + range + "，该时段恢复正常接待。";
        }
        return "🚧 门店停业安排：" + range + parenthesized(reason) + "，该时段不接待新顾客。";
    }

    /**
     * 免费活动新增 / 撤销的播报。
     *
     * <p>写的是「<b>消费全免</b>」而不是含糊的「免费」—— 免的是账单金额，
     * 不影响开门与准入（那是停业管的事，两者是两回事，见计费规则一节）。
     * 撤销时明说「恢复按时长计费」，免得有人以为还能白玩。
     *
     * @param action  新增还是撤销
     * @param startAt 活动开始时刻
     * @param endAt   活动结束时刻
     * @param reason  活动名称，可为 null
     * @param today   今天，用于把日期说成「今天 / 明天」
     * @return 播报文本
     */
    public static String freePeriodChanged(FreePeriodChangeAction action, LocalDateTime startAt,
                                           LocalDateTime endAt, String reason, LocalDate today) {
        String range = rangeText(startAt, endAt, today);
        if (action == FreePeriodChangeAction.DELETED) {
            return "🎉 免费活动已撤销：" + range + "，该时段恢复按时长计费。";
        }
        return "🎉 免费活动：" + range + parenthesized(reason) + "，该时段内消费全免。";
    }

    /**
     * 包场撤销的播报。
     *
     * <p>与 {@code fw包场} 指令和「已安排包场」{@link #rangeText 同一套时段写法} ——
     * 撤销的这一条要能与几天前那条生效消息对得上，
     * 否则群里读不出这两条说的是同一场包场。
     *
     * @param startAt 包场开始时刻
     * @param endAt   包场结束时刻
     * @param today   今天，用于把日期说成「今天 / 明天」
     * @return 播报文本
     */
    public static String bookingRevoked(LocalDateTime startAt, LocalDateTime endAt,
                                        LocalDate today) {
        return "📅 包场已撤销：" + rangeText(startAt, endAt, today) + "，该时段恢复开放。";
    }

    /**
     * 把一段起止时刻写成「今天 14:00 – 18:00」。
     *
     * <p>与 {@link #bookingActivated} 和 {@code fw包场} 指令<b>逐字同一套写法</b>
     * （含「跨零点时只报到时刻为止」这一处）—— 几处各写一份的话，
     * 同一段时间在群里会有好几种说法。
     *
     * @param startAt 起始时刻
     * @param endAt   结束时刻
     * @param today   今天，用于把日期说成「今天 / 明天」
     * @return 形如 {@code 明天 14:00 – 18:00}
     */
    private static String rangeText(LocalDateTime startAt, LocalDateTime endAt, LocalDate today) {
        return dayLabel(startAt.toLocalDate(), today)
                + " " + TIME.format(startAt) + " – " + TIME.format(endAt);
    }

    /**
     * 把可选的原因 / 名称括起来；没填时返回空串。
     *
     * <p>空串与 null 都当作「没填」—— 库里这两种表示都有可能出现
     * （表单没填是 null，清空过是空串），播报不该为它印出「（）」。
     *
     * @param value 原始文本，可为 null
     * @return 形如 {@code （设备维护）}；没填时为空串
     */
    private static String parenthesized(String value) {
        return value == null || value.isBlank() ? "" : "（" + value + "）";
    }

    /**
     * 新公告发布的播报。
     *
     * <p><b>两类公告的文案不同</b>：
     * <ul>
     *   <li><b>手写公告</b>带正文与详情链接 —— 它是运营主动说的话，
     *       正文往往才是重点（标题「本周六场地维护」+ 正文写具体时段）</li>
     *   <li><b>自动公告</b>只发标题 —— 标题本身就是一句完整的事件描述
     *       （「3 号机台由 良好 转为 维护中」），它没有正文，
     *       点进去也只是列表页，链接同样是多余的</li>
     * </ul>
     *
     * <p>正文经 {@link #truncate} 压成一行并截断，超长的部分点链接看全文。
     *
     * @param mode       发布方式；<b>只有 MANUAL 走带正文那一支</b>，
     *                   其余取值（含 null）一律按自动公告处理 —— 信息少的那一侧更保守
     * @param title      公告标题
     * @param content    公告正文，可为 null（自动公告恒为 null）
     * @param noticesUrl 网页端「全部公告」页地址
     * @return 播报文本
     */
    public static String noticePublished(NoticePublishMode mode, String title,
                                         String content, String noticesUrl) {
        if (mode != NoticePublishMode.MANUAL) {
            return "📢 " + title;
        }
        StringBuilder text = new StringBuilder("📢 门店公告：").append(title);
        String body = truncate(content, NOTICE_CONTENT_MAX);
        if (body != null) {
            text.append('\n').append(body);
        }
        return text.append("\n详情 → ").append(noticesUrl).toString();
    }

    /**
     * 有一笔付款凭证进了人工复核队列（2026-10-10 加，只推店主群）。
     *
     * <p><b>为什么需要这条</b>：小额收款识别到交易单号就自动结清了，
     * 而没识别到单号的、以及月卡包场这类大额收款要等人看 ——
     * 人工复核最大的风险不是看错，是<b>没人记得看</b>。这条播报把
     * 「记得去后台」交给群消息。
     *
     * <p>带上金额与原因：这条只进店主群（金额可见性由
     * {@code QqbotProperties#isAmountVisible} 判定），而管理员要判断的
     * 正是「多少钱、为什么值得看一眼」。
     *
     * @param targetType 收款类型名（如 {@code ORDER}），这里翻成中文
     * @param orderNo    对外单号
     * @param amount     应付金额（元）
     * @param reason     为什么进人工复核，一句短语
     * @param adminUrl   运营后台「收款管理」页地址
     * @return 播报文本
     */
    public static String proofPending(String targetType, String orderNo,
                                      BigDecimal amount, String reason, String adminUrl) {
        return "🧾 有一笔付款凭证待复核：" + PaymentTargetType.labelOf(targetType)
                + " " + orderNo + "，金额 " + yuan(amount) + "（" + reason + "）。"
                + "请前往运营后台处理：" + adminUrl;
    }

    /**
     * 指令列表（按「你想干什么」分组）。
     *
     * <p>顺带写明前缀要求 —— 这是「必须带前缀」那条规矩唯一的说明处，
     * 少了它用户会照着指令名裸发，然后什么都得不到。
     *
     * <p><b>分组是 2026-10-04 排的</b>：指令到十来条之后，一列平铺得逐行读完
     * 才能找到自己要的那条。分成「看店里 / 我自己的 / 常用 / 其他」四段之后，
     * 扫一眼就知道该发哪条。⚠️ 分组<b>只影响这一条回复的排版</b>，
     * 不改变任何识别规则（别名与整条精确匹配都在 {@code QqCommandParser} 里）。
     *
     * @param writeEnabled 写指令是否启用（关掉时那一组整段不列出来，
     *                     否则用户发了没反应，而原因无处可查）
     * @param admin        发送者是不是管理员 —— <b>调整库存那条只对管理员列</b>：
     *                     它对别人本来就发不动（执行时会拒绝），列出来只会让人问
     *                     「为什么我不能用」。为此多查一次 {@code sys_user} 是划算的，
     *                     {@code fw帮助} 本来就是低频指令
     * @param webUrl       网页端地址，放在最底下（2026-10-09 由用户要求）——
     *                     它是这条长消息里最常被回头找的一样东西；
     *                     没配置时这一行不出现
     * @return 多行文本
     */
    public static String help(boolean writeEnabled, boolean admin, String webUrl) {
        // ⚠️ 前缀只写 fw（2026-10-09 起 `/` 已不是前缀，发 `/在店` 会当闲聊静默）——
        // 这里曾写着「前缀 / 或 fw」，是那次改动漏掉的一处，别改回去
        StringBuilder text = new StringBuilder("可用指令（前缀 fw，例如 fw在店）：\n");

        text.append("\n【看店里】\n");
        text.append("fw在店 或 fw看看里面，也可直接发 kklm —— 查看当前在店人员\n");
        text.append("fw营业 或 fwstatus —— 查看门店营业状态\n");
        text.append("fw包场 或 fwbooking —— 查看近期包场安排\n");
        text.append("fw菜单 或 fwmenu —— 查看在售商品与库存\n");
        text.append("fw价格 或 fwprice —— 查看计费规则\n");
        text.append("fw月卡 或 fwpass —— 查看月卡种类与价格\n");

        text.append("\n【我自己的】\n");
        text.append("fw看看自己 或 fwme —— 查看本人的月卡、消费与在店时长\n");
        text.append("fw当前订单 或 fwnow —— 查看当前订单金额（仅预览，不停表）\n");
        text.append("fw未付款 或 fwunpaid —— 查看本人未付款的单子\n");

        if (writeEnabled) {
            text.append("\n【常用】\n");
            text.append("fw开门 或 fwopen —— 开始计时并获取门锁密码\n");
            text.append("fw结账 或 fwsettle —— 停止计时并前往付款\n");
            text.append("fw买个可乐 或 fw可乐-2 —— 下单购买商品\n");
            text.append("fw取消 <单号> —— 取消未付款的商品单、月卡购买单或包场\n");
        }
        if (writeEnabled && admin) {
            text.append("\n【管理】\n");
            text.append("fw可乐5 —— 把该商品的库存调整为 5（仅管理员）\n");
            text.append("fw拍拍机 1 号维护中 —— 把该机台状况改为维护中"
                    + "（仅管理员，也可写「待维护」「良好」）\n");
        }

        text.append("\n【其他】\n");
        text.append("fwweb 或 fw网址 —— 查看网页端地址（下单、查账单）\n");
        text.append("fwping 或 fw在吗 —— 检测机器人是否在线\n");
        text.append("fw帮助 或 fwhelp —— 显示本消息\n");

        text.append("\n注册时网页会提供一条以「fw验证 」开头的指令，复制发送到群里即可完成 QQ 号绑定。");
        if (webUrl != null && !webUrl.isBlank()) {
            text.append("\n\n网页端：").append(webUrl);
        }
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
     * @param passcode            一次性门锁密码
     * @param newlyCreated        这次是不是才建的订单（决定要不要自报状态）
     * @param stayMinutes         已在店时长（分钟）；刚刚建单时为 0
     * @param privatePasscodeSent 固定密码有没有私聊给他（{@code uspace.qqbot.private-passcode-enabled}）——
     *                            ⚠️ 关着的时候<b>绝不能</b>还写「已私聊发你」，
     *                            那会让人去翻一个永远空的私聊窗口
     * @param homeUrl             网页端<b>首页</b>地址，固定密码的兜底指路 ——
     *                            ⚠️ 指首页而不是订单详情页（2026-10-09 由用户指出）：
     *                            人在店里时首页就显示着当前这一单与「查看密码」入口，
     *                            指订单详情要多绕一层
     * @return 回复文本
     */
    public static String openPasscode(String passcode, boolean newlyCreated,
                                      Integer stayMinutes, boolean privatePasscodeSent,
                                      String homeUrl) {
        StringBuilder text = new StringBuilder();
        if (!newlyCreated && stayMinutes != null) {
            text.append("你已在计时中（").append(duration(stayMinutes)).append("）。\n");
        }
        text.append("门锁密码：").append(passcode).append("\n");
        text.append("该密码仅可使用一次，请勿转发。");
        text.append(privatePasscodeSent
                ? "固定密码已通过私聊发送，如未收到请前往网页端查看："
                : "如需可重复使用的密码，请前往网页端查看：");
        text.append(homeUrl);
        return text.toString();
    }

    /**
     * 订单建好了、但一次性密码没取到时的回话。
     *
     * <p>⚠️ <b>这一条不能省</b>：人已经被计费了（订单已建），群里静悄悄的话
     * 他只会站在门口等一串永远不来的密码。见 {@code QqWriteCommandService#openDoor}。
     *
     * @param reason              失败原因（取自 {@code BizResult.resolveMessage()}）
     * @param privatePasscodeSent 固定密码有没有私聊给他（决定兜底指路怎么写）
     * @param homeUrl             网页端<b>首页</b>地址（同 {@link #openPasscode}，指首页而非订单详情页）
     * @return 回复文本
     */
    public static String openPasscodeFailed(String reason, boolean privatePasscodeSent,
                                            String homeUrl) {
        return "已开始计时，但门锁密码获取失败：" + reason
                + (privatePasscodeSent ? "\n固定密码已通过私聊发送，也可前往网页端查看：" : "\n请前往网页端查看密码：")
                + homeUrl;
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
            return "开门失败" + (message == null ? "，请稍后重试。" : "：" + message);
        }
        return switch (error) {
            case STORE_CLOSED -> "门店当前暂停营业，暂时无法开门。";
            case BOOKING_ACCESS_DENIED -> "该时段已被包场，仅限包场人与被邀请者入场。";
            case BOOKING_PREPARING -> "该时段即将开始包场，暂时无法入场。";
            case ORDER_UNPAID_EXISTS -> "你有一笔未付款订单，请先完成付款后再开门：" + ordersUrl;
            case LOCK_NOT_CONFIGURED, STORE_NOT_FOUND -> "门店尚未配置完成，请联系管理员。";
            case LOCK_CLOUD_UNAVAILABLE -> "门锁云服务暂不可用，请稍后重新发送 fw开门。";
            default -> "开门失败：" + (message == null ? "请稍后重试。" : message);
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
            return "已停止计时，本次无需支付，账目已结清。";
        }
        if (!amountVisible) {
            // 金额被关掉时（uspace.qqbot.self-amount-visible=false）不报数 ——
            // 打开结算页自然看得到，而这里报了就是全群可见
            return "已停止计时，请前往支付：" + settleUrl + PAY_BY_GROUP_HINT;
        }
        return "已停止计时，本次应付 " + yuan(amount) + "。\n请前往支付：" + settleUrl + PAY_BY_GROUP_HINT;
    }

    /**
     * 再发一次 {@code fw结账} 时，账其实已经停过表了的回复。
     *
     * <p>⚠️ <b>这条回复是一条回头路，不是客套</b>：群内传截图有 5 分钟时限，
     * 超时那句提示写着「重新发一次 fw结账」—— 而那时订单早已不是 {@code IN_USE}
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
        StringBuilder text = new StringBuilder("该订单已停止计时，尚未付款。");
        if (amountVisible && amount != null) {
            text.append("\n应付 ").append(yuan(amount));
        }
        text.append("\n订单 ").append(orderNo).append(" · 付款入口：").append(orderUrl);
        return text.append(PAY_BY_GROUP_HINT).toString();
    }

    /**
     * 取消未付款单成功的回复。
     *
     * <p>三类单共用本方法，差别只在「取消之后释放了什么」那一句 ——
     * 由调用方以 {@code hint} 传入（库存 / 卡种 / 时段）。不在这里按类型分叉：
     * 分叉要认识三个模块的枚举，而文案层只需认识
     * {@link UnpaidBill} 那一层抽象。
     *
     * @param typeLabel 类型中文名（商品 / 月卡 / 包场）
     * @param orderNo   单号
     * @param hint      取消后释放了什么的说明（如「占用的库存已释放」）
     * @return 回复文本
     */
    public static String cancelDone(String typeLabel, String orderNo, String hint) {
        return "已取消未付款的" + typeLabel + " " + orderNo + "，" + hint + "。";
    }

    /**
     * 取消失败时的回复，按错误码分派。
     *
     * <p><b>两档分开说，因为用户的下一步动作不同</b>：「找不到」多半是
     * 单号抄错或不是自己的单子，该去核对（顺手指路 {@code fw未付款} 查单号）；
     * 「状态不对」说明单子已付款或已被处理，该去网页端看究竟。
     *
     * <p>六个错误码归成两档而不是逐码定制：三个模块的 {@code *_NOT_FOUND}
     * 口径一致（都不区分「不存在」与「不是你的」，防枚举单号），
     * 状态类同理。分得更细的话，文案与错误码表就成了两份要同步维护的清单。
     *
     * @param error   失败错误码
     * @param message 服务端给的原因，可为 null
     * @return 回复文本
     */
    public static String cancelFailed(ErrorCode error, String message) {
        return switch (error) {
            case PRODUCT_ORDER_NOT_FOUND, CARD_NOT_FOUND, BOOKING_NOT_FOUND ->
                    "没找到这个单号，或它不是你的单子。可发送 fw未付款 查看自己的未付款单。";
            case PRODUCT_STATUS_INVALID, CARD_STATUS_INVALID, BOOKING_NOT_EDITABLE ->
                    "这笔单当前的状态不支持取消"
                            + (message == null ? "（可能已付款或已被处理）" : "（" + message + "）")
                            + "。详情请前往网页端查看。";
            default -> "取消失败：" + (message == null ? "请稍后重试。" : message);
        };
    }

    /**
     * 取消计时订单被拒的回复。
     *
     * <p><b>这不是「暂不支持」，而是设计边界</b>：欠费不能靠一句指令自消。
     * 所以文案必须说清去处 —— 正在计时的发 {@code fw结账}，
     * 已出账的去网页端付款（凭证被驳回的在那儿重新上传）。
     *
     * @param ordersUrl 网页端订单页地址
     * @return 回复文本
     */
    public static String cancelOrderRefused(String ordersUrl) {
        return "计时订单不支持在群里取消。\n"
                + "正在计时的请发送 fw结账 停止计时；已出账的请前往网页端付款"
                + "（凭证未通过时请重新上传付款截图）：" + ordersUrl;
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
     * 所以直接告诉他名字不对，并指去 {@code fw菜单} —— 那里的名字可以直接复制，
     * 而名字对不上多半是因为商品名里那个空格或全角括号（如「王老吉 250ml（绿）」）。
     *
     * @param name 他打出来的那个名字（原样回显，让他一眼看出差在哪）
     * @return 提示文本
     */
    public static String productNotFound(String name) {
        return "未找到名为「" + name + "」的商品。可发送 fw菜单 查看在售商品，"
                + "名称须完全一致（含空格与括号）。";
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
        StringBuilder text = new StringBuilder("下单成功：")
                .append(order.getProductName()).append(" ×").append(order.getQuantity());
        if (amountVisible) {
            // 与 fw结账 同一口径：金额受 self-amount-visible 控制（回复是群消息、全群可见），
            // 关掉时不报数 —— 付款页上本来就看得到
            text.append("，共 ").append(yuan(order.getAmount()));
        }
        text.append("\n单号 ").append(order.getOrderNo())
                .append("\n付款入口：").append(ordersUrl);
        text.append(PAY_BY_GROUP_HINT);
        text.append("\n付款后请自行取货。");
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
            return "下单失败" + (message == null ? "，请稍后重试。" : "：" + message);
        }
        return switch (error) {
            case PRODUCT_SOLD_OUT -> "该商品已售罄" + (message == null ? "" : "（" + message + "）")
                    + "。可发送 fw菜单 查看其他商品：" + menuUrl;
            case PRODUCT_STATUS_INVALID -> "该商品已下架。可发送 fw菜单 查看其他商品：" + menuUrl;
            case PARAM_INVALID -> message == null ? "数量不合适，请修改后重新发送。" : message + "。";
            // 下面两条的主语是「名下那笔旧单」而不是「这次要买的东西」——
            // 用默认那条「下单失败：…」的话，用户会以为这次买的东西有问题，
            // 换个商品接着试，然后又撞同一堵墙
            case PRODUCT_UNPAID_EXISTS -> message == null
                    ? "你有一笔未付款的商品单，请先完成支付或取消该单，再下单。"
                    : message + "。取消可在网页端「商品订单」页操作。";
            case PRODUCT_PROOF_REJECTED ->
                    "你的商品单付款凭证未通过复核，请先在网页端重新上传一张付款截图，再下单。";
            default -> "下单失败：" + (message == null ? "请稍后重试。" : message);
        };
    }

    /**
     * 库存调整成功的回复（{@code fw可乐5}）。
     *
     * <p>⚠️ <b>必须报出「原库存 → 新库存」</b>：这条指令是把库存<b>设成</b>给定值，
     * 一个字的差错就是把库存改成另一个数（想补货到 20、手一快发成了 fw可乐2）。
     * 不报原值的话，改错了只有下次清点时才会发现 —— 而那时早卖乱了。
     *
     * @param name      商品名（原样回显，让他一眼看出名字切得对不对）
     * @param before    调整前的库存
     * @param after     调整后的库存
     * @param available 当前可售量（= 库存 − 未付款的待支付单占用），由商品模块算好
     * @return 回复文本
     */
    public static String stockAdjusted(String name, int before, int after, int available) {
        StringBuilder text = new StringBuilder("已调整「").append(name).append("」库存：")
                .append(before).append(" 件 → ").append(after).append(" 件");
        if (available != after) {
            // 只在两者不同时补一句。不同就意味着有未付款的订单占着货 ——
            // 不说的话，管理员会奇怪「明明改成 5 了，顾客怎么还说买不了」
            text.append("，当前可售 ").append(available).append(" 件");
        }
        return text.append("。").toString();
    }

    /**
     * 非管理员发了库存指令时的回复。
     *
     * <p>⚠️ <b>必须带购买引导</b>：{@code fw可乐5} 与「买 2 件可乐」的写法
     * （{@code fw可乐-2}）只差一个横杠，发这条的多半是想买可乐的顾客 ——
     * 只回一句「仅限管理员」，他不知道自己该发什么。
     *
     * <p>不报「你是谁」也不报管理员是谁：群消息全群可见，
     * 而这条回复对任何人都一样。
     *
     * @return 提示文本
     */
    public static String stockAdjustForbidden() {
        return "「调整库存」仅限管理员使用。如需购买商品，请发送 fw买个可乐 或 fw可乐-2。";
    }

    /**
     * 群内调整机台状况成功的回复。
     *
     * <p>报「原 → 新」：手快发错了（比如把「维护中」发成了「良好」）当场看得见；
     * 只报一个结果的话，要等顾客来问才会发现牌子挂错了。
     *
     * <p>顺带提一句公告：状况真的变化时，{@code DeviceService.updateStatus} 会往
     * 首页公告栏记一条（「机台 1 号由良好转为维护中」）—— 告诉管理员一声，
     * 否则他会以为还得再去后台补一条公告。
     *
     * @param name      机台名
     * @param fromLabel 变更前状况的中文名，如「良好」
     * @param toLabel   变更后状况的中文名，如「维护中」
     * @return 回复文本
     */
    public static String deviceStatusAdjusted(String name, String fromLabel, String toLabel) {
        return "已将「" + name + "」的状况由「" + fromLabel + "」改为「" + toLabel
                + "」，门店公告已同步更新。";
    }

    /**
     * 目标状况与当前相同时的回复。
     *
     * <p>单独一条而不是复用上面那条：报「维护中 → 维护中」看着像出了故障，
     * 而事实是「你要的状态已经是了」—— 与网页端「重复点两下不算错误」同一口径。
     *
     * @param name  机台名
     * @param label 当前状况的中文名
     * @return 回复文本
     */
    public static String deviceStatusUnchanged(String name, String label) {
        return "「" + name + "」当前的状况已经是「" + label + "」，未做改动。";
    }

    /**
     * 非管理员发了机台状况指令时的回复。
     *
     * <p><b>刻意不带操作引导</b>（与库存那条不同）：库存指令有个只差一个横杠的
     * 近亲写法（{@code fw可乐5} 与 {@code fw可乐-2}），所以那条要教他「怎么买」；
     * 而 {@code fw拍拍机 1 号维护中} 没有任何顾客照着能办成事的样子 ——
     * 顾客想干的事是开门，不是报修。
     *
     * @return 提示文本
     */
    public static String deviceStatusForbidden() {
        return "「调整机台状况」仅限管理员使用。如需查看机台信息，请前往网页端。";
    }

    /**
     * 按名字没找到机台时的回复。
     *
     * @param name 管理员打的机台名
     * @return 提示文本
     */
    public static String deviceNotFound(String name) {
        return "未找到名为「" + name + "」的机台。名称须完全一致（含空格），"
                + "可前往网页端查看机台列表。";
    }

    /**
     * 多台机台同名时的回复。
     *
     * <p>不报「有几台」、也不替管理员挑一台：这条指令的输入只有名字，
     * 挑错的后果是改错机器，而回复看起来一切正常。
     *
     * @param name 管理员打的机台名
     * @return 提示文本
     */
    public static String deviceNameAmbiguous(String name) {
        return "有多台机台都叫「" + name + "」。群内按名字只能改唯一的一台，"
                + "请前往网页端处理（可先在后台改掉重名，或按机台直接操作）。";
    }

    /**
     * 群内付款截图已受理（2026-10-10 改：结尾分「已结清」与「等复核」两种）。
     *
     * <p>识别到的金额与流水号原样报出来 —— 它们是<b>辅助线索</b>，
     * 用户看一眼就知道系统读对没有。
     *
     * <p>⚠️ <b>结尾那句必须与事实相符</b>：识别到有效单号时这笔已经当场结清了
     * （小额自动通过），再说一句「管理员将进行复核」会让他以为还欠着钱、
     * 甚至再付一次；反过来，没识别到单号时绝不能报「已结清」——
     * 那笔还挂在待复核上，钱一分都没算数。
     *
     * @param outTradeNo 商户单号（房间订单是 {@code OD…}、商品是 {@code PD…}）
     * @param amount     识别到的金额，可为 null（没识别出）
     * @param paymentNo  识别到的流水号，可为 null
     * @param settled    这笔是不是已经结清了（{@code ProofSubmitVo#isDelivered}）
     * @return 回复文本
     */
    public static String proofAccepted(String outTradeNo, BigDecimal amount, String paymentNo,
                                       boolean settled) {
        // 文案里说的是「单号」而不是「订单」：这条路既收房间订单的凭证，也收商品的
        StringBuilder text = new StringBuilder("已收到付款截图，单号 ").append(outTradeNo);
        if (amount != null) {
            text.append("\n识别到金额 ").append(yuan(amount));
        }
        if (paymentNo != null && !paymentNo.isBlank()) {
            // 说「流水号」而不是「单号」：上面那个「单号」指的是这一笔业务单，
            // 两个「单号」并排出现时读的人分不清谁是谁
            text.append("，流水号 ").append(paymentNo);
        }
        if (settled) {
            text.append("\n本次已结清。");
        } else {
            text.append("\n尚未结清：管理员将人工复核这张截图。");
            if (paymentNo == null || paymentNo.isBlank()) {
                text.append("若截图里本应有交易单号，也可换一张更清晰的重新发送。");
            }
        }
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
    static String displayName(String nickname, Long userId) {
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
     * 截断过长的文本（用于公告正文）。
     *
     * <p>两处刻意的处理：
     * <ul>
     *   <li><b>换行与连续空白压成单个空格</b>：正文可能是多行的（列表、分段），
     *       而群里一条播报铺成五行会把聊天记录整个推上去。压平之后再决定要不要截</li>
     *   <li><b>按字符截而不是字节</b>：中文一个字符三字节，按字节截会切出
     *       半个汉字（群里显示成乱码方块）</li>
     * </ul>
     *
     * @param text  原文，可为 null
     * @param limit 最多保留几个字符
     * @return 压平并截断后的文本；原文为空时返回 null
     */
    private static String truncate(String text, int limit) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
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
