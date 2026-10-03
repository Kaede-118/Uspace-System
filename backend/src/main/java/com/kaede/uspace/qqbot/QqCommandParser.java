package com.kaede.uspace.qqbot;

import java.util.Locale;
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
 * 再去掉可有可无的前导 {@code /}，然后<b>整条精确匹配</b>：
 *
 * <ul>
 *   <li>{@code 在店} / {@code instore} / {@code rs} → {@link QqCommand.Kind#INSTORE}</li>
 *   <li>{@code 帮助} / {@code help} / {@code ?} → {@link QqCommand.Kind#HELP}</li>
 *   <li>恰好 6 位数字 → {@link QqCommand.Kind#VERIFY_CODE}</li>
 *   <li>上面都不中，但原文带 {@code /} → {@link QqCommand.Kind#UNKNOWN_COMMAND}</li>
 *   <li>其余 → {@link QqCommand.Kind#IGNORE}</li>
 * </ul>
 *
 * <p>⚠️ <b>「整条精确匹配」是这里的核心决定</b>：若改成「包含关键词」，
 * 那么群里一句「我等会儿到店里」就会触发一次在店名册 —— 而群里天天有人这么说。
 * 精确匹配的代价是用户必须把指令发得干净，这个代价由 {@code /帮助} 兜住。
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
     */
    private static final Set<String> INSTORE_ALIASES = Set.of("在店", "instore", "rs", "看看里面");

    /** 同上 */
    private static final Set<String> HELP_ALIASES = Set.of("帮助", "help", "?");

    /**
     * 连通性自检。
     *
     * <p>中文别名给「在吗」—— 群里问「在吗」而机器人回一句「pong」，
     * 比让它对这个最常见的中文招呼保持沉默要自然得多。
     */
    private static final Set<String> PING_ALIASES = Set.of("ping", "在吗");

    /** 6 位纯数字。前导零要保留，所以按字符串匹配而不是转成数字 */
    private static final Pattern VERIFY_CODE_PATTERN = Pattern.compile("^\\d{6}$");

    /** 斜杠前缀。它是「我在跟机器人说话」的标志，见 {@link QqCommand.Kind} */
    private static final char COMMAND_PREFIX = '/';

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

        // 带不带斜杠都认：用户第一次多半不知道要加，而加了的也照常能识别。
        // 剥掉的只是【一个】前导斜杠，"//在店" 仍然是认不出的
        boolean hasPrefix = text.charAt(0) == COMMAND_PREFIX;
        String body = hasPrefix ? text.substring(1).trim() : text;
        if (body.isEmpty()) {
            return hasPrefix ? QqCommand.unknownCommand() : QqCommand.ignore();
        }

        // 统一转小写再比，英文别名大小写不敏感。
        //
        // ⚠️ 三个别名表必须用【同一个】lower —— 漏掉其中一处的话，那个指令的大写写法
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
        if (VERIFY_CODE_PATTERN.matcher(body).matches()) {
            return QqCommand.verifyCode(body);
        }

        // 走到这里说明没认出来。带斜杠的认为是在跟机器人说话，给个提示；
        // 不带的当闲聊，静默 —— 见 QqCommand.Kind 的说明
        return hasPrefix ? QqCommand.unknownCommand() : QqCommand.ignore();
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
