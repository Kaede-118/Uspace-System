package com.kaede.uspace.qqbot;

/**
 * 一条从群消息里解析出来的指令（模块 11）。
 *
 * <p>解析与执行<b>刻意分开</b>：{@link QqCommandParser} 只做「文本 → 对象」这一件事，
 * 是纯静态、不依赖 Spring、不查库的，因此可以脱离容器单测 ——
 * 而指令识别恰恰是最该被测试覆盖的地方（认错了不会报错，只会答非所问）。
 *
 * @param kind     指令种类
 * @param argument 附加参数。{@link Kind#VERIFY_CODE} 存那 6 位数字，
 *                 {@link Kind#PRODUCT_ORDER} 存商品名（<b>还没查过库</b>，可能不存在），
 *                 其余为 null
 * @param quantity 附加数量，只有 {@link Kind#PRODUCT_ORDER} 用得上。
 *                 <b>null 表示「买个X」那种写法</b>（量词本身就说明了数量是 1 件），
 *                 非 null 则是他写明的数量（可能超范围，由业务侧判）
 */
public record QqCommand(Kind kind, String argument, Integer quantity) {

    /**
     * 构造一条既没有参数、也没有数量的指令。
     *
     * <p>十几条指令里只有两条带附加信息，其余都是「一个 kind 说清一切」——
     * 让它们少写两个 null。
     *
     * @param kind     指令种类
     * @param argument 附加参数，可为 null
     */
    public QqCommand(Kind kind, String argument) {
        this(kind, argument, null);
    }

    /**
     * 指令种类。
     *
     * <p>⚠️ <b>{@link #UNKNOWN_COMMAND} 与 {@link #IGNORE} 的区别是全套设计里最容易写错的一处</b>：
     * 群消息里的绝大多数内容都是闲聊，<b>必须静默丢弃</b>；
     * 而「看着像在跟机器人说话、但机器人不认识」的那一类要给个提示，
     * 否则用户打错一个字符就得不到任何反馈，只会以为机器人坏了。
     *
     * <p>判据是<b>有没有前缀</b>（{@code /} 或 {@code fw}）：前缀在群聊语境里
     * 就是「我在跟机器人说话」，而「我在店里」这种闲聊不会带它。
     * <b>加了写指令之后这一条变得更要紧</b> —— {@code /开门} 会真的建单计费，
     * 群里有人喊一声「开门」若被认成指令，就是一笔白扣的钱，而且当场没人会发现。
     */
    public enum Kind {

        /** 查在店名册（{@code /在店}）*/
        INSTORE,

        /**
         * 建单开门计时（{@code /开门}）—— <b>写指令</b>。
         *
         * <p>它做三件事：按发送者的 QQ 找到账号、建一笔订单（已有进行中的就复用）、
         * 把一串一次性门锁密码发到群里。编排在 {@code QqWriteCommandService}。
         */
        OPEN_DOOR,

        /**
         * 停止计时并结算（{@code /结账}）—— <b>写指令</b>。
         *
         * <p>结算后回一条 Web 结算页链接，<b>付款仍然只在网页端发生</b>。
         */
        SETTLE,

        /** 查近期包场时间表（{@code /包场}） */
        BOOKING_SCHEDULE,

        /** 查自己的资料与消费（{@code /看看自己}） */
        ME,

        /**
         * 看当前这一单（{@code /now}、{@code /当前订单}）。
         *
         * <p><b>只预览，不停表</b> —— 与网页端「结账」按钮点进去看到的那一屏同源
         * （{@code OrderPreviewVo}，零副作用：计时照走、状态不变、绝不撤销密码）。
         * 想真的结账要再发 {@code /结账}，两个动作分开是为了防止
         * 「只是想看一眼多少钱，结果把表停了」。
         */
        CURRENT_ORDER,

        /** 查门店营业状态（{@code /营业}） */
        STORE_STATUS,

        /** 查计费规则摘要（{@code /价格}） */
        PRICE,

        /**
         * 查网页端地址（{@code /web}）。
         *
         * <p>群里新来的人第一句常问「在哪儿下单」，而答案永远是一个网址 ——
         * 它是唯一一条<b>给地址而不是给信息</b>的指令。
         * 地址原样取自 {@code uspace.web.base-url}（与包场邀请链接同一项）：
         * 本机开发时要把它写成局域网地址，留 {@code localhost} 的话
         * 别人点开只会看到自己的手机。
         */
        WEB,

        /**
         * 查商城商品与价格（{@code /菜单}）。
         *
         * <p>别名给 {@code menu} —— 群里问「有菜单吗」比「有商品吗」自然。
         * 只列上架中的商品，售罄的照列但标出来（顾客可能想问什么时候补货）。
         */
        PRODUCT_MENU,

        /**
         * 下单买商品（{@code /买个可乐}、{@code /买2个可乐}、{@code /可乐-2}）—— <b>写指令</b>。
         *
         * <p>{@code argument} 里装的是顾客打出来的商品名，<b>解析器不查库、
         * 也不知道这个名字存不存在</b> —— 名字能不能对上由
         * {@code QqWriteCommandService} 去问商品模块。这么切是为了让解析器保持纯静态
         * （见类注释），代价是「商品不存在」这个判断只能发生在 Service 里。
         *
         * <p>⚠️ 它<b>不是开放式的</b>：只认带关键词或带横杠的写法（两种都显式写着数量），
         * 所以打错的指令仍然落到 {@link #UNKNOWN_COMMAND}，不会被错怪成商品。
         */
        PRODUCT_ORDER,

        /** 显示指令列表（{@code /帮助}）*/
        HELP,

        /**
         * 连通性自检（{@code /ping} → 回 {@code pong}）。
         *
         * <p>它存在的意义是<b>把「链路通不通」与「业务逻辑对不对」分开</b>：
         * 机器人不说话时，先发一条 {@code /ping} —— 回 {@code pong} 说明
         * 连接、鉴权、群白名单、幂等、出站这五环都是好的，问题在具体指令上；
         * 不回则说明问题在前面那五环里，排查范围一下就缩小了。
         */
        PING,

        /** 6 位验证码 —— 用户在注册页取码后发到群里，用来把 QQ 号绑到账号上 */
        VERIFY_CODE,

        /**
         * 看着像指令（带 {@code /} 前缀）但不认识 —— <b>要回一句提示</b>。
         *
         * <p>加了商品下单之后它的管辖范围小了一圈：带前缀的文本<b>先当商品名试一次</b>
         * （见 {@link #PRODUCT_ORDER}），只有<b>压根没有名字可试</b>（只发了个 {@code /}）
         * 才落到这里。商品名试不上的那一半，由 Service 回同一句话 ——
         * 两处的文案因此共用 {@code QqReplyText} 里的一份，不要各写各的。
         */
        UNKNOWN_COMMAND,

        /** 不是指令，是闲聊 —— <b>必须静默</b>，回一句就会把群刷爆 */
        IGNORE
    }

    /**
     * 构造「查在店名册」指令。
     *
     * @return 指令
     */
    public static QqCommand instore() {
        return new QqCommand(Kind.INSTORE, null);
    }

    /**
     * 构造「开门计时」指令。
     *
     * @return 指令
     */
    public static QqCommand openDoor() {
        return new QqCommand(Kind.OPEN_DOOR, null);
    }

    /**
     * 构造「结账」指令。
     *
     * @return 指令
     */
    public static QqCommand settle() {
        return new QqCommand(Kind.SETTLE, null);
    }

    /**
     * 构造「查包场时间表」指令。
     *
     * @return 指令
     */
    public static QqCommand bookingSchedule() {
        return new QqCommand(Kind.BOOKING_SCHEDULE, null);
    }

    /**
     * 构造「看看自己」指令。
     *
     * @return 指令
     */
    public static QqCommand me() {
        return new QqCommand(Kind.ME, null);
    }

    /**
     * 构造「看当前订单」指令。
     *
     * @return 指令
     */
    public static QqCommand currentOrder() {
        return new QqCommand(Kind.CURRENT_ORDER, null);
    }

    /**
     * 构造「查营业状态」指令。
     *
     * @return 指令
     */
    public static QqCommand storeStatus() {
        return new QqCommand(Kind.STORE_STATUS, null);
    }

    /**
     * 构造「查价格」指令。
     *
     * @return 指令
     */
    public static QqCommand price() {
        return new QqCommand(Kind.PRICE, null);
    }

    /**
     * 构造「查网页端地址」指令。
     *
     * @return 指令
     */
    public static QqCommand web() {
        return new QqCommand(Kind.WEB, null);
    }

    /**
     * 构造「查商城菜单」指令。
     *
     * @return 指令
     */
    public static QqCommand productMenu() {
        return new QqCommand(Kind.PRODUCT_MENU, null);
    }

    /**
     * 构造「下单买商品」指令。
     *
     * @param name     商品名（<b>原样带出来，尚未与商品表核对过</b>）
     * @param quantity 他写明的数量；<b>「买个X」那种写法传 null</b>（量词即为 1 件）。
     *                 解析器不做范围校验，所以这里也可能是 0 或 100 —— 见 {@link #quantity}
     * @return 指令
     */
    public static QqCommand productOrder(String name, Integer quantity) {
        return new QqCommand(Kind.PRODUCT_ORDER, name, quantity);
    }

    /**
     * 构造「显示帮助」指令。
     *
     * @return 指令
     */
    public static QqCommand help() {
        return new QqCommand(Kind.HELP, null);
    }

    /**
     * 构造「连通性自检」指令。
     *
     * @return 指令
     */
    public static QqCommand ping() {
        return new QqCommand(Kind.PING, null);
    }

    /**
     * 构造「验证码确认」指令。
     *
     * @param code 6 位验证码
     * @return 指令
     */
    public static QqCommand verifyCode(String code) {
        return new QqCommand(Kind.VERIFY_CODE, code);
    }

    /**
     * 构造「像指令但不认识」结果。
     *
     * @return 指令
     */
    public static QqCommand unknownCommand() {
        return new QqCommand(Kind.UNKNOWN_COMMAND, null);
    }

    /**
     * 构造「不是指令」结果。
     *
     * @return 指令
     */
    public static QqCommand ignore() {
        return new QqCommand(Kind.IGNORE, null);
    }
}
