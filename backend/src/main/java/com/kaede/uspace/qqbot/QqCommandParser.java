package com.kaede.uspace.qqbot;

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
 * 再看有没有<b>前缀</b>（{@code /} 或 {@code fw}），然后<b>整条精确匹配</b>：
 *
 * <ul>
 *   <li>{@code /在店} / {@code fw在店} / {@code /instore} / {@code /rs} → {@link QqCommand.Kind#INSTORE}</li>
 *   <li>{@code /验证 123456} → {@link QqCommand.Kind#VERIFY_CODE}</li>
 *   <li>{@code /开门} / {@code /结账} / {@code /包场} / {@code /看看自己} / {@code /营业} / {@code /价格} / {@code /月卡} → 各自对应</li>
 *   <li>{@code /买个可乐} / {@code /买2个可乐} / {@code /可乐-2} → {@link QqCommand.Kind#PRODUCT_ORDER}</li>
 *   <li>带了前缀但认不出 → {@link QqCommand.Kind#UNKNOWN_COMMAND}</li>
 *   <li><b>没有前缀 → 一律 {@link QqCommand.Kind#IGNORE}</b></li>
 * </ul>
 *
 * <p>⚠️ <b>商品下单也是封闭的</b>（2026-10-04 改）：它只认<b>带关键词</b>
 * （{@code 买…个…}）或<b>带横杠</b>（{@code 名-数量}）这两种写法，
 * 而两者都显式写着数量。早先设计成「{@code /可乐} 就买一件」，
 * 那意味着<b>任何带前缀的未知文本都得当商品名查一遍</b> ——
 * 于是打错的指令会得到一句「没有叫「在店铺」的商品」，白白吓人一跳。
 * 名字存不存在<b>解析器不管</b>（那要查库，会破坏「纯静态」这条性质），
 * 由 {@code QqWriteCommandService} 去问商品模块。
 *
 * <p>⚠️ <b>「整条精确匹配」是这里的核心决定</b>：若改成「包含关键词」，
 * 那么群里一句「我等会儿到店里」就会触发一次在店名册 —— 而群里天天有人这么说。
 * 精确匹配的代价是用户必须把指令发得干净，这个代价由 {@code /帮助} 兜住。
 *
 * <p>⚠️ <b>「必须带前缀」是加写指令时立下的规矩</b>（2026-10-04）：
 * 在此之前 {@code /} 可有可无，理由是「用户第一次多半不知道要加」。
 * 但 {@code /开门} 会<b>真的建一笔订单并开始计费</b> —— 群里有人喊一声「开门」
 * 若被认成指令，那是一笔白扣的钱，而且当场没人会发现哪里不对。
 * 代价是用户必须知道要加前缀，由 {@code /帮助} 与 {@code UNKNOWN_COMMAND} 的提示兜住。
 *
 * <p>唯一带参数的是验证码：{@code /验证 123456}。它同样要求前缀，
 * 且<b>不再接受「纯 6 位数字」</b>（早先是那样的）—— 免得群友随口发的一串数字
 * 去撞别人的验证，也让注册页能把整条指令做成一个复制按钮。
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
     * <p>去掉前导 {@code /} 后再比较，所以集合里都是不带斜杠的形式。
     * 「看看里面」是口语化的那一个 —— 群里问「里面有人吗」比「在店」自然，
     * 而它**依然走精确匹配**，所以「看看里面那个人是谁」这种闲聊不会误触发。
     *
     * <p>{@code kklm} 是「看看里面」的拼音首字母（2026-10-04 由用户要求加）——
     * 全在英文键盘上，不必切中文输入法就能发。它只在解析器里认，
     * {@code /帮助} 不列（那边只给最好记的两三种写法）。
     * （同日删掉了 {@code rs}：它不缩写任何中文，记不住也用不上。）
     */
    private static final Set<String> INSTORE_ALIASES =
            Set.of("在店", "instore", "看看里面", "kklm");

    /** 同上 */
    private static final Set<String> HELP_ALIASES = Set.of("帮助", "help", "?");

    /**
     * 连通性自检。
     *
     * <p>中文别名给「在吗」—— 群里问「在吗」而机器人回一句「pong」，
     * 比让它对这个最常见的中文招呼保持沉默要自然得多。
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
     * 看到 {@code /买单} 能结账，就会以为 {@code /买可乐} 也能下单。
     * 删掉之后 {@code /买单} 落到「没认出这条指令」，而那是个正确的反馈：
     * 它确实不是一条指令了。
     *
     * <p>顺带一提，别名必须用<b>同一个 lower</b> 比较（见 {@code parse} 里那段说明）。
     */
    private static final Set<String> SETTLE_ALIASES = Set.of("结账", "settle");

    /** 查近期包场时间表 */
    private static final Set<String> BOOKING_ALIASES = Set.of("包场", "包场时间表", "booking");

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

    /** 斜杠前缀。它是「我在跟机器人说话」的标志，见 {@link QqCommand.Kind} */
    private static final char COMMAND_PREFIX = '/';

    /**
     * 文字前缀 {@code fw}，与斜杠等价（{@code fw开门} 就是 {@code /开门}）。
     *
     * <p>给中文输入法下懒得切符号的用户留的：打 {@code fw} 比打 {@code /} 顺手。
     * 大小写不敏感（{@code FW开门} 也认），后面有没有空格都行。
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
     * 验证码的关键词。它是唯一带参数的指令：{@code /验证 123456}。
     *
     * <p>要求带关键词是为了<b>让这条消息自解释</b>（光 {@code /123456} 看不出在干什么），
     * 也让注册页能把整条指令原样做成一个复制按钮 —— 用户不必理解它，复制粘贴即可。
     */
    private static final Set<String> VERIFY_KEYWORDS = Set.of("验证", "verify");

    /**
     * 全角斜杠。⚠️ <b>这不是洁癖，是中文输入法下的必然产物</b> ——
     * 用户按中文标点打出来的就是它。不归一化的话，他发 {@code ／在店}
     * 会得不到任何回复（被当成闲聊静默），而群里没有任何报错可供排查，
     * 他只会以为机器人坏了。这类「静默不工作」正是本项目最想消灭的东西。
     */
    private static final char FULL_WIDTH_SLASH = '／';

    /** 全角空格。同上，中文输入法下极常见，而 {@code String.trim()} 不认为它是空白 */
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

        // ① 剥前缀（/ 或 fw）。没有前缀的一律当闲聊 —— 见类注释里那条规矩。
        //    剥掉的只是【一个】前缀，"//在店" 与 "fwfw在店" 都仍然认不出
        String body = stripPrefix(text);
        if (body == null) {
            return QqCommand.ignore();
        }
        body = body.trim();
        if (body.isEmpty()) {
            // 只发了一个 / 或 fw：他确实在跟机器人说话，只是没说完整
            return QqCommand.unknownCommand();
        }

        // ② 唯一带参数的那条：/验证 123456
        String code = extractVerifyCode(body);
        if (code != null) {
            return QqCommand.verifyCode(code);
        }

        // ③ 其余整条精确匹配，统一转小写再比（英文别名大小写不敏感）。
        //
        // ⚠️ 所有别名表必须用【同一个】lower —— 漏掉其中一处的话，那个指令的大写写法
        // 会落到「像指令但不认识」那一支，用户收到一句「没认出这条指令」而不知所措。
        // 这个 bug 正是被 QqCommandParserTests 逮到的（`/INSTORE` 当时不认），
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
        if (ME_ALIASES.contains(lower)) {
            return QqCommand.me();
        }
        if (CURRENT_ORDER_ALIASES.contains(lower)) {
            return QqCommand.currentOrder();
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
        if (WEB_ALIASES.contains(lower)) {
            return QqCommand.web();
        }

        // ④ 都不是别名 —— 那它可能是商品下单
        QqCommand product = parseProductOrder(body);
        if (product != null) {
            return product;
        }

        // 走到这里说明带了前缀但没认出来 —— 给个提示，别让用户以为机器人坏了。
        // ⚠️ 商品那条路【只认带关键词或带横杠的写法】，所以拼错的指令
        //（/在店铺、/开门吧）仍然落在这里，不会被错怪成「没有这个商品」
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
     * 早先设计成「{@code /可乐} 就买一件」，但那意味着<b>任何带前缀的未知文本
     * 都得当成商品名去查一遍</b> —— 于是「{@code /在店铺}」这种打错的指令
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
            // 没有量词（如「/买可乐」）—— 不认。宁可回一句「没认出」，
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
     *   <li>左边为空（{@code /-2}）→ 也不是这种写法</li>
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
     * <p>两种前缀等价：{@code /} 与 {@code fw}（后者大小写不敏感，后面可有空格）。
     *
     * @param text 归一化后的消息文本（非空）
     * @return 剥掉前缀后的正文（可能为空串）；<b>没有前缀时返回 null</b> ——
     *         调用方据此区分「闲聊」与「前缀后没写东西」两件事
     */
    private static String stripPrefix(String text) {
        if (text.charAt(0) == COMMAND_PREFIX) {
            return text.substring(1);
        }
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
     * 归一化：剥掉开头的 @ 提及 → 全角标点转半角 → 去掉首尾空白。
     *
     * <p>三步里<b>真正会改变行为的是后两步</b>：中文输入法下打出的全角斜杠与全角空格
     * 都很常见，而它们既不会被识别成斜杠、也不会被 {@link String#trim()} 当成空白
     * （那个方法只处理 {@code <= U+0020} 的字符）。少了这两步，
     * 用户发出来的指令会静默地变成一句「闲聊」，群里没有任何反馈可供他判断哪里错了。
     *
     * @param rawMessage 原始消息
     * @return 归一化后的文本，首尾无空白
     */
    private static String normalize(String rawMessage) {
        String text = LEADING_CQ_CODE.matcher(rawMessage).replaceFirst("");
        text = text.replace(FULL_WIDTH_SLASH, COMMAND_PREFIX)
                .replace(FULL_WIDTH_SPACE, ' ');
        return text.trim();
    }
}
