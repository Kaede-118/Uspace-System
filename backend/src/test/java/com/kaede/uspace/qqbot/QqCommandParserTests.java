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
 *   <li><b>缺前缀仍被认成指令</b> —— {@code fw开门} 会<b>真的建单计费</b>，
 *       群里有人喊一声「开门」绝不能触发它</li>
 *   <li><b>全角斜杠</b> —— 中文输入法下打出来的就是 {@code ／}，不认它的话
 *       用户发指令得不到任何回应，且群里没有任何可供排查的报错</li>
 *   <li><b>@ 机器人的前缀</b> —— {@code [CQ:at,qq=...] fw在店} 是群里最常见的用法</li>
 *   <li><b>验证码的位数</b> —— 5 位、7 位都不该被当成验证码，
 *       否则群友随口发的一串数字会去撞别人的验证</li>
 * </ol>
 */
class QqCommandParserTests {

    // ==================================================================
    // 前缀：只有 fw 一种，且【必须有】（唯一豁免是 kklm，见下方用例）
    // ==================================================================

    @Test
    @DisplayName("解析：fw 前缀的几种写法都认")
    void parse_acceptsPrefixVariants() {
        assertEquals(QqCommand.Kind.INSTORE, QqCommandParser.parse("fw在店").kind());
        assertEquals(QqCommand.Kind.INSTORE, QqCommandParser.parse("fw 在店").kind(),
                "前缀后带空格也认 —— 多一个空格不改变「我在跟机器人说话」这个语义");
        assertEquals(QqCommand.Kind.INSTORE, QqCommandParser.parse("FW在店").kind(),
                "前缀大小写不敏感");
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("/在店").kind(),
                "⚠️ 斜杠自 2026-10-09 起不再是前缀，与「没前缀」走同一条路（当闲聊）");
    }

    @Test
    @DisplayName("解析：⚠️ 没有前缀的一律当闲聊 —— 这是加写指令时立的规矩")
    void parse_requiresPrefix() {
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("在店").kind(),
                "早先「不带斜杠也认」，但 fw开门 会真建单计费，那条路必须堵死");
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("开门").kind(),
                "群里说「开门」可能只是在喊人");
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("结账").kind());
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("?").kind(),
                "连「?」这种看似指令的也不认 —— 规矩只有一条，例外越少越不容易漏");
    }

    @Test
    @DisplayName("解析：只发一个前缀时给提示，而不是静默")
    void parse_barePrefixGivesHint() {
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw").kind(),
                "他确实在跟机器人说话，只是没说完整 —— 静默会让他以为机器人坏了");
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("/").kind(),
                "⚠️ 斜杠自 2026-10-09 起不再是前缀，它就是一句闲聊");
    }

    // ==================================================================
    // 各指令的识别
    // ==================================================================

    @Test
    @DisplayName("解析：fwping 与 fw在吗 都识别为连通性自检")
    void parse_recognizesPing() {
        assertEquals(QqCommand.Kind.PING, QqCommandParser.parse("fwping").kind());
        assertEquals(QqCommand.Kind.PING, QqCommandParser.parse("fw在吗").kind(),
                "群里问「在吗」时回一句 pong，比对这个最常见的招呼保持沉默自然得多");
    }

    @Test
    @DisplayName("解析：fw在店 与 fw看看里面 都识别为在店名册")
    void parse_recognizesInstore() {
        assertEquals(QqCommand.Kind.INSTORE, QqCommandParser.parse("fw在店").kind());
        assertEquals(QqCommand.Kind.INSTORE, QqCommandParser.parse("fw看看里面").kind(),
                "口语化的那个别名 —— 群里问「里面有人吗」比「在店」自然");
        assertEquals(QqCommand.Kind.INSTORE, QqCommandParser.parse("fwkklm").kind(),
                "「看看里面」的拼音首字母：全在英文键盘上，不用切中文输入法就能发");
    }

    @Test
    @DisplayName("解析：kklm 免前缀（唯一豁免），其余指令仍必须有前缀")
    void parse_prefixFreeKklm() {
        assertEquals(QqCommand.Kind.INSTORE, QqCommandParser.parse("kklm").kind(),
                "2026-10-10 起它不需要 fw：四个英文字母的刻意输入，不像闲聊");
        assertEquals(QqCommand.Kind.INSTORE, QqCommandParser.parse("KKLM").kind(),
                "大小写不敏感 —— 与其余英文别名同一套 lower");
        assertEquals(QqCommand.Kind.INSTORE,
                QqCommandParser.parse("[CQ:at,qq=123] kklm").kind(),
                "开头的 @ 提及照旧先剥掉");
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("kklm一下").kind(),
                "⚠️ 豁免只认【整条相等】—— 带尾巴的仍当闲聊，免得蹭到别的词");
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("看看里面").kind(),
                "中文别名不享受豁免：它是群聊常见词，误触发就是满群乱答");
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("instore").kind(),
                "英文别名同理 —— 豁免只给 kklm 一个");
    }

    @Test
    @DisplayName("解析：七条新指令都能识别")
    void parse_recognizesNewCommands() {
        assertEquals(QqCommand.Kind.OPEN_DOOR, QqCommandParser.parse("fw开门").kind());
        assertEquals(QqCommand.Kind.OPEN_DOOR, QqCommandParser.parse("fw开门").kind());
        assertEquals(QqCommand.Kind.SETTLE, QqCommandParser.parse("fw结账").kind());
        assertEquals(QqCommand.Kind.SETTLE, QqCommandParser.parse("fwsettle").kind());
        assertEquals(QqCommand.Kind.BOOKING_SCHEDULE, QqCommandParser.parse("fw包场").kind());
        assertEquals(QqCommand.Kind.ME, QqCommandParser.parse("fw看看自己").kind());
        assertEquals(QqCommand.Kind.STORE_STATUS, QqCommandParser.parse("fw营业").kind());
        assertEquals(QqCommand.Kind.PRICE, QqCommandParser.parse("fw价格").kind());
        assertEquals(QqCommand.Kind.PRODUCT_MENU, QqCommandParser.parse("fw菜单").kind());
        assertEquals(QqCommand.Kind.PRODUCT_MENU, QqCommandParser.parse("fw菜单").kind());
        assertEquals(QqCommand.Kind.PRODUCT_MENU, QqCommandParser.parse("fwmenu").kind(),
                "「菜单」的英文别名 —— 群里打 fwmenu 比打中文还顺手");
    }

    @Test
    @DisplayName("解析：fw月卡 与 fwpass、fwcard 都识别为月卡说明")
    void parse_recognizesCardAliases() {
        assertEquals(QqCommand.Kind.CARD_TYPES, QqCommandParser.parse("fw月卡").kind());
        assertEquals(QqCommand.Kind.CARD_TYPES, QqCommandParser.parse("fw月卡").kind(),
                "fw 前缀与斜杠等价");
        assertEquals(QqCommand.Kind.CARD_TYPES, QqCommandParser.parse("fwpass").kind(),
                "月卡在英文里就是 monthly pass");
        assertEquals(QqCommand.Kind.CARD_TYPES, QqCommandParser.parse("fwCard").kind(),
                "英文别名大小写不敏感");
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("我先 pass 这局").kind(),
                "⚠️ 没有前缀的一律静默 —— 群里「pass 一下」是句闲聊，不能触发指令");
    }

    @Test
    @DisplayName("解析：fw帮助 的英文别名与大小写")
    void parse_recognizesHelpAliases() {
        assertEquals(QqCommand.Kind.HELP, QqCommandParser.parse("fw帮助").kind());
        assertEquals(QqCommand.Kind.HELP, QqCommandParser.parse("fwhelp").kind());
        assertEquals(QqCommand.Kind.HELP, QqCommandParser.parse("fwHelp").kind());
    }

    @Test
    @DisplayName("解析：英文别名大小写不敏感")
    void parse_isCaseInsensitiveForAsciiAliases() {
        assertEquals(QqCommand.Kind.INSTORE, QqCommandParser.parse("fwINSTORE").kind());
        assertEquals(QqCommand.Kind.PRICE, QqCommandParser.parse("fwPRICE").kind());
    }

    @Test
    @DisplayName("解析：「看看自己」与「看看里面」不能串味")
    void parse_meAndInstoreAreDistinct() {
        assertEquals(QqCommand.Kind.ME, QqCommandParser.parse("fw看看自己").kind());
        assertEquals(QqCommand.Kind.INSTORE, QqCommandParser.parse("fw看看里面").kind(),
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
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw开门吧").kind(),
                "带前缀说明在跟机器人说话，但整条不相等 —— 回提示，绝不建单");
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw包场多少钱").kind());
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
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fwxyz").kind(),
                "带了前缀说明用户在跟机器人说话，打了错别字却得不到反馈，他会以为机器人坏了");
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("/").kind(),
                "⚠️ 斜杠自 2026-10-09 起不再是前缀（只认 fw），它就是一句闲聊");
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("xyz").kind(),
                "不带前缀的当成闲聊");
    }

    // ==================================================================
    // 全角与 @ 前缀
    // ==================================================================

    @Test
    @DisplayName("解析：全角空格能认（全角斜杠自 2026-10-09 起不再当前缀）")
    void parse_normalizesFullWidthCharacters() {
        assertEquals(QqCommand.Kind.INSTORE, QqCommandParser.parse("　fw在店　").kind(),
                "全角空格不会被 String.trim() 去掉");
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("／在店").kind(),
                "⚠️ 斜杠不再是前缀，全角斜杠自然也不再归一化 —— "
                        + "它现在与「没前缀」走同一条路（闲聊，静默）");
    }

    @Test
    @DisplayName("解析：剥掉开头的 @ 机器人")
    void parse_stripsLeadingMentions() {
        assertEquals(QqCommand.Kind.INSTORE,
                QqCommandParser.parse("[CQ:at,qq=2198047522] fw在店").kind(),
                "群里 @机器人 再说指令是最常见的用法");
        assertEquals(QqCommand.Kind.PING,
                QqCommandParser.parse("[CQ:at,qq=1][CQ:face,id=1] fwping").kind(),
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
    // 验证码：fw验证 123456
    // ==================================================================

    @Test
    @DisplayName("解析：fw验证 后跟 6 位数字才是验证码")
    void parse_verifyCodeNeedsKeyword() {
        assertEquals(QqCommand.Kind.VERIFY_CODE, QqCommandParser.parse("fw验证 123456").kind());
        assertEquals(QqCommand.Kind.VERIFY_CODE, QqCommandParser.parse("fw验证 042317").kind(),
                "前缀两种写法都认");
        assertEquals(QqCommand.Kind.VERIFY_CODE, QqCommandParser.parse("fw验证123456").kind(),
                "关键词与数字之间有没有空格都认");
        assertEquals(QqCommand.Kind.VERIFY_CODE, QqCommandParser.parse("fwverify 123456").kind());
    }

    @Test
    @DisplayName("解析：⚠️ 纯 6 位数字不再是验证码")
    void parse_bareSixDigitsIsNoLongerVerifyCode() {
        // 2026-10-04 改：验证码要求带 fw验证 前缀。
        // 理由是群友随口发的数字不该去撞别人的验证，而注册页现在给的是
        // 一条可以整条复制的指令，用户不必理解它
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("123456").kind());
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("042317").kind());
    }

    @Test
    @DisplayName("解析：位数不对的不算验证码")
    void parse_verifyCodeRequiresExactlySixDigits() {
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw验证 12345").kind(),
                "5 位 → 认不出这条指令（而不是当成验证码去撞）");
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw验证 1234567").kind());
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw验证 12345a").kind());
    }

    @Test
    @DisplayName("解析：验证码原样带出来（前导零不能丢）")
    void parse_carriesVerifyCodeAsArgument() {
        QqCommand command = QqCommandParser.parse("fw验证 042317");

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
    @DisplayName("解析：fwnow 与 fw当前订单 都认，且刻意不给「订单」这种泛别名")
    void parse_recognizesCurrentOrder() {
        assertEquals(QqCommand.Kind.CURRENT_ORDER, QqCommandParser.parse("fwnow").kind());
        assertEquals(QqCommand.Kind.CURRENT_ORDER, QqCommandParser.parse("fwNOW").kind(),
                "大小写不敏感");
        assertEquals(QqCommand.Kind.CURRENT_ORDER, QqCommandParser.parse("fw当前订单").kind());
        assertEquals(QqCommand.Kind.CURRENT_ORDER, QqCommandParser.parse("fw当前订单").kind());

        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw订单").kind(),
                "⚠️ 群里说「订单」可能指任何一笔，而这条只讲【正在计时的那一单】——"
                        + "名字里带「当前」才不会误解");
    }

    // ==================================================================
    // 网页端地址
    // ==================================================================

    @Test
    @DisplayName("解析：fwweb 与 fw网址 都认，且仍然整条精确匹配")
    void parse_recognizesWebCommand() {
        assertEquals(QqCommand.Kind.WEB, QqCommandParser.parse("fwweb").kind());
        assertEquals(QqCommand.Kind.WEB, QqCommandParser.parse("fwWEB").kind(), "大小写不敏感");
        assertEquals(QqCommand.Kind.WEB, QqCommandParser.parse("fwweb").kind(), "fw 前缀等价");
        assertEquals(QqCommand.Kind.WEB, QqCommandParser.parse("fw网址").kind());

        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("网址发我一下").kind(),
                "整条精确匹配 —— 群里问「网址多少」这种闲聊不会触发");
    }

    @Test
    @DisplayName("商品：买个X 是 1 件")
    void parseProduct_buyOneForm() {
        QqCommand command = QqCommandParser.parse("fw买个可乐 500ml");

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
        assertEquals(2, QqCommandParser.parse("fw买2个可乐").quantity());
        assertEquals("可乐", QqCommandParser.parse("fw买2个可乐").argument());
        assertEquals(2, QqCommandParser.parse("fw买两个可乐").quantity(),
                "「买两个可乐」才是群里最自然的说法 —— 只认阿拉伯数字会白挨一句「没认出」");
        assertEquals(10, QqCommandParser.parse("fw买十个手套").quantity());
        assertEquals("手套", QqCommandParser.parse("fw买3个 手套 ").argument(),
                "名字两边的空白要去掉");
    }

    @Test
    @DisplayName("商品：⚠️ 商品名里的「个」不能被当量词（量词取【第一个】个）")
    void parseProduct_usesFirstMeasureWord() {
        QqCommand command = QqCommandParser.parse("fw买2个个人杯");

        assertEquals("个人杯", command.argument(), "取最后一个「个」会把名字切坏");
        assertEquals(2, command.quantity());
    }

    @Test
    @DisplayName("商品：名-数量 那种简写也认，在最后一个横杠处切")
    void parseProduct_dashForm() {
        QqCommand command = QqCommandParser.parse("fw冰-红茶-2");

        assertEquals(QqCommand.Kind.PRODUCT_ORDER, command.kind());
        assertEquals("冰-红茶", command.argument());
        assertEquals(2, command.quantity());
        assertEquals(2, QqCommandParser.parse("fw可乐 500ml-2").quantity());
        assertEquals(2, QqCommandParser.parse("fw可乐－２").quantity(),
                "全角横杠与全角数字都要认 —— 中文输入法下打出来的就是它们");
    }

    @Test
    @DisplayName("商品：⚠️ 没带关键词也没带横杠的一律「没认出这条指令」")
    void parseProduct_requiresKeywordOrDash() {
        // 2026-10-04 与用户确认的改法：早先「fw可乐」也算下单一件，
        // 那意味着任何带前缀的未知文本都要当商品名查一遍 ——
        // 打错的指令会得到一句「没有叫「在店铺」的商品」，白白吓人一跳
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw可乐").kind());
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw可乐 500ml").kind(),
                "光写名字不算下单 —— 老老实实写数量");
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw买可乐").kind(),
                "少了量词「个」：宁可回「没认出」，也不替用户决定买几件");
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw买个").kind());
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw买2个").kind());
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw-2").kind(),
                "切出来名字为空 —— 不算下单");
    }

    @Test
    @DisplayName("商品：⚠️ 数量不做范围校验 —— 超范围也原样带出去")
    void parseProduct_doesNotValidateQuantityRange() {
        // 范围（1~99）是业务规则，由 Service 判 —— 放在解析器里的话，
        // 那句「单次最多买 99 件」就没有地方可写了，用户只会收到「没认出这条指令」
        assertEquals(100, QqCommandParser.parse("fw买100个可乐").quantity());
        assertEquals(0, QqCommandParser.parse("fw可乐-0").quantity());
    }

    @Test
    @DisplayName("商品：4 位以上数字不算数量（「可乐-2024」更可能是名字）")
    void parseProduct_treatsLongNumbersAsPartOfName() {
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw可乐-2024").kind());
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND,
                QqCommandParser.parse("fw买2024个可乐").kind());
    }

    @Test
    @DisplayName("商品：⚠️「买单」已不是结账的别名（2026-10-04 删）")
    void parseProduct_buyAliasRemoved() {
        // 它与商品下单的「买…个…」共用「买」字开头，两条语法混在一起容易被误用：
        // 看到 fw买单 能结账，就会以为 fw买可乐 也能下单。
        // 删掉之后它落到「没认出这条指令」—— 那是个正确的反馈，它确实不再是指令了
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw买单").kind());
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw买单").kind());
    }

    // ==================================================================
    // 调整库存（fw可乐5）—— 仅管理员，身份不在这里判，但格式的守卫全在这
    // ==================================================================

    @Test
    @DisplayName("库存：fw可乐5 是「把可乐的库存设为 5」")
    void parseStockAdjust_basicForm() {
        QqCommand command = QqCommandParser.parse("fw可乐5");

        assertEquals(QqCommand.Kind.STOCK_ADJUST, command.kind());
        assertEquals("可乐", command.argument());
        assertEquals(5, command.quantity(), "是新的库存值，不是增减量");
    }

    @Test
    @DisplayName("库存：名字里的空格、前缀后的空格、名字与数量之间的空格都认")
    void parseStockAdjust_toleratesSpacing() {
        assertEquals("王老吉 250ml", QqCommandParser.parse("fw王老吉 250ml3").argument(),
                "名字里的空格要原样留着 —— 商品名带空格是常事");
        assertEquals(5, QqCommandParser.parse("fw 可乐5").quantity(), "前缀后的空格不影响");
        assertEquals(5, QqCommandParser.parse("fw可乐 5").quantity(), "名字与数量之间也可以有空格");
    }

    @Test
    @DisplayName("库存：⚠️ 全角数字与商品下单同一套判定")
    void parseStockAdjust_acceptsFullWidthDigits() {
        assertEquals(5, QqCommandParser.parse("fw可乐５").quantity(),
                "⚠️ 两条路若用不同的数字判定，就会出现「fw可乐－２ 能买、"
                        + "fw可乐２ 改不了库存」这种同一个数字两种结果的分岔 —— 而它不报任何错");
    }

    @Test
    @DisplayName("库存：0 是合法值（改成 0 就是售罄）")
    void parseStockAdjust_allowsZero() {
        assertEquals(0, QqCommandParser.parse("fw可乐0").quantity(),
                "盘点时把一件商品清零是正常的事，不能当无效输入挡掉");
    }

    @Test
    @DisplayName("库存：⚠️ 4 位以上数字整条拒绝，不截断")
    void parseStockAdjust_rejectsLongNumbers() {
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw可乐2024").kind(),
                "⚠️ 截断写法会把它切成「名字『可乐2』、库存 4」——改错了商品，且不报任何错");
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw可乐-2024").kind(),
                "既有行为：横杠右边 4 位以上不算数量");
    }

    @Test
    @DisplayName("库存：⚠️ 名字是别的指令别名的一律不认（守住封闭集合）")
    void parseStockAdjust_reservesCommandNames() {
        // 不设这道守卫的话，打错的指令会被拿去查商品 —— 「fw在店2」会得到
        // 「没有叫「在店2」的商品」，而它本来该是「没认出这条指令」。
        // 2026-10-04 立下的「指令是封闭集合」就是被这类豁口破掉的
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw在店2").kind());
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw开门5").kind());
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw帮助1").kind());
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw菜单2").kind());
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fwping1").kind(),
                "英文别名也要挡 —— 只挡中文等于把豁口开在另一半");
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fwMENU2").kind(),
                "大小写不敏感，和别名表同一套小写");
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw验证 123").kind(),
                "不足 6 位的验证码不该被吞成「改『验证』的库存」");
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw买可乐5").kind(),
                "「买…」是下单语法的领地：打错的下单该回「没认出这条指令」，"
                        + "而不是一句「没有叫「买可乐」的商品」");
    }

    @Test
    @DisplayName("库存：⚠️ 名字只剩标点的残缺写法不认")
    void parseStockAdjust_requiresRealName() {
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw-2").kind(),
                "它是商品下单那条试剩下的残缺写法，不该被库存这条吃掉");
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw。。2").kind(),
                "名字里一个字母或数字都没有，不可能是一件商品");
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw 2").kind(),
                "名字为空");
    }

    @Test
    @DisplayName("库存：⚠️ 解析顺序 —— 横杠形式永远是商品下单，不会变成改库存")
    void parseStockAdjust_dashFormStaysProductOrder() {
        QqCommand buy = QqCommandParser.parse("fw可乐-2");

        assertEquals(QqCommand.Kind.PRODUCT_ORDER, buy.kind(),
                "⚠️ 顺序反了的话，「买 2 件可乐」会静默变成「把『可乐-』的库存设成 2」，"
                        + "而两条路都不会报错");
        assertEquals(2, buy.quantity());
    }

    @Test
    @DisplayName("库存：名字里带横杠照样能改（横杠右边不是纯数字时，商品那条落空）")
    void parseStockAdjust_allowsDashInsideName() {
        QqCommand adjust = QqCommandParser.parse("fw冰-红茶2");

        assertEquals(QqCommand.Kind.STOCK_ADJUST, adjust.kind(),
                "商品下单那条先试过：「红茶2」不是纯数字，故落空");
        assertEquals("冰-红茶", adjust.argument(), "横杠留在名字里");

        assertEquals(QqCommand.Kind.PRODUCT_ORDER, QqCommandParser.parse("fw冰-红茶-2").kind(),
                "要买 2 件「冰-红茶」得在末尾补一个横杠 —— 两条路的区别只有一个分隔符");
    }

    // ==================================================================
    // 调整机台状况（fw拍拍机 1 号维护中）
    // ==================================================================

    @Test
    @DisplayName("解析：fw[机台名]维护中|待维护|良好 识别为机台状况指令")
    void parse_recognizesDeviceStatus() {
        QqCommand maintain = QqCommandParser.parse("fw拍拍机 1 号维护中");
        assertEquals(QqCommand.Kind.DEVICE_STATUS, maintain.kind());
        assertEquals("拍拍机 1 号", maintain.argument());
        assertEquals("MAINTAINING", maintain.status(),
                "中文说法在解析时按 DeviceStatus 翻成枚举名 —— 解析器不另抄一份状况清单");

        assertEquals("NEEDS_REPAIR", QqCommandParser.parse("fw拍拍机 1 号待维护").status());
        assertEquals("NORMAL", QqCommandParser.parse("fw拍拍机 1 号良好").status());
        assertEquals("拍拍机 1 号", QqCommandParser.parse("fw 拍拍机 1 号 良好").argument(),
                "前缀后、名字后的空格都 trim 掉");
        assertEquals("1 号机", QqCommandParser.parse("fw1 号机维护中").argument(),
                "名字里的空格与数字原样保留");
    }

    @Test
    @DisplayName("解析：机台状况的两道守卫 —— 打错的指令不能被错怪成机台名")
    void parse_deviceStatusGuards() {
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw维护中").kind(),
                "只发状况词：名字为空，它不是一条指令 —— "
                        + "但带着前缀，该给提示而不是静默");
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw在店维护中").kind(),
                "「在店」是别的指令的别名 —— 打错的指令该得到打错的反馈，"
                        + "而不是一句「没有这台机台」");
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw。。维护中").kind(),
                "名字只剩标点：与改库存那条共用同一道守卫");
        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("这台机器维护中").kind(),
                "没前缀的照旧是闲聊，静默");
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
                QqCommandParser.parse("可用指令（前缀 fw，例如 fw在店）：\n【管理】\nfw可乐5 —— 把该商品的库存调整为 5（仅管理员）").kind(),
                "帮助文案里虽然含 fw可乐5 字样（那正是一条指令的样子），"
                        + "但整条不以 fw 开头、整条也不相等，不该被认成指令");
        assertEquals(QqCommand.Kind.IGNORE,
                QqCommandParser.parse("已调整「可乐 500ml」库存：20 件 → 5 件。").kind(),
                "库存回复也是机器人自己发的 —— 它若能被解析成指令，就是一次自问自答");
        assertEquals(QqCommand.Kind.IGNORE,
                QqCommandParser.parse("「调整库存」仅限管理员使用。如需购买商品，请发送 fw买个可乐 或 fw可乐-2。").kind(),
                "⚠️ 拒绝文案里带着 fw买个可乐 字样，但它整条不以 fw 开头 —— 这条守的正是回环防线");
        assertEquals(QqCommand.Kind.IGNORE,
                QqCommandParser.parse("门锁密码：123456\n用一次即作废，请勿转发。").kind(),
                "⚠️ 开门回复里那串密码绝不能反过来触发什么");
        assertEquals(QqCommand.Kind.IGNORE,
                QqCommandParser.parse("已停止计时，本次应付 ¥12.00。\n去支付：http://localhost:5173/#/orders/1/settle").kind());
        assertEquals(QqCommand.Kind.IGNORE,
                QqCommandParser.parse("已将「拍拍机 1 号」的状况由「良好」改为「维护中」，门店公告已同步更新。").kind(),
                "机台状况回复也是机器人自己发的 —— 出现「维护中」三个字不代表它是一条指令");
        assertEquals(QqCommand.Kind.IGNORE,
                QqCommandParser.parse("「调整机台状况」仅限管理员使用。如需查看机台信息，请前往网页端。").kind(),
                "拒绝文案里带着指令名，整条却不以 fw 开头 —— 与库存那条同一道防线");
    }

    @Test
    @DisplayName("回环：验证码绝不能落到闲聊上")
    void parse_verifyCodeIsNeverIgnored() {
        // ⚠️ 这条是踩过坑才加的。曾经「自己发的消息」按「有没有前缀」过滤，
        // 而那时的验证码不带前缀 —— 于是同号登录 bot 时，运营者在手机上发的验证码
        // 被当成「机器人自己发的话」挡掉：取码正常、发群正常，
        // 但【验证永远通不过，且后端与群里都没有任何报错】。
        // 判据换成「解析结果是不是 IGNORE」之后，这条断言就是那道防线的锚点。
        assertEquals(QqCommand.Kind.VERIFY_CODE, QqCommandParser.parse("fw验证 253106").kind());
        assertNotEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("fw验证 253106").kind());
    }

    // ==================================================================
    // 未付款查询与取消（2026-10-10）
    // ==================================================================

    @Test
    @DisplayName("解析：fw未付款 / fw欠费 / fwunpaid 都是查未付款单")
    void parse_recognizesUnpaidBills() {
        assertEquals(QqCommand.Kind.UNPAID_BILLS, QqCommandParser.parse("fw未付款").kind());
        assertEquals(QqCommand.Kind.UNPAID_BILLS, QqCommandParser.parse("fw欠费").kind());
        assertEquals(QqCommand.Kind.UNPAID_BILLS, QqCommandParser.parse("fwUNPAID").kind());
        assertEquals(QqCommand.Kind.UNPAID_BILLS, QqCommandParser.parse("fw 欠费").kind());

        assertEquals(QqCommand.Kind.IGNORE, QqCommandParser.parse("未付款").kind(),
                "没有前缀仍是闲聊 —— 群里一句「我未付款呢」不该触发查询");
    }

    @Test
    @DisplayName("解析：fw取消 <单号>，单号统一转大写")
    void parse_recognizesCancelOrder() {
        assertEquals(QqCommand.Kind.CANCEL_ORDER,
                QqCommandParser.parse("fw取消 PD202610101430251739").kind());
        assertEquals("PD202610101430251739",
                QqCommandParser.parse("fw取消 PD202610101430251739").argument());
        assertEquals("MC202610101430251739",
                QqCommandParser.parse("fw取消 mc202610101430251739").argument(),
                "小写要归一成大写 —— 库里存的是大写");
        assertEquals("BK202610101430251739",
                QqCommandParser.parse("fw取消BK202610101430251739").argument(),
                "关键词与单号之间没有空格也要认");
        assertEquals("OD202610101430251739",
                QqCommandParser.parse("fwcancel od202610101430251739").argument(),
                "英文关键词同样认");
    }

    @Test
    @DisplayName("解析：⚠️ 取消的单号做形状校验 —— 不像单号的一律落回「没认出」")
    void parse_cancelRequiresOrderNoShape() {
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw取消").kind(),
                "缺参数与 fw验证 缺码同待遇");
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw取消一下").kind(),
                "中文不该被拿去查单号");
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND, QqCommandParser.parse("fw取消2").kind(),
                "⚠️ 极短的数字不是单号 —— 少了长度下限，它会静默变成一条「取消单号 2」的指令");
        assertEquals(QqCommand.Kind.UNKNOWN_COMMAND,
                QqCommandParser.parse("fw取消 PD20261010 1430251739").kind(),
                "带空格的两段不是单号");
    }

    @Test
    @DisplayName("回环：未付款清单与取消回复都不能解析成指令（含 fw取消 字样的那条）")
    void parse_unpaidAndCancelRepliesAreIgnored() {
        assertEquals(QqCommand.Kind.IGNORE,
                QqCommandParser.parse("已取消未付款的商品 PD202610101200009012，占用的库存已释放。").kind(),
                "取消回复是机器人自己发的 ——「取消」在句子中间不是关键词");
        assertEquals(QqCommand.Kind.IGNORE,
                QqCommandParser.parse("你有 1 笔未付款的单子：\n· [计时] OD2026 · 待支付\n"
                        + "付款请前往网页端：http://localhost:5173/#/orders\n"
                        + "不需要的单子可发送 fw取消 <单号> 取消。").kind(),
                "⚠️ 清单末尾带 fw取消 字样 —— 整条不以 fw 开头，必须仍是闲聊，"
                        + "否则机器人会回复自己、无限循环");
    }
}
