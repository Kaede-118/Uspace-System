package com.kaede.uspace.qqbot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link QqCommandParser} 的单元测试。
 *
 * <p><b>纯单测，连 Spring 都不需要</b> —— 被测类是无状态静态工具，直接调即可。
 *
 * <p>这一层值得测得很密：它认错了<b>不会报任何错</b>，只会答非所问，
 * 而那看起来像「机器人变笨了」，不像一个 bug。
 *
 * <p>本类守住的四类坑，都是「写错了也照样能跑通演示」的：
 * <ol>
 *   <li><b>闲聊被当成指令</b> —— 群里一句「我等会儿到店里」若触发了在店名册，
 *       群会被刷屏，而这在演示时几乎撞不到（演示的人只发指令）</li>
 *   <li><b>全角斜杠</b> —— 中文输入法下打出来的就是 {@code ／}，不认它的话
 *       用户发指令得不到任何回应，且群里没有任何可供排查的报错</li>
 *   <li><b>@ 机器人的前缀</b> —— {@code [CQ:at,qq=...] /在店} 是群里最常见的用法</li>
 *   <li><b>验证码的位数</b> —— 5 位、7 位都不该被当成验证码，
 *       否则群友随口发的一串数字会去撞别人的验证</li>
 * </ol>
 */
class QqCommandParserTests {

    // ==================================================================
    // 指令
    // ==================================================================

    @Test
    @DisplayName("解析：/ping 识别为连通性自检")
    void parse_recognizesPing() {
        assertEquals(QqCommand.Kind.PING, QqCommandParser.parse("/ping").kind());
        assertEquals(QqCommand.Kind.PING, QqCommandParser.parse("/在吗").kind(),
                "群里问「在吗」时回一句 pong，比对这个最常见的招呼保持沉默自然得多");
    }

    @Test
    @DisplayName("解析：/在店 与 /看看里面 都识别为在店名册")
    void parse_recognizesInstore() {
        assertEquals(QqCommand.Kind.INSTORE, QqCommandParser.parse("/在店").kind());
        assertEquals(QqCommand.Kind.INSTORE, QqCommandParser.parse("/看看里面").kind(),
                "口语化的那个别名 —— 群里问「里面有人吗」比「在店」自然");
        assertEquals(QqCommand.Kind.INSTORE, QqCommandParser.parse("看看里面").kind(),
                "不带斜杠同样认");
    }

    @Test
    @DisplayName("解析：含「看看里面」字样的闲聊仍不算指令")
    void parse_ignoresChitChatWithInstoreAlias() {
        assertEquals(QqCommand.Kind.IGNORE,
                QqCommandParser.parse("看看里面那个人在不在").kind(),
                "别名走的是整条精确匹配 —— 加别名不会让闲聊误触发");
    }

    @Test
    @DisplayName("解析：指令不带斜杠也能认")
    void parse_acceptsCommandWithoutSlash() {
        assertEquals(QqCommand.Kind.INSTORE, QqCommandParser.parse("在店").kind(),
                "用户第一次多半不知道要加斜杠，而精确匹配已经挡住了闲聊（见下一条）");
    }

    @Test
    @DisplayName("解析：/帮助 的三种写法都认")
    void parse_recognizesHelpAliases() {
        assertEquals(QqCommand.Kind.HELP, QqCommandParser.parse("/帮助").kind());
        assertEquals(QqCommand.Kind.HELP, QqCommandParser.parse("/help").kind());
        assertEquals(QqCommand.Kind.HELP, QqCommandParser.parse("?").kind());
    }

    @Test
    @DisplayName("解析：英文别名大小写不敏感")
    void parse_isCaseInsensitiveForAsciiAliases() {
        assertEquals(QqCommand.Kind.INSTORE, QqCommandParser.parse("/INSTORE").kind());
        assertEquals(QqCommand.Kind.HELP, QqCommandParser.parse("/Help").kind());
    }

    // ==================================================================
    // 闲聊必须静默 —— 这一组是整套规则里最重要的
    // ==================================================================

    @Test
    @DisplayName("解析：含关键词的闲聊不算指令")
    void parse_ignoresChitChatContainingKeywords() {
        // 这几句在群里天天有人发。用「包含」而不是「整条相等」来匹配的话，
        // 每句都会触发一次在店名册，而演示时根本撞不到
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("我等会儿到店里").kind());
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("你现在在店吗").kind());
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("大家好").kind());
    }

    @Test
    @DisplayName("解析：空消息与 null 一律静默")
    void parse_ignoresEmptyInput() {
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse(null).kind());
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("").kind());
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("   ").kind());
    }

    @Test
    @DisplayName("解析：看着像指令但不认识的，要回一句提示而不是静默")
    void parse_unknownCommandOnlyWhenItLooksLikeOne() {
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("/开门").kind(),
                "带斜杠说明用户在跟机器人说话，打了错别字却得不到反馈，他会以为机器人坏了");
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("开门").kind(),
                "不带斜杠的当成闲聊 —— 群里说「开门」可能只是在喊人");
    }

    // ==================================================================
    // 全角与 @ 前缀
    // ==================================================================

    @Test
    @DisplayName("解析：全角斜杠与全角空格都能认")
    void parse_normalizesFullWidthCharacters() {
        assertEquals(QqCommand.Kind.INSTORE, QqCommandParser.parse("／在店").kind(),
                "中文输入法下打出来的就是全角斜杠 —— 不认的话用户得不到任何回应");
        assertEquals(QqCommand.Kind.INSTORE, QqCommandParser.parse("　/在店　").kind(),
                "全角空格不会被 String.trim() 去掉");
    }

    @Test
    @DisplayName("解析：剥掉开头的 @ 机器人")
    void parse_stripsLeadingMentions() {
        assertEquals(QqCommand.Kind.INSTORE,
                QqCommandParser.parse("[CQ:at,qq=2198047522] /在店").kind(),
                "群里 @机器人 再说指令是最常见的用法");
        assertEquals(QqCommand.Kind.PING,
                QqCommandParser.parse("[CQ:at,qq=1][CQ:face,id=1] /ping").kind(),
                "连续多段 CQ 码也要能剥干净");
    }

    @Test
    @DisplayName("解析：正文里的 CQ 码字样不动它")
    void parse_keepsCqCodeInBody() {
        // 只剥开头的。正文里出现 [CQ:...] 是用户自己的内容，动了它就是篡改消息
        assertEquals(QqCommand.Kind.IGNORE,
                QqCommandParser.parse("这个 [CQ:face,id=1] 真好玩").kind());
    }

    // ==================================================================
    // 验证码
    // ==================================================================

    @Test
    @DisplayName("解析：恰好 6 位数字才是验证码")
    void parse_verifyCodeRequiresExactlySixDigits() {
        assertEquals(QqCommand.Kind.VERIFY_CODE, QqCommandParser.parse("123456").kind());
        assertEquals(QqCommand.Kind.VERIFY_CODE, QqCommandParser.parse("042317").kind(),
                "前导零要保留 —— 转成数字再判的话 042317 会变成 5 位的 42317");
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("12345").kind());
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("1234567").kind());
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("12345a").kind());
    }

    @Test
    @DisplayName("解析：验证码原样带出来")
    void parse_carriesVerifyCodeAsArgument() {
        QqCommand command = QqCommandParser.parse("042317");

        assertEquals("042317", command.argument(), "带出去的必须是原样的字符串，不是数字");
    }

    // ==================================================================
    // 回环防线：机器人自己发出去的东西必须都落回「闲聊」
    // ==================================================================

    @Test
    @DisplayName("回环：机器人发出去的那几种文本全都解析为闲聊")
    void parse_botRepliesAreAllIgnored() {
        // ⚠️ QqCommandService 用「解析结果是不是 IGNORE」来判断
        // 「自己那一侧发的消息要不要处理」。所以这条断言守的是【回环防线本身】：
        // 只要下面有一种能被解析成指令，机器人就会回复自己，然后无限循环。
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("pong").kind());
        assertEquals(QqCommand.Kind.IGNORE,
                QqCommandParser.parse("当前 3 人在店：\n1. 张三 · 1 小时 20 分").kind());
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("现在店里没人。").kind());
        assertEquals(QqCommand.Kind.IGNORE,
                QqCommandParser.parse("验证成功，可以回注册页继续了").kind());
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("验证码不正确").kind());
        assertEquals(QqCommand.Kind.IGNORE,
                QqCommandParser.parse("可用指令\n/ping —— 看看机器人在不在").kind(),
                "帮助文案里虽然含 /ping 字样，但整条不相等，不该被认成指令");
    }

    @Test
    @DisplayName("回环：6 位验证码绝不能落到闲聊上")
    void parse_verifyCodeIsNeverIgnored() {
        // ⚠️ 这条是踩过坑才加的。曾经「自己发的消息」按「有没有 / 前缀」过滤，
        // 而验证码不带斜杠 —— 于是同号登录 bot 时，运营者在手机上发的验证码
        // 被当成「机器人自己发的话」挡掉：取码正常、发群正常，
        // 但【验证永远通不过，且后端与群里都没有任何报错】。
        // 判据换成「解析结果是不是 IGNORE」之后，这条断言就是那道防线的锚点。
        assertEquals(QqCommand.Kind.VERIFY_CODE, QqCommandParser.parse("253106").kind());
        assertNotEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("253106").kind());
    }
}
