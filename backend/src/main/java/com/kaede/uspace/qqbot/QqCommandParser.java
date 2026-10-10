package com.kaede.uspace.qqbot;

import com.kaede.uspace.device.DeviceStatus;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 群消息文本 → 指令（模块 11）。
 *
 * <p><b>纯静态、无状态、不依赖 Spring</b>，因此可以脱离容器单测。
 * 这一层值得测得很密：它认错了不会报任何错，只会答非所问 ——
 * 而那看起来像「机器人变笨了」，不像一个 bug。
 *
 * <h3>识别规则</h3>
 *
 * <p>先剥掉消息<b>开头的 @ 提及</b>（群里 @机器人 再说指令是常见用法，
 * 而 {@code raw_message} 里那个 @ 是一段 {@code [CQ:at,qq=...]}），
 * 再看有没有<b>前缀</b>（{@code fw}，2026-10-09 起它是唯一的一种），然后<b>整条精确匹配</b>：
 *
 * <ul>
 *   <li>{@code fw在店} / {@code fwinstore} / {@code fw看看里面} → {@link QqCommand.Kind#INSTORE}</li>
 *   <li>{@code fw验证 123456} 与 {@code fw取消 &lt;单号&gt;} → 两条带参数的指令，
 *       见 {@link QqCommand.Kind#VERIFY_CODE} 与 {@link QqCommand.Kind#CANCEL_ORDER}</li>
 *   <li>{@code fw开门} / {@code fw结账} / {@code fw包场} / {@code fw看看自己} / {@code fw营业} / {@code fw价格} / {@code fw月卡} → 各自对应</li>
 *   <li>{@code fw买个可乐} / {@code fw买2个可乐} / {@code fw可乐-2} → {@link QqCommand.Kind#PRODUCT_ORDER}</li>
 *   <li>{@code fw可乐5} → {@link QqCommand.Kind#STOCK_ADJUST}（调整库存，仅管理员 ——
 *       身份不在这里判，解析器不认人）</li>
 *   <li>{@code fw拍拍机 1 号维护中} → {@link QqCommand.Kind#DEVICE_STATUS}
 *       （调整机台状况，仅管理员；状况词就是 {@code DeviceStatus} 的三个中文名）</li>
 *   <li>带了前缀但认不出 → {@link QqCommand.Kind#UNKNOWN_COMMAND}</li>
 *   <li><b>没有前缀 → 一律 {@link QqCommand.Kind#IGNORE}</b>。
 *       例外只有 {@code kklm} 与 {@code ping} 两个英文词，见 {@link #PREFIX_FREE_ALIASES}</li>
 * </ul>
 *
 * <p>⚠️ <b>商品下单也是封闭的</b>（2026-10-04 改）：它只认<b>带关键词</b>
 * （{@code 买…个…}）或<b>带横杠</b>（{@code 名-数量}）这两种写法，
 * 而两者都显式写着数量。早先设计成「{@code fw可乐} 就买一件」，
 * 那意味着<b>任何带前缀的未知文本都得当商品名查一遍</b> ——
 * 于是打错的指令会得到一句「没有叫「在店铺」的商品」，白白吓人一跳。
 * 名字存不存在<b>解析器不管</b>（那要查库，会破坏「纯静态」这条性质），
 * 由 {@code QqWriteCommandService} 去问商品模块。
 *
 * <p>⚠️ <b>「整条精确匹配」是这里的核心决定</b>：若改成「包含关键词」，
 * 那么群里一句「我等会儿到店里」就会触发一次在店名册 —— 而群里天天有人这么说。
 * 精确匹配的代价是用户必须把指令发得干净，这个代价由 {@code fw帮助} 兜住。
 *
 * <p>⚠️ <b>「必须带前缀」是加写指令时立下的规矩</b>（2026-10-04）：
 * 在此之前 {@code /} 可有可无，理由是「用户第一次多半不知道要加」。
 * 但 {@code fw开门} 会<b>真的建一笔订单并开始计费</b> —— 群里有人喊一声「开门」
 * 若被认成指令，那是一笔白扣的钱，而且当场没人会发现哪里不对。
 * 代价是用户必须知道要加前缀，由 {@code fw帮助} 与 {@code UNKNOWN_COMMAND} 的提示兜住。
 *
 * <p><b>带参数的指令有两条</b>：验证码 {@code fw验证 123456} 与取消
 * {@code fw取消 &lt;单号&gt;}。验证码同样要求前缀，且<b>不再接受「纯 6 位数字」</b>
 * （早先是那样的）—— 免得群友随口发的一串数字去撞别人的验证，
 * 也让注册页能把整条指令做成一个复制按钮。取消那条的单号要先过
 * {@link #isOrderNoLike} 的形状校验（够长 + 全 ASCII 字母数字）。
 */
public final class QqCommandParser {

    /**
     * 消息开头的 CQ 码（可能连续多段，如先 @机器人 再发一个表情）。
     *
     * <p>只在<b>开头</b>剥，不剥中间与结尾 —— 消息正文里出现 {@code [CQ:...]} 字样
     * 属于用户自己的内容，动了它就等于篡改消息。
     */
    private static final Pattern LEADING_CQ_CODE = Pattern.compile("^(\\[CQ:[^]]*])+");

    /**
     * 查在店名册。
     *
     * <p>去掉前导 {@code fw} 后再比较，所以集合里都是不带前缀的形式。
     * 「看看里面」是口语化的那一个 —— 群里问「里面有人吗」比「在店」自然，
     * 而它**依然走精确匹配**，所以「看看里面那个人是谁」这种闲聊不会误触发。
     *
     * <p>{@code kklm} 是「看看里面」的拼音首字母（2026-10-04 由用户要求加）——
     * 全在英文键盘上，不必切中文输入法就能发；它<b>免前缀</b>
     * （见 {@link #PREFIX_FREE_ALIASES}），{@code fw帮助} 里也给了这个免前缀写法。
     * （同日删掉了 {@code rs}：它不缩写任何中文，记不住也用不上。）
     */
    private static final Set<String> INSTORE_ALIASES =
            Set.of("在店", "instore", "看看里面", "kklm");

    /**
     * 免前缀的别名 → 对应的指令（2026-10-10 由用户要求）。
     *
     * <p>它们豁免的是那一次前缀体检：两个都是<b>刻意的英文输入</b>，
     * 不像谁在群里随口说的话，而且全在英文键盘上。
     *
     * <ul>
     *   <li>{@code kklm} → 在店名册：店里想瞄一眼「现在有谁在」的人，
     *       不必切中文输入法就能发</li>
     *   <li>{@code ping} → 连通性自检：网络里通行的自检词，
     *       单独发一个 {@code ping} 就是在问「机器人还活着吗」，
     *       与 {@code fw在吗} 同一个语义</li>
     * </ul>
     *
     * <p>⚠️ <b>豁免只给这两个英文词，中文别名与其余英文别名都必须带 {@code fw}</b>：
     * 「在店」「在吗」「看看里面」在群聊里是常见词 —— 何况开头一个 @ 会被剥掉，
     * 「@某人 在吗」剥完就是「在吗」—— 放了它们等于把「指令是封闭集合」
     * 那条决定拆掉一半，而那条决定的收益是「任何一次误判都可能真的建单计费」
     * （见类注释）。{@code kklm} 与 {@code ping} 没有这个风险，
     * 它们是刻意的输入，不是谁的口头话。
     *
     * <p>仍走<b>整条精确匹配</b>：{@code kklm一下}、{@code ping一下} 都不是指令，
     * 照旧当闲聊静默。
     *
     * <p>⚠️ <b>它是 Map 而不是集合</b>：两个词指向不同的指令，
     * 集合表达不了这个对应关系。{@link QqCommand} 是不可变的 record，
     * 所以每条指令只造一个实例、反复复用。
     */
    private static final Map<String, QqCommand> PREFIX_FREE_ALIASES = Map.of(
            "kklm", QqCommand.instore(),
            "ping", QqCommand.ping());

    /** 同上 */
    private static final Set<String> HELP_ALIASES = Set.of("帮助", "help", "?");

    /**
     * 连通性自检。
     *
     * <p>中文别名给「在吗」—— 群里问「在吗」而机器人回一句「pong」，
     * 比让它对这个最常见的中文招呼保持沉默要自然得多。
     *
     * <p>⚠️ 但「在吗」<b>必须带前缀</b>：它在群里是再常见不过的招呼，
     * 而且开头一个 @ 会被剥掉（「@某人 在吗」剥完就是「在吗」）——
     * 免前缀的只有英文的 {@code ping}，见 {@link #PREFIX_FREE_ALIASES}。
     */
    private static final Set<String> PING_ALIASES = Set.of("ping", "在吗");

    /**
     * 开门计时（写指令）。
     *
     * <p>⚠️ 它的别名格外危险：{@code 开门} 这两个字在群聊里是常见词。
     * 全部靠「必须带前缀 + 整条精确匹配」两道挡住 —— 「我马上开门」不会触发，
     * 因为它既没有前缀、整条也不相等。改这里之前先想清楚这两道是怎么起作用的。
     */
    private static final Set<String> OPEN_ALIASES = Set.of("开门", "open");

    /**
     * 结账（写指令）。
     *
     * <p>⚠️ <b>「买单」不是别名</b>（2026-10-04 由用户要求删掉）。它与商品下单的
     * 「买…个…」共用「买」字开头，两条语法混在一起容易被误用 ——
     * 看到 {@code fw买单} 能结账，就会以为 {@code fw买可乐} 也能下单。
     * 删掉之后 {@code fw买单} 落到「没认出这条指令」，而那是个正确的反馈：
     * 它确实不是一条指令了。
     *
     * <p>英文别名原来给的是 {@code settle}，2026-10-10 由用户要求换成 {@code pay} ——
     * 少按三个键。⚠️ <b>是换掉而不是新增</b>：{@code fwsettle} 不再是一条指令，
     * 发它会落到「没认出这条指令」，而那正是它该得的反馈。
     *
     * <p>顺带一提，别名必须用<b>同一个 lower</b> 比较（见 {@code parse} 里那段说明）。
     */
    private static final Set<String> SETTLE_ALIASES = Set.of("结账", "pay");

    /** 查近期包场时间表 */
    private static final Set<String> BOOKING_ALIASES = Set.of("包场", "包场时间表", "booking");

    /**
     * 查门店公告。
     *
     * <p>别名给 {@code notice}（英文）与 {@code gg}（「公告」的拼音首字母，
     * 与 {@code kklm} 同一套构词法）。
     *
     * <p>⚠️ <b>{@code gg} 必须带前缀</b>：它在游戏群里是常见词（打完一局就发
     * 「gg」），裸发绝不能触发 —— 免前缀的只有 {@code kklm} 与 {@code ping}
     * 两个刻意的词，见 {@link #PREFIX_FREE_ALIASES}。
     */
    private static final Set<String> NOTICE_ALIASES = Set.of("公告", "gg", "notice");

    /** 查自己的资料与消费 */
    private static final Set<String> ME_ALIASES = Set.of("看看自己", "我的", "me");

    /**
     * 看当前这一单。
     *
     * <p>⚠️ <b>别名不给「订单」</b>：群里说「订单」可能指任何一笔，
     * 而这条指令只讲<b>正在计时的这一单</b> —— 名字里带「当前」才不会误解。
     * {@code now} 是给懒得打中文的人留的。
     */
    private static final Set<String> CURRENT_ORDER_ALIASES = Set.of("now", "当前订单");

    /**
     * 查本人未付款的单。
     *
     * <p>别名给「欠费」—— 群里催自己账的说法就是它；{@code unpaid} 照顾英文派。
     * ⚠️ <b>「账单」刻意不给</b>：它更像在说「历史订单」，
     * 而这条只讲<b>还没付钱的那几笔</b>。
     */
    private static final Set<String> UNPAID_ALIASES = Set.of("未付款", "欠费", "unpaid");

    /** 查门店营业状态 */
    private static final Set<String> STORE_STATUS_ALIASES = Set.of("营业", "营业吗", "status");

    /**
     * 查计费规则摘要。
     *
     * <p>⚠️ <b>刻意不加「多少钱」</b>：它在群里是句再普通不过的闲聊，
     * 而机器人答一句价格表不会报任何错，只会显得乱插嘴 ——
     * 正是这套设计最想避免的那类失败。
     */
    private static final Set<String> PRICE_ALIASES = Set.of("价格", "价目", "price", "pricing");

    /**
     * 查月卡说明。
     *
     * <p>别名给 {@code pass}（月卡在英文里就是 monthly pass）与 {@code card} ——
     * 与 {@code me} / {@code price} 这些英文别名同一风格。
     * ⚠️ 仍靠「必须带前缀 + 整条精确匹配」两道挡住闲聊：
     * 「我先 pass 这局」既没有前缀、整条也不相等，不会触发。
     */
    private static final Set<String> CARD_ALIASES = Set.of("月卡", "pass", "card");

    /**
     * 查商城商品与价格。
     *
     * <p>「菜单」是店里更常说的那个词（卖饮料零食的那种柜子，顾客就管它叫菜单）。
     */
    private static final Set<String> MENU_ALIASES = Set.of("菜单", "menu");

    /**
     * 查店内设施与状况。
     *
     * <p>别名给 {@code device} —— 与 {@code menu} / {@code price} 这些英文别名同一风格。
     *
     * <p>⚠️ <b>它与「改机台状况」是两条不同的指令</b>（{@code fw拍拍机 1 号维护中}）：
     * 这一条是查询、谁都能发，那一条是写指令、仅管理员。两者语法不重叠 ——
     * 后者要求「机台名 + 状况词」结尾，而 {@code fw机台} 整条就是个别名。
     */
    private static final Set<String> DEVICE_LIST_ALIASES = Set.of("机台", "device");

    /**
     * 查网页端地址。
     *
     * <p>别名给「网址」—— 群里问的是「网址多少」，不是「web 是什么」。
     * 仍然走整条精确匹配，所以「你把网址发我一下」这种闲聊不会触发。
     */
    private static final Set<String> WEB_ALIASES = Set.of("web", "网址");

    /**
     * 6 位验证码。
     *
     * <p>前导零要保留，所以按<b>字符串</b>匹配而不是转成数字（{@code 042317} 转数字会变成 5 位的 42317）。
     * 它只被 {@link #extractVerifyCode} 用 —— 单发一串数字不再算验证码。
     */
    private static final Pattern VERIFY_CODE_PATTERN = Pattern.compile("^\\d{6}$");

    /**
     * 指令前缀 {@code fw}。它是「我在跟机器人说话」的唯一标志，见 {@link QqCommand.Kind}。
     *
     * <p>⚠️ <b>2026-10-09 起它是指令的唯一入口</b>：此前 {@code /} 与 {@code fw} 等价，
     * 按用户要求改成只认 {@code fw} —— {@code fw在店} 自此与「没前缀」走同一条路
     * （当闲聊，静默）。大小写不敏感（{@code FW在店} 也认），后面有没有空格都行。
     */
    private static final String FW_PREFIX = "fw";

    /**
     * 数量段最多几位。超过 3 位的一律不当数量（「可乐-2024」更可能是名字）。
     *
     * <p>它<b>不是</b>「单次最多买几件」那条业务上限（那是 99，归商品模块管）——
     * 这里管的是「这串数字到底是不是数量」，所以放得比 99 宽。
     */
    private static final int MAX_QUANTITY_DIGITS = 3;

    /**
     * 商品下单的关键词：「买个可乐」「买2个可乐」。
     *
     * <p>它让这条指令重新成为<b>封闭集合</b> —— 认不出的文本仍然回
     * 「没认出这条指令」，而不会像早先那样被当成商品名去查一遍。
     */
    private static final String BUY_KEYWORD = "买";

    /** 量词，与 {@link #BUY_KEYWORD} 一起组成「买N个X」。它本身也说明数量是 1 */
    private static final String BUY_MEASURE_WORD_TEXT = "个";

    /** 量词的单字符形式，供逐字符查找用 */
    private static final char BUY_MEASURE_WORD = '个';

    /**
     * 中文数词。
     *
     * <p>群里最自然的说法是「买两个可乐」，只认阿拉伯数字会让他白白收到一句
     * 「没认出这条指令」—— 而这次改版要消灭的正是这类无谓的报错。
     * <b>只收个位数与十</b>：二十、三十这种两位数不支持（群里买那么多不现实），
     * 要买那么多写阿拉伯数字即可。
     */
    private static final Map<Character, Integer> CHINESE_DIGITS = Map.ofEntries(
            Map.entry('一', 1), Map.entry('两', 2), Map.entry('二', 2), Map.entry('三', 3),
            Map.entry('四', 4), Map.entry('五', 5), Map.entry('六', 6), Map.entry('七', 7),
            Map.entry('八', 8), Map.entry('九', 9), Map.entry('十', 10));

    /**
     * 验证码的关键词。它是唯一带参数的指令：{@code fw验证 123456}。
     *
     * <p>要求带关键词是为了<b>让这条消息自解释</b>（光 {@code fw123456} 看不出在干什么），
     * 也让注册页能把整条指令原样做成一个复制按钮 —— 用户不必理解它，复制粘贴即可。
     */
    private static final Set<String> VERIFY_KEYWORDS = Set.of("验证", "verify");

    /**
     * 取消未付款单的关键词（第二条带参数的指令）：{@code fw取消 OD2026…}。
     *
     * <p>与验证码同构：关键词后面跟参数（这里是单号）。要求带关键词是为了
     * <b>让指令自解释</b>，也让「fw取消一下」这类误跟中文的写法
     * 落回「没认出这条指令」，而不是拿「一下」去查一遍单号。
     */
    private static final Set<String> CANCEL_KEYWORDS = Set.of("取消", "cancel");

    /**
     * 不能当商品名的「保留字」：所有指令的别名与关键词。
     *
     * <p>它是 {@code fw可乐5}（调整库存）识别时的第二道守卫 —— 名字命中这里就
     * 判定为「不像商品名」，整条落回 {@link QqCommand.Kind#UNKNOWN_COMMAND}。
     * 理由见 {@link #looksLikeProductName}：打错的指令不该被错怪成商品。
     *
     * <p>⚠️ <b>由各别名表拼出来，不手写第二份</b>：手抄一遍的话，将来加别名时
     * 漏掉的正是新加的那几个，而失败方式是「那条指令打错时被当成商品名查」——
     * 不报错、只是答非所问。英文别名（{@code help} / {@code ping} / {@code pass} …）
     * 必须一并收进来，只挡中文等于豁口开在另一半。
     *
     * <p>它的初始化<b>必须排在所有别名表之后</b>（静态字段按声明顺序初始化，
     * 放前面会读到还没建好的空集合）。
     */
    private static final Set<String> RESERVED_NAMES = buildReservedNames();

    /**
     * 把各指令的别名与关键词拼成一个保留字集合。
     *
     * @return 不可变的保留字集合
     */
    private static Set<String> buildReservedNames() {
        Set<String> names = new HashSet<>();
        names.addAll(INSTORE_ALIASES);
        names.addAll(HELP_ALIASES);
        names.addAll(PING_ALIASES);
        names.addAll(OPEN_ALIASES);
        names.addAll(SETTLE_ALIASES);
        names.addAll(BOOKING_ALIASES);
        names.addAll(NOTICE_ALIASES);
        names.addAll(ME_ALIASES);
        names.addAll(CURRENT_ORDER_ALIASES);
        names.addAll(UNPAID_ALIASES);
        names.addAll(STORE_STATUS_ALIASES);
        names.addAll(PRICE_ALIASES);
        names.addAll(CARD_ALIASES);
        names.addAll(MENU_ALIASES);
        names.addAll(DEVICE_LIST_ALIASES);
        names.addAll(WEB_ALIASES);
        names.addAll(VERIFY_KEYWORDS);
        names.addAll(CANCEL_KEYWORDS);
        return Set.copyOf(names);
    }

    /**
     * 全角空格。⚠️ <b>这不是洁癖，是中文输入法下的必然产物</b> ——
     * 用户按中文标点打出来的就是它，而 {@code String.trim()} 不认为它是空白。
     * 不归一化的话，他发 {@code fw　在店}（中间是全角空格）会得不到任何回复
     * （被当成闲聊静默），而群里没有任何报错可供排查，他只会以为机器人坏了。
     * 这类「静默不工作」正是本项目最想消灭的东西。
     */
    private static final char FULL_WIDTH_SPACE = '　';

    /**
     * 工具类，不实例化。
     */
    private QqCommandParser() {
    }

    /**
     * 解析一条群消息。
     *
     * @param rawMessage OneBot 推来的原始消息文本，可为 null
     * @return 解析结果，永不返回 null —— 认不出来时返回
     *         {@link QqCommand.Kind#IGNORE} 或 {@link QqCommand.Kind#UNKNOWN_COMMAND}
     */
    public static QqCommand parse(String rawMessage) {
        if (rawMessage == null) {
            return QqCommand.ignore();
        }
        String text = normalize(rawMessage);
        if (text.isEmpty()) {
            return QqCommand.ignore();
        }

        // ⓪ 免前缀的别名（kklm 与 ping）—— 它们不必先说「我在跟机器人说话」，
        //    理由与边界见 PREFIX_FREE_ALIASES。放在剥前缀之前：剥不到前缀就走人了
        QqCommand prefixFree = PREFIX_FREE_ALIASES.get(text.toLowerCase(Locale.ROOT));
        if (prefixFree != null) {
            return prefixFree;
        }

        // ① 剥前缀（fw）。没有前缀的一律当闲聊 —— 见类注释里那条规矩。
        //    剥掉的只是【一个】前缀，"fwfw在店" 仍然认不出
        String body = stripPrefix(text);
        if (body == null) {
            return QqCommand.ignore();
        }
        body = body.trim();
        if (body.isEmpty()) {
            // 只发了一个 fw：他确实在跟机器人说话，只是没说完整
            return QqCommand.unknownCommand();
        }

        // ② 两条带参数的指令先试：fw验证 123456 与 fw取消 <单号>。
        //    它们排在别名区之前，因为「取消PD2026…」整条不是任何别名
        String code = extractVerifyCode(body);
        if (code != null) {
            return QqCommand.verifyCode(code);
        }
        QqCommand cancel = extractCancelOrderNo(body);
        if (cancel != null) {
            return cancel;
        }

        // ③ 其余整条精确匹配，统一转小写再比（英文别名大小写不敏感）。
        //
        // ⚠️ 所有别名表必须用【同一个】lower —— 漏掉其中一处的话，那个指令的大写写法
        // 会落到「像指令但不认识」那一支，用户收到一句「没认出这条指令」而不知所措。
        // 这个 bug 正是被 QqCommandParserTests 逮到的（`fwINSTORE` 当时不认），
        // 而它在手工测试里几乎不可能被发现（没人会特意发大写）。
        //
        // 用 Locale.ROOT 而不是默认 locale：土耳其语环境下 "I".toLowerCase() 会得到
        // 无点的 "ı"，让任何含大写 I 的别名静默失配。中文别名不受影响（汉字没有大小写）。
        String lower = body.toLowerCase(Locale.ROOT);
        if (INSTORE_ALIASES.contains(lower)) {
            return QqCommand.instore();
        }
        if (HELP_ALIASES.contains(lower)) {
            return QqCommand.help();
        }
        if (PING_ALIASES.contains(lower)) {
            return QqCommand.ping();
        }
        if (OPEN_ALIASES.contains(lower)) {
            return QqCommand.openDoor();
        }
        if (SETTLE_ALIASES.contains(lower)) {
            return QqCommand.settle();
        }
        if (BOOKING_ALIASES.contains(lower)) {
            return QqCommand.bookingSchedule();
        }
        if (NOTICE_ALIASES.contains(lower)) {
            return QqCommand.notices();
        }
        if (ME_ALIASES.contains(lower)) {
            return QqCommand.me();
        }
        if (CURRENT_ORDER_ALIASES.contains(lower)) {
            return QqCommand.currentOrder();
        }
        if (UNPAID_ALIASES.contains(lower)) {
            return QqCommand.unpaidBills();
        }
        if (STORE_STATUS_ALIASES.contains(lower)) {
            return QqCommand.storeStatus();
        }
        if (PRICE_ALIASES.contains(lower)) {
            return QqCommand.price();
        }
        if (CARD_ALIASES.contains(lower)) {
            return QqCommand.cardTypes();
        }
        if (MENU_ALIASES.contains(lower)) {
            return QqCommand.productMenu();
        }
        if (DEVICE_LIST_ALIASES.contains(lower)) {
            return QqCommand.deviceList();
        }
        if (WEB_ALIASES.contains(lower)) {
            return QqCommand.web();
        }

        // ④ 都不是别名 —— 那它可能是商品下单
        QqCommand product = parseProductOrder(body);
        if (product != null) {
            return product;
        }

        // ⑤ 商品下单没认出来的，可能是在调整库存（fw可乐5）。
        // ⚠️ 【顺序不能反】：parseDashForm 认的是「名-数量」，本条认的是「名+数字」，
        // 两者对 fw可乐-2 都能切出一组结果 —— 反过来的话，「买 2 件可乐」
        // 会静默变成「把『可乐-』的库存设成 2」，且不报任何错
        QqCommand stock = parseStockAdjust(body);
        if (stock != null) {
            return stock;
        }

        // ⑥ 库存也没认出来的，可能是在改机台状况（fw拍拍机 1 号维护中）
        QqCommand device = parseDeviceStatus(body);
        if (device != null) {
            return device;
        }

        // 走到这里说明带了前缀但没认出来 —— 给个提示，别让用户以为机器人坏了。
        // ⚠️ 商品那条路【只认带关键词或带横杠的写法】，所以拼错的指令
        //（fw在店铺、fw开门吧）仍然落在这里，不会被错怪成「没有这个商品」
        return QqCommand.unknownCommand();
    }

    /**
     * 解析商品下单。两种写法，都<b>显式带数量</b>：
     *
     * <pre>
     *   买个可乐        → 1 件（量词「个」本身就说明了数量）
     *   买2个可乐       → 2 件
     *   买两个可乐      → 2 件（中文数词也认，见 {@link #parseQuantity}）
     *   可乐-2          → 2 件（简写变体，横杠后面直接跟数量）
     * </pre>
     *
     * <p>⚠️ <b>「只写商品名」这条路刻意没有</b>（2026-10-04 与用户确认）：
     * 早先设计成「{@code fw可乐} 就买一件」，但那意味着<b>任何带前缀的未知文本
     * 都得当成商品名去查一遍</b> —— 于是「{@code fw在店铺}」这种打错的指令
     * 会得到一句「没有叫「在店铺」的商品」，而群里天天有人在打字。
     * 改成关键词开头之后，指令重新是个<b>封闭集合</b>：
     * 不匹配就是「没认出这条指令」，一个字都不会被错怪成商品。
     *
     * <p>⚠️ <b>数量不在这里做范围校验</b>：「几件算合理」是业务规则（1~99，
     * 与网页端同一个上限），归 Service 判 —— 放在这里的话，那句
     * 「单次最多买 99 件」就没有地方可写了，用户只会收到一句「没认出这条指令」。
     *
     * @param body 剥掉前缀、去掉首尾空白后的正文（非空）
     * @return 商品下单指令；不像商品下单时返回 null
     */
    private static QqCommand parseProductOrder(String body) {
        QqCommand byKeyword = parseBuyKeyword(body);
        return byKeyword != null ? byKeyword : parseDashForm(body);
    }

    /**
     * 解析「买…个…」那种写法。
     *
     * @param body 正文
     * @return 指令；不是这种写法时返回 null
     */
    private static QqCommand parseBuyKeyword(String body) {
        if (!body.startsWith(BUY_KEYWORD) || body.length() <= BUY_KEYWORD.length()) {
            return null;
        }
        String rest = body.substring(BUY_KEYWORD.length()).trim();

        // ① 买个X —— 「个」紧跟其后，数量即为 1
        if (rest.startsWith(BUY_MEASURE_WORD_TEXT)) {
            String name = rest.substring(BUY_MEASURE_WORD_TEXT.length()).trim();
            return name.isEmpty() ? null : QqCommand.productOrder(name, null);
        }

        // ② 买N个X —— 量词在中间。⚠️ 取【第一个】「个」而不是最后一个：
        //    商品名里出现「个」很常见（「个人杯」），取最后一个会把名字切坏
        int measure = rest.indexOf(BUY_MEASURE_WORD);
        if (measure <= 0) {
            // 没有量词（如「fw买可乐」）—— 不认。宁可回一句「没认出」，
            // 也不要在没有数量说明的情况下替用户决定买几件
            return null;
        }
        Integer quantity = parseQuantity(rest.substring(0, measure));
        String name = rest.substring(measure + BUY_MEASURE_WORD_TEXT.length()).trim();
        return quantity == null || name.isEmpty() ? null
                : QqCommand.productOrder(name, quantity);
    }

    /**
     * 解析「商品名-数量」这种简写。
     *
     * <p><b>规则必须确定</b>（同一个输入两次解析要得到同一个结论）：
     * <ol>
     *   <li>在<b>最后一个</b>分隔符处切。名字里带横杠也不怕：
     *       {@code 冰-红茶-2} → 名字「冰-红茶」、2 件</li>
     *   <li>右边是纯数字且<b>不超过 3 位</b> → 那是数量；否则整条都不是这种写法
     *       （4 位以上更可能是名字的一部分，如「可乐-2024」）</li>
     *   <li>左边为空（{@code fw-2}）→ 也不是这种写法</li>
     * </ol>
     *
     * <p>⚠️ <b>横杠是「这条消息在下单」的信号</b>：正是它把商品这条开放的路
     * 收回到「封闭集合」里 —— 不带横杠的未知文本一律走「没认出这条指令」，
     * 不会被拿去查商品。改这里之前先想清楚这一条。
     *
     * @param body 正文
     * @return 指令；不像这种写法时返回 null
     */
    private static QqCommand parseDashForm(String body) {
        int cut = lastSeparatorIndex(body);
        if (cut <= 0) {
            return null;
        }
        String name = body.substring(0, cut).trim();
        Integer quantity = parseQuantity(body.substring(cut + 1));
        return quantity == null || name.isEmpty()
                ? null : QqCommand.productOrder(name, quantity);
    }

    /**
     * 解析「调整库存」写法：{@code fw可乐5} —— 名字后面<b>直接跟数字</b>，
     * 把该商品的库存<b>设成</b>这个数（不是增减量）。
     *
     * <pre>
     *   可乐5            → 把「可乐」的库存设为 5
     *   王老吉 250ml3    → 名字里的空格与数字都原样保留
     *   冰-红茶2         → 名字里带横杠也认（商品下单那条先试过，右边不是纯数字故落空）
     * </pre>
     *
     * <p><b>规则（与商品下单同理，必须确定 —— 同一输入两次解析要得到同一个结论）</b>：
     * <ol>
     *   <li>从末尾往前取连续的数字（含全角，与商品下单同一套判定），长度
     *       1~{@value #MAX_QUANTITY_DIGITS} 位 —— 更长的数字段不算数量，
     *       <b>整条拒绝而不是截断</b>（{@code 可乐2024} 更可能是名字；截断的话
     *       {@code fw验证 12345} 会被吞成一条改库存指令）</li>
     *   <li>剩下的部分 trim 后就是商品名，交给 {@link #looksLikeProductName}
     *       过两道守卫（至少一个字母数字、不能是别的指令的别名）</li>
     * </ol>
     *
     * <p>⚠️ <b>刻意不收中文数词</b>（商品下单那条收）：这是设一个具体数值、
     * 不是口语下单；而收了的话，叫「可乐五」的商品在不带数量时会被误切。
     *
     * <p>⚠️ <b>调用顺序必须在 {@link #parseProductOrder} 之后</b>，
     * 否则 {@code fw可乐-2}（买 2 件）会被切成语义相反的「名字『可乐-』、库存 2」。
     *
     * <p>⚠️ <b>两条已知歧义，都不报错、只会做错事</b>，靠说明与文案兜住：
     * <ul>
     *   <li><b>名字以数字结尾的商品改不了库存</b>：商品「可乐2」要改成 5 件，
     *       发 {@code fw可乐25} 会被切成「名字『可乐』、库存 25」——
     *       店里若真有叫「可乐」的另一件，改的就是它。这类商品请走网页端</li>
     *   <li><b>带横杠的名字永远走商品下单</b>：{@code fw冰-红茶2} 是买 2 件。
     *       横杠本身就是「我在下单」的信号（见 {@link #parseDashForm}）</li>
     * </ul>
     *
     * <p>数量不做范围校验（与商品下单一致）：「库存上限多少算合理」是业务规则，
     * 归商品模块判 —— 放在这里的话，负数与超大值的提示就没有地方可写了。
     *
     * @param body 剥掉前缀、去掉首尾空白后的正文（非空）
     * @return 调整库存指令；不像这种写法时返回 null
     */
    private static QqCommand parseStockAdjust(String body) {
        int cut = body.length();
        // ⚠️ 用 Character.isDigit 而不是「半角 0~9」：全角数字在中文输入法下是常态，
        // 而商品下单那条（parseQuantity）认它 —— 两边不一致的表现是
        // 「fw可乐－２ 能买、fw可乐２ 改不了库存」，同一个数字两种结果，最难查
        while (cut > 0 && Character.isDigit(body.charAt(cut - 1))) {
            cut--;
        }
        int digits = body.length() - cut;
        if (digits == 0 || digits > MAX_QUANTITY_DIGITS) {
            return null;
        }
        String name = body.substring(0, cut).trim();
        if (!looksLikeEntityName(name)) {
            return null;
        }
        return QqCommand.stockAdjust(name, Integer.valueOf(body.substring(cut)));
    }

    /**
     * 解析「调整机台状况」写法：{@code fw拍拍机 1 号维护中} —— 名字后面直接跟中文状况词。
     *
     * <p><b>状况词的清单就是 {@link DeviceStatus} 的三个中文名，不在这里另抄一份</b>：
     * 将来枚举里加一态，指令自动跟着认；手抄一份的话，漏改的表现是
     * 「那条新状况在群里永远发不出去」，而群里不会有任何报错。
     *
     * <p><b>规则</b>：
     * <ol>
     *   <li>整条以某个状况词的中文名<b>结尾</b>，且前面还有名字
     *       （{@code fw维护中} 不算 —— 那是名字为空）</li>
     *   <li>名字过 {@link #looksLikeEntityName} 那两道守卫 ——
     *       {@code fw在店维护中} 这类打错的指令仍然落回「没认出这条指令」</li>
     * </ol>
     *
     * <p>⚠️ <b>调用顺序排在商品两条与改库存之后</b>：本条的触发条件是
     * 「以状况词结尾」，与「名字 + 数字」那几条语法不重叠，顺序其实无碍；
     * 排在后面只是让「先商品、后机台」这条读起来直白。
     *
     * <p>⚠️ 与商品那条同一条取舍：<b>名字存不存在、是否同名多台，解析器一概不管</b>
     * （那要查库，会破坏「纯静态」这条性质），由 {@code QqWriteCommandService}
     * 去问设备模块。
     *
     * @param body 剥掉前缀、去掉首尾空白后的正文（非空）
     * @return 调整机台状况指令；不像这种写法时返回 null
     */
    private static QqCommand parseDeviceStatus(String body) {
        for (DeviceStatus status : DeviceStatus.values()) {
            String label = status.getLabel();
            if (body.length() <= label.length() || !body.endsWith(label)) {
                continue;
            }
            String name = body.substring(0, body.length() - label.length()).trim();
            if (!looksLikeEntityName(name)) {
                return null;
            }
            return QqCommand.deviceStatus(name, status.name());
        }
        return null;
    }

    /**
     * 这段文本像不像一个「群里能点名的东西」的名字（商品名，或机台名）。
     *
     * <p>两道守卫，都是为了守住「指令是封闭集合」那条决定（2026-10-04 立）：
     * 改库存靠「名字 + 数字」识别、改机台状况靠「名字 + 状况词」识别，
     * 若不设防，<b>任何带前缀的未知文本</b>都会被拿去查一次 ——
     * 打错的指令会得到一句「没有叫「在店2」的商品」，而不是本该给的「没认出这条指令」。
     *
     * <ol>
     *   <li><b>至少含一个字母或数字</b>：{@code fw-2}、{@code fw--2}、{@code fw。。2}
     *       切出来的「名字」只剩标点，一律不认（前者正是现有测试钉着的 UNKNOWN）</li>
     *   <li><b>不能是别的指令的别名</b>：{@code fw在店2}、{@code fw开门5}、
     *       {@code fwhelp1}、{@code fw验证 123}、{@code fw在店维护中} 全部还原成
     *       「没认出这条指令」——打错一条指令，反馈就该是「这条不认识」，
     *       而不是「没有这个商品 / 没有这台机台」</li>
     *   <li><b>不以「买」开头</b>：{@code fw买可乐5} 是打错的下单写法
     *       （正确的是 {@code fw买5个可乐}），同样该回「没认出这条指令」</li>
     * </ol>
     *
     * <p>⚠️ <b>代价要如实说</b>：真有商品叫「菜单」「开门」这类名字时，
     * 群里这两条指令都点不到它（网页端不受影响）。这个取舍是刻意的 ——
     * 名字撞上指令别名的概率，远低于用户把指令打错的概率。
     *
     * @param name 切掉尾部数字（或状况词）并 trim 之后的候选名字
     * @return 看来像个能被点名的名字返回 true
     */
    private static boolean looksLikeEntityName(String name) {
        if (name.isEmpty()) {
            return false;
        }
        boolean hasLetterOrDigit = false;
        for (int i = 0; i < name.length(); i++) {
            if (Character.isLetterOrDigit(name.charAt(i))) {
                hasLetterOrDigit = true;
                break;
            }
        }
        if (!hasLetterOrDigit) {
            return false;
        }
        // 「买…」是下单语法的领地：fw买可乐5 这种打错的下单，该回「没认出这条指令」，
        // 而不是一句「没有叫「买可乐」的商品」——后者会让人以为店里真有这么个东西
        if (name.startsWith(BUY_KEYWORD)) {
            return false;
        }
        // 与别名表同一套小写（Locale.ROOT）—— 两个大小写不同的比较，
        // 漏掉的那一半就是「fwMENU2 能过、fwmenu2 被挡」这种半边防守
        return !RESERVED_NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * 找最后一个数量分隔符的位置。
     *
     * @param body 正文
     * @return 下标；一个都没有时返回 -1
     */
    private static int lastSeparatorIndex(String body) {
        for (int i = body.length() - 1; i >= 0; i--) {
            if (isQuantitySeparator(body.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 是不是数量分隔符。
     *
     * <p>除半角 {@code -} 外还收几个中文输入法下容易打出来的：全角连字符
     * {@code －}、短破折号 {@code –}、破折号 {@code —} 与减号 {@code −}。
     * 收了它们不必担心误伤 —— 右边必须是纯数字才算数量（见 {@link #parseQuantity}），
     * 所以「王老吉—250ml」不会被切成数量。
     *
     * @param c 待判字符
     * @return 是分隔符返回 true
     */
    private static boolean isQuantitySeparator(char c) {
        return c == '-'
                || c == '－'   // － 全角连字符
                || c == '–'   // – 短破折号
                || c == '—'   // — 破折号
                || c == '−';  // − 减号
    }

    /**
     * 把一段文本解析成数量。
     *
     * <p>认两种写法：<b>阿拉伯数字</b>（最多 3 位）与<b>中文数词</b>（一~十、两）。
     * 收中文数词是因为「买两个可乐」才是群里最自然的说法，只认阿拉伯数字的话
     * 用户会白白收到一句「没认出这条指令」—— 而那正是这次改版想消灭的东西。
     * 两位数（二十、三十）不支持：群里买那么多不现实，写阿拉伯数字即可。
     *
     * <p><b>不在这里判范围</b>：0 与 100 都算解析成功，由 Service 给出
     * 「至少 1 件」「最多 99 件」的不同提示。
     *
     * @param text 待解析的文本（调用方已去空白）
     * @return 数量；不像数量时返回 null
     */
    private static Integer parseQuantity(String text) {
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() == 1) {
            Integer chinese = CHINESE_DIGITS.get(text.charAt(0));
            if (chinese != null) {
                return chinese;
            }
        }
        if (text.length() > MAX_QUANTITY_DIGITS) {
            return null;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return null;
            }
        }
        return Integer.valueOf(text);
    }

    /**
     * 剥掉前缀，返回正文。
     *
     * <p>前缀只有 {@code fw} 一种（大小写不敏感，后面可有空格）。
     *
     * @param text 归一化后的消息文本（非空）
     * @return 剥掉前缀后的正文（可能为空串）；<b>没有前缀时返回 null</b> ——
     *         调用方据此区分「闲聊」与「前缀后没写东西」两件事
     */
    private static String stripPrefix(String text) {
        if (text.length() >= FW_PREFIX.length()
                && text.regionMatches(true, 0, FW_PREFIX, 0, FW_PREFIX.length())) {
            return text.substring(FW_PREFIX.length());
        }
        return null;
    }

    /**
     * 从正文里取验证码。
     *
     * <p>接受 {@code 验证 123456} / {@code 验证123456} / {@code verify 123456}
     * 几种写法（前缀已由调用方剥掉，故这里不再判前缀）。
     *
     * @param body 剥掉前缀后的正文
     * @return 6 位验证码；不是验证码指令时返回 null
     */
    private static String extractVerifyCode(String body) {
        for (String keyword : VERIFY_KEYWORDS) {
            if (body.length() > keyword.length()
                    && body.regionMatches(true, 0, keyword, 0, keyword.length())) {
                String rest = body.substring(keyword.length()).trim();
                if (VERIFY_CODE_PATTERN.matcher(rest).matches()) {
                    return rest;
                }
            }
        }
        return null;
    }

    /**
     * 从正文里取「取消」指令的目标单号。
     *
     * <p>接受 {@code 取消 PD2026…} / {@code 取消PD2026…} / {@code cancel …}
     * 几种写法（前缀已由调用方剥掉）。单号原样带出、只做<b>大写归一</b>：
     * 库里存的是大写，用户从小写键盘敲进来也要认。
     *
     * <p><b>形状校验只有一道</b>（见 {@link #isOrderNoLike}）：够长、且全是
     * ASCII 字母数字。它挡的是「fw取消一下」「fw取消2」这类后面跟的不是单号的
     * 情形 —— 它们应当落回「没认出这条指令」，而不是拿「一下」去查一遍。
     * 至于这个单号<b>存不存在、是谁的</b>，解析器一概不管
     *（那要查库，会破坏「纯静态」这条性质），由
     * {@code QqWriteCommandService} 按前缀路由去问各模块。
     *
     * @param body 剥掉前缀后的正文
     * @return 取消指令；不是这种写法时返回 null
     */
    private static QqCommand extractCancelOrderNo(String body) {
        for (String keyword : CANCEL_KEYWORDS) {
            if (body.length() > keyword.length()
                    && body.regionMatches(true, 0, keyword, 0, keyword.length())) {
                String rest = body.substring(keyword.length()).trim();
                if (isOrderNoLike(rest)) {
                    return QqCommand.cancelOrder(rest.toUpperCase(Locale.ROOT));
                }
            }
        }
        return null;
    }

    /**
     * 这段文本像不像一个单号。
     *
     * <p>判据只有两条，都刻意放得很宽：
     * <ol>
     *   <li><b>长度 6~32</b> —— 单号是 20 位（前缀 2 + 时间戳 14 + 随机 4）。
     *       下限 6 挡「fw取消2」这类打错的指令，上限 32 挡住把一大段文本塞进来</li>
     *   <li><b>全是 ASCII 字母或数字</b> —— 中文（「fw取消一下」）、空格、
     *       标点一律不认。单号的字符集就是这两样</li>
     * </ol>
     *
     * <p>不在这里校验前缀（{@code OD}/{@code PD}/{@code MC}/{@code BK}）：
     * 那是路由的活（{@code PaymentTargetType.fromOrderNo}）；而且前缀对了、
     * 后面对不上的单号照样要走到「没找到」那句提示，两处各判一次只会让
     * 「哪种算认得出」出现两个口径。
     *
     * @param text 关键词后面 trim 过的剩余文本
     * @return 像单号返回 true
     */
    private static boolean isOrderNoLike(String text) {
        if (text.length() < 6 || text.length() > 32) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ascii = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            if (!ascii) {
                return false;
            }
        }
        return true;
    }

    /**
     * 归一化：剥掉开头的 @ 提及 → 全角空格转半角 → 去掉首尾空白。
     *
     * <p>真正的含义在后两步：全角空格在中文输入法下极常见，
     * 而 {@link String#trim()} 不认为它是空白（那个方法只处理 {@code <= U+0020} 的字符）。
     *
     * <p>⚠️ <b>2026-10-09 起不再转全角斜杠</b>：那一步原本是为了「中文输入法下打出的
     * {@code ／在店} 也能认」，而 {@code /} 已不再是前缀，转了也没有用武之地。
     *
     * @param rawMessage 原始消息
     * @return 归一化后的文本，首尾无空白
     */
    private static String normalize(String rawMessage) {
        String text = LEADING_CQ_CODE.matcher(rawMessage).replaceFirst("");
        return text.replace(FULL_WIDTH_SPACE, ' ').trim();
    }
}
