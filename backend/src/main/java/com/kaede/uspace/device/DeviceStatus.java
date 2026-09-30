package com.kaede.uspace.device;

import java.util.Arrays;

/**
 * 机台状况。
 *
 * <p>取值与建表脚本里 {@code biz_device.status} 的注释一一对应。
 * 三态按<b>「顾客还能不能玩」</b>划分，而不是按维修流程走到了哪一步：
 *
 * <pre>
 *   NORMAL       良好      能玩，且没毛病
 *   NEEDS_REPAIR 待维护    能玩，但有小毛病（某个键不灵、屏幕有划痕…）
 *   MAINTAINING  维护中    不能玩（正在修，或停用等配件）
 * </pre>
 *
 * <p><b>「待维护」刻意不并进「维护中」</b>：前者是如实告诉顾客「这台有点小问题，
 * 不介意可以玩」，后者是「这台现在别碰」。合成一个「异常」态的话，
 * 顾客看到标签不知道能不能上手，运营也没法靠它判断要不要劝阻。
 *
 * <p><b>本状态不影响准入</b>：能不能进店只由停业（{@code biz_closure}）与
 * 包场（{@code biz_booking}）决定。哪怕全店机台都标成维护中，系统照样放人进门 ——
 * 真要拦住顾客，排一条停业区间，而不是改机台状态。
 */
public enum DeviceStatus {

    /** 良好。正常可用，没有已知问题 */
    NORMAL("良好"),

    /** 待维护。有小毛病但尚可使用，如实告知顾客 */
    NEEDS_REPAIR("待维护"),

    /** 维护中。当前不可使用（正在维修，或停用等配件） */
    MAINTAINING("维护中");

    /** 面向用户的中文说明，供前端展示 */
    private final String label;

    DeviceStatus(String label) {
        this.label = label;
    }

    /**
     * 取中文说明。
     *
     * @return 状况的中文名称
     */
    public String getLabel() {
        return label;
    }

    /**
     * 判断一个字符串是否为合法状况名。
     *
     * <p>用于校验接口入参：状况列是 {@code VARCHAR} 而非库级 {@code ENUM}，
     * 库不会替我们挡住非法值，得在落库前拦一道。
     *
     * <p><b>校验只认枚举，不另写一份正则</b> —— 正则就是第二份取值清单，
     * 将来加一态时容易漏改其中一份。
     *
     * @param name 待校验的状况名，可为 null
     * @return 合法返回 true；null 或不在枚举内返回 false
     */
    public static boolean isValid(String name) {
        return name != null && Arrays.stream(values()).anyMatch(s -> s.name().equals(name));
    }

    /**
     * 判断该状况下机台是否可用。
     *
     * <p>{@link #NORMAL} 与 {@link #NEEDS_REPAIR} 算可用 ——
     * 「待维护」的语义就是「还能玩，只是有小毛病」；
     * 只有 {@link #MAINTAINING} 不可用。
     *
     * <p>供前端决定标签样式与提示语（是否要加一句「可能影响体验」），
     * <b>不参与任何准入判断</b>。
     *
     * <p><b>刻意不写成 {@code Set.of(...).contains(name)}</b>：JDK 的不可变集合
     * 对 {@code null} 查询会抛 {@link NullPointerException}（它们用
     * {@code requireNonNull} 挡住 null），而本方法的契约是「认不出就返回 false」。
     * 用 {@code equals} 逐个比则天然安全 —— 状况名来自数据库列，为 null
     * 是完全可能的情形（手工改库、老数据），不该让查询机台列表直接崩掉。
     *
     * @param name 状况名，可为 null
     * @return 可用返回 true；维护中、null 或无法识别的取值返回 false
     */
    public static boolean isUsable(String name) {
        return NORMAL.name().equals(name) || NEEDS_REPAIR.name().equals(name);
    }

    /**
     * 把状况名转成中文说明。
     *
     * <p>状况列是 {@code VARCHAR}，库里可能存着枚举之外的字符串（手工改过数据）。
     * 这种情况下<b>原样返回</b>，让异常数据在界面上一眼看得出来 ——
     * 伪装成一个正常的中文标签反而会把它藏起来。
     *
     * @param name 状况名，可为 null
     * @return 中文说明；认不出的取值原样返回
     */
    public static String labelOf(String name) {
        return isValid(name) ? valueOf(name).getLabel() : name;
    }
}
