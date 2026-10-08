package com.kaede.uspace.qqbot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link QqCommandParser} 的单元测试。
 *
 * <p><b>纯单测，连 Spring 都不需要</b> —— 被测类是无状态静态工具，直接调即可。
 *
 * <p>这一层值得测得很密：它认错了<b>不会报任何错</b>，只会答非所问，
 * 而那看起来像「机器人变笨了」，不像一个 bug。
 *
 * <p>本类守住的五类坑，都是「写错了也照样能跑通演示」的：
 * <ol>
 *   <li><b>闲聊被当成指令</b> —— 群里一句「我等会儿到店里」若触发了在店名册，
 *       群会被刷屏，而这在演示时几乎撞不到（演示的人只发指令）</li>
 *   <li><b>缺前缀仍被认成指令</b> —— {@code /开门} 会<b>真的建单计费</b>，
 *       群里有人喊一声「开门」绝不能触发它</li>
 *   <li><b>全角斜杠</b> —— 中文输入法下打出来的就是 {@code ／}，不认它的话
 *       用户发指令得不到任何回应，且群里没有任何可供排查的报错</li>
 *   <li><b>@ 机器人的前缀</b> —— {@code [CQ:at,qq=...] /在店} 是群里最常见的用法</li>
 *   <li><b>验证码的位数</b> —— 5 位、7 位都不该被当成验证码，
 *       否则群友随口发的一串数字会去撞别人的验证</li>
 * </ol>
 */
class QqCommandParserTests {

    // ==================================================================
    // 前缀：/ 与 fw 等价，且【必须有】
    // ==================================================================

    @Test
    @DisplayName("解析：/ 与 fw 两种前缀等价")
    void parse_acceptsBothPrefixes() {
        assertEquals(QqCommand.Kind.INSTORE, QqCommandParser.parse("/在店").kind());
        assertEquals(QqCommand.Kind.INSTORE, QqCommandParser.parse("fw在店").kind(),
                "fw 是给中文输入法下懒得切符号的用户留的");
        assertEquals(QqCommand.Kind.INSTORE, QqCommandParser.parse("fw 在店").kind(),
                "前缀后带空格也认 —— 多一个空格不改变「我在跟机器人说话」这个语义");
        assertEquals(QqCommand.Kind.INSTORE, QqCommandParser.parse("FW在店").kind(),
                "前缀大小写不敏感");
    }

    @Test
    @DisplayName("解析：⚠️ 没有前缀的一律当闲聊 —— 这是加写指令时立的规矩")
    void parse_requiresPrefix() {
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("在店").kind(),
                "早先「不带斜杠也认」，但 /开门 会真建单计费，那条路必须堵死");
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("开门").kind(),
                "群里说「开门」可能只是在喊人");
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("结账").kind());
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("?").kind(),
                "连「?」这种看似指令的也不认 —— 规矩只有一条，例外越少越不容易漏");
    }

    @Test
    @DisplayName("解析：只发一个前缀时给提示，而不是静默")
    void parse_barePrefixGivesHint() {
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("/").kind());
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw").kind(),
                "他确实在跟机器人说话，只是没说完整 —— 静默会让他以为机器人坏了");
    }

    // ==================================================================
    // 各指令的识别
    // ==================================================================

    @Test
    @DisplayName("解析：/ping 与 /在吗 都识别为连通性自检")
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
        assertEquals(QqCommand.Kind.INSTORE, QqCommandParser.parse("fwkklm").kind(),
                "「看看里面」的拼音首字母：全在英文键盘上，不用切中文输入法就能发");
    }

    @Test
    @DisplayName("解析：七条新指令都能识别")
    void parse_recognizesNewCommands() {
        assertEquals(QqCommand.Kind.OPEN_DOOR, QqCommandParser.parse("/开门").kind());
        assertEquals(QqCommand.Kind.OPEN_DOOR, QqCommandParser.parse("fw开门").kind());
        assertEquals(QqCommand.Kind.SETTLE, QqCommandParser.parse("/结账").kind());
        assertEquals(QqCommand.Kind.SETTLE, QqCommandParser.parse("/settle").kind());
        assertEquals(QqCommand.Kind.BOOKING_SCHEDULE, QqCommandParser.parse("/包场").kind());
        assertEquals(QqCommand.Kind.ME, QqCommandParser.parse("/看看自己").kind());
        assertEquals(QqCommand.Kind.STORE_STATUS, QqCommandParser.parse("/营业").kind());
        assertEquals(QqCommand.Kind.PRICE, QqCommandParser.parse("/价格").kind());
        assertEquals(QqCommand.Kind.PRODUCT_MENU, QqCommandParser.parse("/菜单").kind());
        assertEquals(QqCommand.Kind.PRODUCT_MENU, QqCommandParser.parse("fw菜单").kind());
        assertEquals(QqCommand.Kind.PRODUCT_MENU, QqCommandParser.parse("fwmenu").kind(),
                "「菜单」的英文别名 —— 群里打 fwmenu 比打中文还顺手");
    }

    @Test
    @DisplayName("解析：/月卡 与 /pass、/card 都识别为月卡说明")
    void parse_recognizesCardAliases() {
        assertEquals(QqCommand.Kind.CARD_TYPES, QqCommandParser.parse("/月卡").kind());
        assertEquals(QqCommand.Kind.CARD_TYPES, QqCommandParser.parse("fw月卡").kind(),
                "fw 前缀与斜杠等价");
        assertEquals(QqCommand.Kind.CARD_TYPES, QqCommandParser.parse("/pass").kind(),
                "月卡在英文里就是 monthly pass");
        assertEquals(QqCommand.Kind.CARD_TYPES, QqCommandParser.parse("/Card").kind(),
                "英文别名大小写不敏感");
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("我先 pass 这局").kind(),
                "⚠️ 没有前缀的一律静默 —— 群里「pass 一下」是句闲聊，不能触发指令");
    }

    @Test
    @DisplayName("解析：/帮助 的英文别名与大小写")
    void parse_recognizesHelpAliases() {
        assertEquals(QqCommand.Kind.HELP, QqCommandParser.parse("/帮助").kind());
        assertEquals(QqCommand.Kind.HELP, QqCommandParser.parse("/help").kind());
        assertEquals(QqCommand.Kind.HELP, QqCommandParser.parse("/Help").kind());
    }

    @Test
    @DisplayName("解析：英文别名大小写不敏感")
    void parse_isCaseInsensitiveForAsciiAliases() {
        assertEquals(QqCommand.Kind.INSTORE, QqCommandParser.parse("/INSTORE").kind());
        assertEquals(QqCommand.Kind.PRICE, QqCommandParser.parse("/PRICE").kind());
    }

    @Test
    @DisplayName("解析：「看看自己」与「看看里面」不能串味")
    void parse_meAndInstoreAreDistinct() {
        assertEquals(QqCommand.Kind.ME, QqCommandParser.parse("/看看自己").kind());
        assertEquals(QqCommand.Kind.INSTORE, QqCommandParser.parse("/看看里面").kind(),
                "两条指令只差一个字，认错的表现是「问自己却报出全店的人」");
    }

    // ==================================================================
    // 闲聊必须静默 —— 这一组是整套规则里最重要的
    // ==================================================================

    @Test
    @DisplayName("解析：含关键词的闲聊不算指令")
    void parse_ignoresChitChatContainingKeywords() {
        // 这几句在群里天天有人发。用「包含」而不是「整条相等」来匹配的话，
        // 每句都会触发一次动作，而演示时根本撞不到
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("我等会儿到店里").kind());
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("你现在在店吗").kind());
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("大家好").kind());
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("我看看自己的余额").kind());
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("包场多少钱").kind(),
                "⚠️「多少钱」刻意没做别名：它是群里再普通不过的闲聊");
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("开门吧").kind());
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("菜单给我看看").kind(),
                "⚠️「菜单」这两个字在群里也会出现在闲聊里 —— 靠不带前缀 + 整条不相等两道挡住");
    }

    @Test
    @DisplayName("解析：带了前缀但含关键词的闲聊，给提示而不是执行")
    void parse_prefixedChitChatGivesHintNotAction() {
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("/开门吧").kind(),
                "带前缀说明在跟机器人说话，但整条不相等 —— 回提示，绝不建单");
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("/包场多少钱").kind());
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
    void parse_unknownCommandOnlyWhenPrefixed() {
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("/xyz").kind(),
                "带了前缀说明用户在跟机器人说话，打了错别字却得不到反馈，他会以为机器人坏了");
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("/").kind());
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("xyz").kind(),
                "不带前缀的当成闲聊");
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
        assertEquals(QqCommand.Kind.OPEN_DOOR,
                QqCommandParser.parse("[CQ:at,qq=1] fw开门").kind());
    }

    @Test
    @DisplayName("解析：正文里的 CQ 码字样不动它")
    void parse_keepsCqCodeInBody() {
        // 只剥开头的。正文里出现 [CQ:...] 是用户自己的内容，动了它就是篡改消息
        assertEquals(QqCommand.Kind.IGNORE,
                QqCommandParser.parse("这个 [CQ:face,id=1] 真好玩").kind());
    }

    // ==================================================================
    // 验证码：/验证 123456
    // ==================================================================

    @Test
    @DisplayName("解析：/验证 后跟 6 位数字才是验证码")
    void parse_verifyCodeNeedsKeyword() {
        assertEquals(QqCommand.Kind.VERIFY_CODE, QqCommandParser.parse("/验证 123456").kind());
        assertEquals(QqCommand.Kind.VERIFY_CODE, QqCommandParser.parse("fw验证 042317").kind(),
                "前缀两种写法都认");
        assertEquals(QqCommand.Kind.VERIFY_CODE, QqCommandParser.parse("/验证123456").kind(),
                "关键词与数字之间有没有空格都认");
        assertEquals(QqCommand.Kind.VERIFY_CODE, QqCommandParser.parse("/verify 123456").kind());
    }

    @Test
    @DisplayName("解析：⚠️ 纯 6 位数字不再是验证码")
    void parse_bareSixDigitsIsNoLongerVerifyCode() {
        // 2026-10-04 改：验证码要求带 /验证 前缀。
        // 理由是群友随口发的数字不该去撞别人的验证，而注册页现在给的是
        // 一条可以整条复制的指令，用户不必理解它
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("123456").kind());
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("042317").kind());
    }

    @Test
    @DisplayName("解析：位数不对的不算验证码")
    void parse_verifyCodeRequiresExactlySixDigits() {
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("/验证 12345").kind(),
                "5 位 → 认不出这条指令（而不是当成验证码去撞）");
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("/验证 1234567").kind());
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("/验证 12345a").kind());
    }

    @Test
    @DisplayName("解析：验证码原样带出来（前导零不能丢）")
    void parse_carriesVerifyCodeAsArgument() {
        QqCommand command = QqCommandParser.parse("/验证 042317");

        assertEquals("042317", command.argument(),
                "带出去的必须是原样的字符串，不是数字 —— 转成数字再判的话 042317 会变成 5 位的 42317");
    }

    // ==================================================================
    // 商品下单：买个X / 买N个X / 名-数量
    // ==================================================================

    // ==================================================================
    // 当前订单
    // ==================================================================

    @Test
    @DisplayName("解析：/now 与 /当前订单 都认，且刻意不给「订单」这种泛别名")
    void parse_recognizesCurrentOrder() {
        assertEquals(QqCommand.Kind.CURRENT_ORDER, QqCommandParser.parse("/now").kind());
        assertEquals(QqCommand.Kind.CURRENT_ORDER, QqCommandParser.parse("/NOW").kind(),
                "大小写不敏感");
        assertEquals(QqCommand.Kind.CURRENT_ORDER, QqCommandParser.parse("fw当前订单").kind());
        assertEquals(QqCommand.Kind.CURRENT_ORDER, QqCommandParser.parse("/当前订单").kind());

        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("/订单").kind(),
                "⚠️ 群里说「订单」可能指任何一笔，而这条只讲【正在计时的那一单】——"
                        + "名字里带「当前」才不会误解");
    }

    // ==================================================================
    // 网页端地址
    // ==================================================================

    @Test
    @DisplayName("解析：/web 与 /网址 都认，且仍然整条精确匹配")
    void parse_recognizesWebCommand() {
        assertEquals(QqCommand.Kind.WEB, QqCommandParser.parse("/web").kind());
        assertEquals(QqCommand.Kind.WEB, QqCommandParser.parse("/WEB").kind(), "大小写不敏感");
        assertEquals(QqCommand.Kind.WEB, QqCommandParser.parse("fwweb").kind(), "fw 前缀等价");
        assertEquals(QqCommand.Kind.WEB, QqCommandParser.parse("/网址").kind());

        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("网址发我一下").kind(),
                "整条精确匹配 —— 群里问「网址多少」这种闲聊不会触发");
    }

    @Test
    @DisplayName("商品：买个X 是 1 件")
    void parseProduct_buyOneForm() {
        QqCommand command = QqCommandParser.parse("/买个可乐 500ml");

        assertEquals(QqCommand.Kind.PRODUCT_ORDER, command.kind());
        assertEquals("可乐 500ml", command.argument(), "名字里的空格要原样留着");
        assertNull(command.quantity(),
                "null 表示「买个X」那种写法 —— 量词「个」本身已经说明了是 1 件");

        assertEquals(QqCommand.Kind.PRODUCT_ORDER, QqCommandParser.parse("fw买个可乐").kind(),
                "fw 前缀等价");
    }

    @Test
    @DisplayName("商品：买N个X 取到数量，阿拉伯数字与中文数词都认")
    void parseProduct_buyQuantityForm() {
        assertEquals(2, QqCommandParser.parse("/买2个可乐").quantity());
        assertEquals("可乐", QqCommandParser.parse("/买2个可乐").argument());
        assertEquals(2, QqCommandParser.parse("/买两个可乐").quantity(),
                "「买两个可乐」才是群里最自然的说法 —— 只认阿拉伯数字会白挨一句「没认出」");
        assertEquals(10, QqCommandParser.parse("/买十个手套").quantity());
        assertEquals("手套", QqCommandParser.parse("/买3个 手套 ").argument(),
                "名字两边的空白要去掉");
    }

    @Test
    @DisplayName("商品：⚠️ 商品名里的「个」不能被当量词（量词取【第一个】个）")
    void parseProduct_usesFirstMeasureWord() {
        QqCommand command = QqCommandParser.parse("/买2个个人杯");

        assertEquals("个人杯", command.argument(), "取最后一个「个」会把名字切坏");
        assertEquals(2, command.quantity());
    }

    @Test
    @DisplayName("商品：名-数量 那种简写也认，在最后一个横杠处切")
    void parseProduct_dashForm() {
        QqCommand command = QqCommandParser.parse("/冰-红茶-2");

        assertEquals(QqCommand.Kind.PRODUCT_ORDER, command.kind());
        assertEquals("冰-红茶", command.argument());
        assertEquals(2, command.quantity());
        assertEquals(2, QqCommandParser.parse("/可乐 500ml-2").quantity());
        assertEquals(2, QqCommandParser.parse("/可乐－２").quantity(),
                "全角横杠与全角数字都要认 —— 中文输入法下打出来的就是它们");
    }

    @Test
    @DisplayName("商品：⚠️ 没带关键词也没带横杠的一律「没认出这条指令」")
    void parseProduct_requiresKeywordOrDash() {
        // 2026-10-04 与用户确认的改法：早先「/可乐」也算下单一件，
        // 那意味着任何带前缀的未知文本都要当商品名查一遍 ——
        // 打错的指令会得到一句「没有叫「在店铺」的商品」，白白吓人一跳
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("/可乐").kind());
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("/可乐 500ml").kind(),
                "光写名字不算下单 —— 老老实实写数量");
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("/买可乐").kind(),
                "少了量词「个」：宁可回「没认出」，也不替用户决定买几件");
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("/买个").kind());
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("/买2个").kind());
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("/-2").kind(),
                "切出来名字为空 —— 不算下单");
    }

    @Test
    @DisplayName("商品：⚠️ 数量不做范围校验 —— 超范围也原样带出去")
    void parseProduct_doesNotValidateQuantityRange() {
        // 范围（1~99）是业务规则，由 Service 判 —— 放在解析器里的话，
        // 那句「单次最多买 99 件」就没有地方可写了，用户只会收到「没认出这条指令」
        assertEquals(100, QqCommandParser.parse("/买100个可乐").quantity());
        assertEquals(0, QqCommandParser.parse("/可乐-0").quantity());
    }

    @Test
    @DisplayName("商品：4 位以上数字不算数量（「可乐-2024」更可能是名字）")
    void parseProduct_treatsLongNumbersAsPartOfName() {
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("/可乐-2024").kind());
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND,
                QqCommandParser.parse("/买2024个可乐").kind());
    }

    @Test
    @DisplayName("商品：⚠️「买单」已不是结账的别名（2026-10-04 删）")
    void parseProduct_buyAliasRemoved() {
        // 它与商品下单的「买…个…」共用「买」字开头，两条语法混在一起容易被误用：
        // 看到 /买单 能结账，就会以为 /买可乐 也能下单。
        // 删掉之后它落到「没认出这条指令」—— 那是个正确的反馈，它确实不再是指令了
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("/买单").kind());
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw买单").kind());
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
                QqCommandParser.parse("可用指令（前面加 / 或 fw，例如 /在店）：\n/包场 —— 看看近期的包场安排").kind(),
                "帮助文案里虽然含 /包场 字样，但整条不相等，不该被认成指令");
        assertEquals(QqCommand.Kind.IGNORE,
                QqCommandParser.parse("门锁密码：123456\n用一次即作废，请勿转发。").kind(),
                "⚠️ 开门回复里那串密码绝不能反过来触发什么");
        assertEquals(QqCommand.Kind.IGNORE,
                QqCommandParser.parse("已停止计时，本次应付 ¥12.00。\n去支付：http://localhost:5173/#/orders/1/settle").kind());
    }

    @Test
    @DisplayName("回环：验证码绝不能落到闲聊上")
    void parse_verifyCodeIsNeverIgnored() {
        // ⚠️ 这条是踩过坑才加的。曾经「自己发的消息」按「有没有前缀」过滤，
        // 而那时的验证码不带前缀 —— 于是同号登录 bot 时，运营者在手机上发的验证码
        // 被当成「机器人自己发的话」挡掉：取码正常、发群正常，
        // 但【验证永远通不过，且后端与群里都没有任何报错】。
        // 判据换成「解析结果是不是 IGNORE」之后，这条断言就是那道防线的锚点。
        assertEquals(QqCommand.Kind.VERIFY_CODE, QqCommandParser.parse("/验证 253106").kind());
        assertNotEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("/验证 253106").kind());
    }
}
