package com.kaede.uspace.common.trade;

import java.util.Arrays;

/**
 * 交易流水的来源渠道 —— 记录「这笔账是哪条路上发生的」。
 *
 * <p><b>它为什么住在公共层，而不是 order 包</b>：写这笔账的不止订单模块。
 * 取消未付款单这件事发生在三个包里（商品的取消在 {@code product}、
 * 月卡购买单的取消在 {@code promotion}、包场的取消在 {@code space}），
 * 而每一次都要在流水上写明是网页端发起的还是群里发起的。
 * 把它放进 {@code order}，那三个包就得反向 import {@code order} ——
 * 而业务包之间的依赖方向是单向的（见 CLAUDE.md「代码组织」），
 * 于是要么成环、要么三个包各抄一份枚举。
 *
 * <p>这与 {@code ErrorCode}、{@code ImageStorage} 住在公共层是<b>同一个理由</b>：
 * 多个业务包共用的东西，放进任何一个业务包都会逼出反向依赖。
 * （{@code ImageStorage} 那条理由写在 CLAUDE.md 的「工程结构」一节里，
 * 措辞几乎一模一样 —— 那时被两个包共用，这里被四个。）
 *
 * <p>取值是封闭的四种。库里存的这一列是 {@code VARCHAR}，没有库级约束兜底，
 * 改这里的名字之前先想清楚：<b>历史流水里存的是旧名字</b>。
 */
public enum TradeSource {

    /** 网页端 —— 用户或管理员在页面上点的 */
    WEB("网页端"),

    /** QQ 群内 —— 群里那几条写指令发起的 */
    QQ("群内"),

    /** 运营后台 —— 管理员点的（与「网页端」分开，因为要看得出是顾客还是店家） */
    ADMIN("管理后台"),

    /**
     * 系统自动 —— 没有具体操作人的动作。
     *
     * <p>如线上支付通道的回调落账（没人「点」它，是平台推过来的）。
     */
    SYSTEM("系统自动");

    /** 面向人的中文说明，供后台展示 */
    private final String label;

    TradeSource(String label) {
        this.label = label;
    }

    /**
     * 取中文说明。
     *
     * @return 渠道的中文名称
     */
    public String getLabel() {
        return label;
    }

    /**
     * 判断一个字符串是否为合法取值。
     *
     * <p>与 {@code DeviceStatus} 同一套写法：取值清单的权威定义在枚举里，
     * 不另写一份正则 —— 两份清单迟早会在某次新增上分岔。
     *
     * @param name 待校验的名字，可为 null
     * @return 合法返回 true；null 或不在枚举内返回 false
     */
    public static boolean isValid(String name) {
        return name != null && Arrays.stream(values()).anyMatch(s -> s.name().equals(name));
    }

    /**
     * 把名字转成中文说明。
     *
     * <p>认不出的取值<b>原样返回</b>，让异常数据在界面上一眼看得出来 ——
     * 与 {@code DeviceStatus#labelOf} 同一条纪律。
     *
     * @param name 名字，可为 null
     * @return 中文说明；认不出时原样返回
     */
    public static String labelOf(String name) {
        return isValid(name) ? valueOf(name).getLabel() : name;
    }
}
