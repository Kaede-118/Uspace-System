package com.kaede.uspace.order.ocr;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 OCR 识别出的文本行里解析出「金额」与「交易单号」。
 *
 * <p><b>本类是本项目自己的业务规则，不是识别引擎的一部分。</b>
 * 百度只负责把图上的字读成一行行文本（{@code words_result}），
 * 「哪一行是金额、哪一行是单号」是这里说了算 —— 所以它被单独拎出来：
 * 纯静态、无状态、不碰 Spring 也不碰网络，可以脱离一切环境单测。
 *
 * <p><b>整个类只有一条纪律：认不出就返回 null，绝不猜。</b>
 * 它是辅助线索，判错会让管理员多看一眼那张截图（那本来就要看）；
 * 而猜错一个数字会让管理员<b>以为核对过了</b> —— 后者的代价大得多。
 *
 * <h3>规则一：金额找带货币符号的那一处</h3>
 *
 * <p>正则 {@code [¥￥]\s*数字}，取第一个<b>金额合理</b>的匹配。
 * 只认带货币符号的写法，是因为微信与支付宝的付款成功页金额都写作 {@code ¥8.00} ——
 * 放宽到「任何形如 8.00 的独立行」会误伤时间戳（{@code 21:45.32} 之类）与各种编号。
 *
 * <p>「合理」指 {@code 0 < 金额 ≤ 99999.99}：单笔消费不可能过万，
 * 而这个上限正好是数据库列 {@code DECIMAL(10,2)} 装得下的最大值 ——
 * 在这里挡住之后，下游不必再为「识别出一个天文数字导致插入失败」写第二道防御。
 *
 * <h3>规则二：交易单号先按关键词找，找不到再退化为「最长的数字串」</h3>
 *
 * <p>关键词的<b>顺序是有讲究的</b>（见 {@link #NO_RULES}）：交易单号是支付平台的，
 * 商户单号是商户自己的，前者才是对账时能跟平台账单勾稽上的那一个。
 *
 * <p>匹配前先去掉<b>行内</b>的空白：OCR 常把一长串数字断成 {@code 4200 0012 3420}
 * 这样，带着空格是匹配不到 {@code \d{16,40}} 的。
 * <b>但换行要留着</b> —— 它是行边界，抹掉会让金额的 {@code ¥8.00} 与下一行的单号
 * 粘成一个数，兜底规则随即认出一个多带两位的假单号。详见
 * {@link #extractPaymentNo} 里的说明。
 *
 * <p>关键词与数字之间允许隔几个非数字字符（{@code ：}、{@code *} 之类，
 * 或换行被去掉之后剩下的东西），但只给 10 个字符的额度 ——
 * 放太宽就会跨过中间那一行，把完全无关的数字认成单号。
 */
public final class OcrTextParser {

    /**
     * 金额：货币符号（半角 ¥ 或全角 ￥）后跟数字，最多两位小数。
     *
     * <p>{@code \s*} 是给「¥ 8.00」这种中间带空格的写法留的 —— 手机截图里常见。
     */
    private static final Pattern AMOUNT = Pattern.compile("[¥￥]\\s*(\\d+(?:\\.\\d{1,2})?)");

    /**
     * 交易单号的候选关键词，<b>按优先级排列</b>，前一个找不到才试下一个。
     *
     * <p>顺序的道理：微信付款页同时有「交易单号」与「商户单号」，
     * 支付宝同时有「订单号」与「商户订单号」。
     * <b>平台那一侧的号才是对账时能与平台账单勾稽的</b>，商户自己的号只能在
     * 本系统内部找到对应关系 —— 所以「交易单号 / 订单号」排在「商户单号」前面。
     */
    private static final List<PaymentNoRule> NO_RULES = List.of(
            rule("交易单号"),
            rule("订单号"),
            rule("商户单号"),
            rule("交易号"),
            rule("流水号"));

    /** 关键词与数字之间允许出现的非数字字符数，见类注释 */
    private static final String GAP = "\\D{0,10}";

    /**
     * 关键词之后的数字串长度。
     *
     * <p>12 位起步，比兜底规则宽松 —— 这里有「前面写着交易单号」这个佐证，
     * 短一点也认。微信的 28 位、支付宝的 28 位都在区间内。
     */
    private static final String DIGITS = "\\d{12,40}";

    /**
     * 兜底：全文第一个足够长的数字串。
     *
     * <p>比关键词规则严格（16 位起步，不是 12）—— 它没有任何上下文佐证，
     * 只能靠长度赌一把，那就赌得保守些。付款页上的时间、日期、门店编号都短于 16 位。
     */
    private static final Pattern FALLBACK_NO = Pattern.compile("\\d{16,40}");

    /**
     * 金额上限，与 {@code ocr_amount DECIMAL(10,2)} 的容量一致。
     *
     * <p>公开是给 {@code PaymentProofService} 用的：提交时回传上来的金额
     * 同样要过这一关。两处各定一份上限的话，改列宽时漏掉一处就会在插入时
     * 报 {@code Out of range value} —— 而那是用户完全无法自救的一种失败。
     */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("99999.99");

    /**
     * 原文的截断长度，与 {@code ocr_text VARCHAR(1000)} 的列宽一致。
     *
     * <p>在这里截而不是让数据库报错：超长会让整条提交失败，
     * 而用户完全不知道发生了什么（这三个字段是系统塞给他带回来的）。
     *
     * <p>公开的理由同 {@link #MAX_AMOUNT}：提交接口那边也要按它截一次。
     */
    public static final int MAX_TEXT_LENGTH = 1000;

    /** 工具类，不实例化 */
    private OcrTextParser() {
    }

    /**
     * 解析文本行。
     *
     * @param lines 识别出的文本行，可为 null 或空（引擎没认出任何字）
     * @return 解析结果。<b>永远不返回 null</b>，什么都没认出时返回 {@link OcrFields#EMPTY}
     */
    public static OcrFields parse(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return OcrFields.EMPTY;
        }

        // 原文按行拼接：留换行是为了让「交易单号」与它下面那行数字之间的关系
        // 还能被人看懂（后台会展示这段原文）
        String raw = normalize(String.join("\n", lines));
        String text = truncate(raw);
        return new OcrFields(extractPaymentNo(raw), extractAmount(raw),
                text.isBlank() ? null : text);
    }

    /**
     * 提取金额。
     *
     * <p>取第一个<b>落在合理区间内</b>的匹配，而不是第一个匹配 ——
     * 页面上可能先出现 {@code ¥0.00}（未支付时的占位）或营销文案里的数字。
     * 0 元不是一次有效的付款，跳过它继续往后找。
     *
     * @param text 识别原文
     * @return 金额，单位元；一处都没找到时返回 null
     */
    private static BigDecimal extractAmount(String text) {
        Matcher matcher = AMOUNT.matcher(text);
        while (matcher.find()) {
            BigDecimal value = new BigDecimal(matcher.group(1));
            if (value.compareTo(BigDecimal.ZERO) > 0 && value.compareTo(MAX_AMOUNT) <= 0) {
                return value;
            }
        }
        return null;
    }

    /**
     * 提取交易单号。
     *
     * @param text 识别原文
     * @return 单号；一处都没找到时返回 null
     */
    private static String extractPaymentNo(String text) {
        /*
         * 去掉【行内】的空白，但保留换行 —— 这两者的性质完全不同：
         *   · 行内的空格是识别噪声（OCR 常把 4200 0012 3420 这样断开），必须去掉
         *   · 换行是 OCR 的行边界，是有意义的分隔，去掉就会闯祸
         *
         * 全局去空白的后果很具体：金额那行「¥8.00」与它下面那行的单号会被粘成
         * 「¥8.004200001234…」，于是兜底规则从金额的小数部分开始匹配，
         * 认出一个前面多两个 0 的假单号 —— 而这串数字没人会逐位核对。
         *（这个 bug 是被 OcrTextParserTests 逮住的，不是想出来的。）
         */
        String compact = text.replaceAll("[^\\S\n]+", "");

        for (PaymentNoRule rule : NO_RULES) {
            Matcher matcher = rule.pattern().matcher(compact);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }

        Matcher fallback = FALLBACK_NO.matcher(compact);
        return fallback.find() ? fallback.group() : null;
    }

    /**
     * 归一化识别原文。
     *
     * <p><b>OCR 认不出「全角还是半角」，它只认形状</b> —— 付款截图上的
     * {@code ８．００} 与 {@code 8.00} 是同一张图，识别结果却可能一会儿全角一会儿半角。
     * NFKC 把这些兼容字符统一成标准写法，规则才不必为每种宽度各写一份。
     *
     * <p>顺带解决了一件小事：全角 {@code ￥} 归一化后就是 {@code ¥}，
     * 金额正则里那对「两种符号都认」的写法因此只为了兼容没归一化的输入，
     * 仍然保留 —— 它是最后一道保险，不值得为省两个字符去掉。
     *
     * <p>归一化后的文本同时用于解析与落库：存到 {@code ocr_text} 的那份也是归一化的，
     * 后台读起来更规整，而它本来就只是给管理员看的参考。
     *
     * @param text 拼接好的原文
     * @return NFKC 归一化后的文本
     */
    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC);
    }

    /**
     * 按列宽截断原文。
     *
     * <p>{@code substring} 按 UTF-16 码元切分，理论上可能把一个增补平面字符
     *（emoji 之类）切成两半。付款截图里出现这类字符的概率极低，
     * 而付出的代价只是后台展示时多一个乱码方块 —— 不值得为此写一段代理对判断。
     *
     * @param text 原文
     * @return 不超过 {@link #MAX_TEXT_LENGTH} 个字符的文本
     */
    private static String truncate(String text) {
        return text.length() <= MAX_TEXT_LENGTH ? text : text.substring(0, MAX_TEXT_LENGTH);
    }

    /**
     * 造一条「关键词 + 其后数字串」的规则。
     *
     * @param keyword 关键词，会被 {@link Pattern#quote} 转义（消息里出现 {@code *} 之类不会出错）
     * @return 规则
     */
    private static PaymentNoRule rule(String keyword) {
        return new PaymentNoRule(keyword,
                Pattern.compile(Pattern.quote(keyword) + GAP + "(" + DIGITS + ")"));
    }

    /**
     * 一条交易单号的提取规则。
     *
     * <p>{@code keyword} 只用于日志与注释的可读性，真正干活的是预先编译好的
     * {@code pattern} —— 每条规则在类加载时就编译一次，不在每次解析时现编。
     *
     * @param keyword 关键词
     * @param pattern 已编译的提取正则，第 1 组是单号
     */
    private record PaymentNoRule(String keyword, Pattern pattern) {
    }
}
