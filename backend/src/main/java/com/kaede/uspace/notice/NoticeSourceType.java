package com.kaede.uspace.notice;

import java.util.Arrays;

/**
 * 自动公告的来源类型：这条消息是哪一类业务对象产生的。
 *
 * <p>与 {@code source_id} 一起用来回答「这条公告是哪台机器发的」——
 * 运营在后台看到「拍拍机 1 号 转为维护中」时，能顺着这个字段定位到具体那条机台记录。
 * <b>它们不是唯一键</b>：同一台机台每次状况变化各产生一条公告，
 * 这正是消息流该有的样子。
 *
 * <p><b>枚举里刻意没有 {@code MANUAL}</b>：发布方式是
 * {@link NoticePublishMode} 的事，混进来源类型里会让「手写」看起来像一种来源。
 * 更要紧的是 —— <b>枚举里没有这个取值，就没有任何 API 入参能把它传进来</b>。
 *
 * <p><b>为什么没有 {@code BOOKING}</b>：包场信息走独立的「包场时间表」，
 * 不进消息流。它属于「未来的安排」而不是「已经发生的事」——
 * 混在一起会让用户分不清哪条是通知、哪条是日程。
 *
 * <p><b>本枚举不对外暴露</b>：用户端的 {@code NoticeVo} 不含
 * {@code sourceType} 与 {@code sourceId}。
 */
public enum NoticeSourceType {

    /** 机台。{@code source_id} 是 {@code biz_device.id} */
    DEVICE("机台");

    /** 面向运营的中文说明。只在后台列表里用，用户端看不到来源类型 */
    private final String label;

    NoticeSourceType(String label) {
        this.label = label;
    }

    /**
     * 取中文说明。
     *
     * @return 来源类型的中文名称
     */
    public String getLabel() {
        return label;
    }

    /**
     * 判断一个字符串是否为合法来源类型。
     *
     * <p>与 {@code DeviceStatus#isValid} 同一套做法：来源列是 {@code VARCHAR}
     * 而非库级 {@code ENUM}，库里可能存着枚举之外的字符串（手工改过数据），
     * 解析之前先问一句比事后 {@code valueOf} 抛异常要好。
     *
     * @param name 待校验的来源类型名，可为 null
     * @return 合法返回 true；null 或不在枚举内返回 false
     */
    public static boolean isValid(String name) {
        return name != null && Arrays.stream(values()).anyMatch(t -> t.name().equals(name));
    }

    /**
     * 把来源类型名转成中文说明。
     *
     * <p>认不出的取值<b>原样返回</b>，让异常数据在后台一眼看得出来 ——
     * 伪装成一个正常的中文标签反而会把它藏起来。
     *
     * @param name 来源类型名，可为 null
     * @return 中文说明；认不出的取值原样返回
     */
    public static String labelOf(String name) {
        return isValid(name) ? valueOf(name).getLabel() : name;
    }
}
