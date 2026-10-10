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
 *                 {@link Kind#PRODUCT_ORDER} 与 {@link Kind#STOCK_ADJUST} 存商品名、
 *                 {@link Kind#DEVICE_STATUS} 存机台名
 *                 （名字都<b>还没查过库</b>，可能不存在），
 *                 其余为 null
 * @param quantity 附加数量，{@link Kind#PRODUCT_ORDER} 与 {@link Kind#STOCK_ADJUST} 用得上。
 *                 <b>下单那边</b>：null 表示「买个X」那种写法（量词本身就说明了数量是 1 件），
 *                 非 null 则是他写明的数量（可能超范围，由业务侧判）；
 *                 <b>改库存那边</b>：它是<b>新的库存值</b>，且永不 null
 * @param status   附加状况名，{@link Kind#DEVICE_STATUS} 用得上 ——
 *                 装的是 {@code DeviceStatus} 的<b>枚举名</b>（如 {@code MAINTAINING}），
 *                 中文说法在<b>解析时</b>就按枚举里的中文名翻好了（见 {@code QqCommandParser}），
 *                 其余指令为 null
 */
public record QqCommand(Kind kind, String argument, Integer quantity, String status) {

    /**
     * 构造一条带数量、但没有状况的指令。
     *
     * <p>保留这个构造器，是为了两条已有的带数量指令（下单、改库存）
     * 不必多写第四个 {@code null} —— 新增的 {@code status} 只服务机台那一条。
     *
     * @param kind     指令种类
     * @param argument 附加参数，可为 null
     * @param quantity 附加数量，可为 null
     */
    public QqCommand(Kind kind, String argument, Integer quantity) {
        this(kind, argument, quantity, null);
    }

    /**
     * 构造一条既没有参数、也没有数量的指令。
     *
     * <p>十几条指令里只有少数带附加信息，其余都是「一个 kind 说清一切」——
     * 让它们少写几个 null。
     *
     * @param kind     指令种类
     * @param argument 附加参数，可为 null
     */
    public QqCommand(Kind kind, String argument) {
        this(kind, argument, null, null);
    }

    /**
     * 指令种类。
     *
     * <p>⚠️ <b>{@link #UNKNOWN_COMMAND} 与 {@link #IGNORE} 的区别是全套设计里最容易写错的一处</b>：
     * 群消息里的绝大多数内容都是闲聊，<b>必须静默丢弃</b>；
     * 而「看着像在跟机器人说话、但机器人不认识」的那一类要给个提示，
     * 否则用户打错一个字符就得不到任何反馈，只会以为机器人坏了。
     *
     * <p>判据是<b>有没有前缀</b>（{@code fw}，2026-10-09 起它是唯一的一种）：
     * 前缀在群聊语境里就是「我在跟机器人说话」，而「我在店里」这种闲聊不会带它。
     * <b>加了写指令之后这一条变得更要紧</b> —— {@code fw开门} 会真的建单计费，
     * 群里有人喊一声「开门」若被认成指令，就是一笔白扣的钱，而且当场没人会发现。
     */
    public enum Kind {

        /** 查在店名册（{@code fw在店}）*/
        INSTORE,

        /**
         * 建单开门计时（{@code fw开门}）—— <b>写指令</b>。
         *
         * <p>它做三件事：按发送者的 QQ 找到账号、建一笔订单（已有进行中的就复用）、
         * 把一串一次性门锁密码发到群里。编排在 {@code QqWriteCommandService}。
         */
        OPEN_DOOR,

        /**
         * 停止计时并结算（{@code fw结账}）—— <b>写指令</b>。
         *
         * <p>结算后回一条 Web 结算页链接，<b>付款仍然只在网页端发生</b>。
         */
        SETTLE,

        /**
         * 取消一笔未付款的单（{@code fw取消 <单号>}）—— <b>写指令</b>。
         *
         * <p>只受理<b>商品单、月卡购买单、未付款包场</b>这三类 ——
         * 计时订单（{@code OD} 前缀）一律拒绝，理由见
         * {@code QqWriteCommandService#cancelOrder}：正在计时该走 {@code fw结账}、
         * 已出账要前往网页端付款，而<b>欠费不能靠一句指令自消</b>
         * （那是这个系统里最不能开的口子）。
         *
         * <p>{@code argument} 装的是单号<b>原文</b>（解析时已转大写）——
         * 它属于哪一类由 {@code PaymentTargetType.fromOrderNo} 按前缀路由，
         * 解析器不查库、也不知道这个单号存不存在。
         */
        CANCEL_ORDER,

        /** 查近期包场时间表（{@code fw包场}） */
        BOOKING_SCHEDULE,

        /** 查自己的资料与消费（{@code fw看看自己}） */
        ME,

        /**
         * 看当前这一单（{@code fwnow}、{@code fw当前订单}）。
         *
         * <p><b>只预览，不停表</b> —— 与网页端「结账」按钮点进去看到的那一屏同源
         * （{@code OrderPreviewVo}，零副作用：计时照走、状态不变、绝不撤销密码）。
         * 想真的结账要再发 {@code fw结账}，两个动作分开是为了防止
         * 「只是想看一眼多少钱，结果把表停了」。
         */
        CURRENT_ORDER,

        /**
         * 查本人全部未付款的单（{@code fw未付款}）。
         *
         * <p>把四类收款里「还没付钱的那几笔」一次列全：计时订单
         * （{@code PENDING_PAYMENT} 与 {@code REJECTED}）、商品单、
         * 月卡购买单、未付款包场。<b>不含正在计时的单</b> ——
         * 那一分钱还没算出来，不是「未付款」，看它的是 {@link #CURRENT_ORDER}。
         *
         * <p>下一步动作（去付款、重新上传凭证）都指路网页端；
         * 不需要的单子可以用 {@link #CANCEL_ORDER} 取消（计时订单除外）。
         */
        UNPAID_BILLS,

        /** 查门店营业状态（{@code fw营业}） */
        STORE_STATUS,

        /** 查计费规则摘要（{@code fw价格}） */
        PRICE,

        /**
         * 查月卡说明（{@code fw月卡}、{@code fwpass}）。
         *
         * <p>它是 {@link #PRICE} 的<b>补充而不是重复</b>：{@code fw价格} 讲的是
         * 「按时长怎么算钱」，月卡是另一种买法（包月），两者并列、各自才说得清。
         * 在此之前群里问「月卡多少钱」机器人完全答不上来 —— 而月卡是店里
         * 客单价最高的一项。
         */
        CARD_TYPES,

        /**
         * 查网页端地址（{@code fwweb}）。
         *
         * <p>群里新来的人第一句常问「在哪儿下单」，而答案永远是一个网址 ——
         * 它是唯一一条<b>给地址而不是给信息</b>的指令。
         * 地址原样取自 {@code uspace.web.base-url}（与包场邀请链接同一项）：
         * 本机开发时要把它写成局域网地址，留 {@code localhost} 的话
         * 别人点开只会看到自己的手机。
         */
        WEB,

        /**
         * 查商城商品与价格（{@code fw菜单}）。
         *
         * <p>别名给 {@code menu} —— 群里问「有菜单吗」比「有商品吗」自然。
         * 只列上架中的商品，售罄的照列但标出来（顾客可能想问什么时候补货）。
         */
        PRODUCT_MENU,

        /**
         * 下单买商品（{@code fw买个可乐}、{@code fw买2个可乐}、{@code fw可乐-2}）—— <b>写指令</b>。
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

        /**
         * 调整商品库存（{@code fw可乐5}）—— <b>写指令，且仅管理员可用</b>。
         *
         * <p>{@code argument} 装商品名、{@code quantity} 装<b>新的库存值</b>
         * （不是增减量）—— 与后台 {@code PUT /api/admin/products/{id}} 的
         * {@code stock} 字段同义：盘点后把数字改成实际数量。
         *
         * <p>⚠️ <b>它与商品下单长得极像</b>（都是「名字 + 数字」），靠<b>分隔符</b>
         * 分开：{@code fw可乐5} 是改库存、{@code fw可乐-2} 是买 2 件。
         * 解析时<b>先试商品下单、再试本条</b> —— 顺序反了会把「买 2 件可乐」
         * 静默变成「把『可乐-』的库存设成 2」，而那不报任何错。
         *
         * <p>⚠️ 它还是全项目<b>唯一按身份限制的群指令</b>：解析层不认人
         * （它必须保持纯静态，见类注释），由 {@code QqWriteCommandService}
         * 查出 {@code sys_user.role} 之后拒绝非管理员。
         */
        STOCK_ADJUST,

        /**
         * 调整机台状况（{@code fw拍拍机 1 号维护中}）—— <b>写指令，且仅管理员可用</b>。
         *
         * <p>{@code argument} 装机台名、{@code status} 装目标状况的<b>枚举名</b>
         * （如 {@code MAINTAINING}）：中文说法在解析时按 {@code DeviceStatus}
         * 的三个中文名翻好，解析器不另抄一份状况清单 —— 枚举里加一态，
         * 指令自动跟着认。
         *
         * <p>⚠️ <b>机台名没有唯一键</b>（唯一的是「门店 + 资产编号」）：
         * 同名多台时执行侧会拒绝并让管理员走网页端 —— 猜着改会改错机器，
         * 而群里那条回复看起来一切正常。
         *
         * <p>与 {@link #STOCK_ADJUST} 一样，解析层不认人
         * （它必须保持纯静态，见类注释），是不是管理员由
         * {@code QqWriteCommandService} 查库判。
         */
        DEVICE_STATUS,

        /** 显示指令列表（{@code fw帮助}）*/
        HELP,

        /**
         * 连通性自检（{@code fwping} → 回 {@code pong}）。
         *
         * <p>它存在的意义是<b>把「链路通不通」与「业务逻辑对不对」分开</b>：
         * 机器人不说话时，先发一条 {@code fwping} —— 回 {@code pong} 说明
         * 连接、鉴权、群白名单、幂等、出站这五环都是好的，问题在具体指令上；
         * 不回则说明问题在前面那五环里，排查范围一下就缩小了。
         */
        PING,

        /** 6 位验证码 —— 用户在注册页取码后发到群里，用来把 QQ 号绑到账号上 */
        VERIFY_CODE,

        /**
         * 看着像指令（带 {@code fw} 前缀）但不认识 —— <b>要回一句提示</b>。
         *
         * <p>它的管辖范围被前面几条收窄过：带前缀的文本会<b>先当商品名试一次</b>
         * （见 {@link #PRODUCT_ORDER}）、<b>再当库存指令试一次</b>
         * （见 {@link #STOCK_ADJUST}）、<b>最后当机台状况试一次</b>
         * （见 {@link #DEVICE_STATUS}），几处都没认出来才落到这里 ——
         * 认出了格式但名字对不上的那一半，由 Service 回一句「没有这个商品」，
         * 文案与这里共用 {@code QqReplyText} 里的一份，不要各写各的。
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
     * 构造「取消未付款单」指令。
     *
     * @param orderNo 单号（解析器已转大写，<b>尚未与任何模块核对过</b>）
     * @return 指令
     */
    public static QqCommand cancelOrder(String orderNo) {
        return new QqCommand(Kind.CANCEL_ORDER, orderNo);
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
     * 构造「查未付款单」指令。
     *
     * @return 指令
     */
    public static QqCommand unpaidBills() {
        return new QqCommand(Kind.UNPAID_BILLS, null);
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
     * 构造「查月卡说明」指令。
     *
     * @return 指令
     */
    public static QqCommand cardTypes() {
        return new QqCommand(Kind.CARD_TYPES, null);
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
     * 构造「调整库存」指令。
     *
     * @param name  商品名（<b>原样带出来，尚未与商品表核对过</b>）
     * @param stock 新的库存值。解析器只保证它是 1~3 位数字，
     *              「这个数合不合理」归商品模块判
     * @return 指令
     */
    public static QqCommand stockAdjust(String name, Integer stock) {
        return new QqCommand(Kind.STOCK_ADJUST, name, stock);
    }

    /**
     * 构造「调整机台状况」指令。
     *
     * @param name   机台名（<b>原样带出来，尚未与机台表核对过</b>，也可能撞上同名多台）
     * @param status 目标状况的枚举名（如 {@code MAINTAINING}）——
     *               由解析器按 {@code DeviceStatus} 的中文名翻好，这里不再校验
     * @return 指令
     */
    public static QqCommand deviceStatus(String name, String status) {
        return new QqCommand(Kind.DEVICE_STATUS, name, null, status);
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
