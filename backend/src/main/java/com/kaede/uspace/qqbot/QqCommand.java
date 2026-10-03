package com.kaede.uspace.qqbot;

/**
 * 一条从群消息里解析出来的指令（模块 11）。
 *
 * <p>解析与执行<b>刻意分开</b>：{@link QqCommandParser} 只做「文本 → 对象」这一件事，
 * 是纯静态、不依赖 Spring、不查库的，因此可以脱离容器单测 ——
 * 而指令识别恰恰是最该被测试覆盖的地方（认错了不会报错，只会答非所问）。
 *
 * @param kind     指令种类
 * @param argument 附加参数。目前只有 {@link Kind#VERIFY_CODE} 用得上（存那 6 位数字），
 *                 其余为 null
 */
public record QqCommand(Kind kind, String argument) {

    /**
     * 指令种类。
     *
     * <p>⚠️ <b>{@link #UNKNOWN_COMMAND} 与 {@link #IGNORE} 的区别是全套设计里最容易写错的一处</b>：
     * 群消息里的绝大多数内容都是闲聊，<b>必须静默丢弃</b>；
     * 而「看着像在跟机器人说话、但机器人不认识」的那一类要给个提示，
     * 否则用户打错一个字符就得不到任何反馈，只会以为机器人坏了。
     *
     * <p>判据取「有没有 {@code /} 前缀」：{@code /} 在群聊语境里就是「我在跟机器人说话」，
     * 而「我在店里」这种闲聊不会带它。
     */
    public enum Kind {

        /** 查在店名册（{@code /在店}）*/
        INSTORE,

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

        /** 看着像指令（带 {@code /} 前缀）但不认识 —— <b>要回一句提示</b> */
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
