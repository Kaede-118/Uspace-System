package com.kaede.uspace.notice;

/**
 * 自动公告的文案工厂。
 *
 * <p>公告是一条<b>消息</b>：「什么时刻发生了什么事」。所以这里的文案都是<b>事件描述</b>，
 * 而不是状态描述 —— 写「拍拍机 1 号 转为维护中」而不是「拍拍机 1 号 维护中」。
 * 后者读起来像一份当前状态清单，而消息流里每一行都对应一个已经发生的动作。
 *
 * <p><b>这是本模块的披露边界所在，用「结构上写不出来」代替「记得别写」。</b>
 * 每个方法都<b>刻意不接受 {@code biz_device.remark}</b>（运营备注，
 * 如「等屏幕配件到货」「张三反映左键不灵」）—— 连匿名的 {@code DeviceDisplayVo}
 * 都不含它，公告更不该含。调用方就算想泄露，也没有参数可传。
 * 这是本项目已经在用的手法（「声明成接口方法而非 DTO 字段，是为了漏实现时编译不过」
 * 是同一个思路）。
 *
 * <p><b>状况名以字符串传入，不 import {@code DeviceStatus}</b>：
 * 本包只依赖 {@code common}，不认识 {@code device} 包 —— 这是
 * {@code notice} 能保持独立、不成环的前提。调用方传
 * {@code DeviceStatus.getLabel()} 的结果进来即可。
 *
 * <p><b>不生成「停业」公告</b>：停业那条路径的措辞边界由 {@code StoreStatusVo}
 * 定好了（只说「暂停营业」，不说原因，因为「设备维护」「员工休假」属运营内务），
 * 在这里另开一条说着说着就会说漏。
 */
public final class NoticeContents {

    /**
     * 工具类的构造器私有化。本类只有静态方法，不需要实例。
     */
    private NoticeContents() {
    }

    /**
     * 新增机台的通知。
     *
     * <p>新机到店也是一次「状况从无到有」，值得让顾客知道 ——
     * 尤其是新机直接标成维护中的情形（等配件、还没调试），
     * 顾客在陈列页看到它标着「维护中」时不会再疑惑。
     *
     * @param deviceName  机台名称，如「拍拍机 1 号」
     * @param statusLabel 初始状况的中文名，如「良好」
     * @return 公告标题
     */
    public static String deviceCreated(String deviceName, String statusLabel) {
        return "新增机台 " + deviceName + "（" + statusLabel + "）";
    }

    /**
     * 机台状况变更的通知。
     *
     * <p><b>每一次变更都产生一条</b>，包括「良好 ↔ 待维护」这两个看起来不起眼的跳转。
     * 后者先前被排除在外（理由是「顾客自己看标签就知道了」），
     * 但那是把公告当成状态投影时的取舍；作为消息流，
     * 「这台机器今天被标了待维护」本身就是一条值得记录的事实，
     * 而且顾客从两行字里能看出「店里在照看这些机器」。
     *
     * <p>文案写成「A 转为 B」而不是「A 现在是 B」：前者是一个事件，
     * 后者是一句状态断言 —— 而这条消息被翻到时，状态可能早就又变了。
     * 事件描述永远不会过期。
     *
     * @param deviceName    机台名称
     * @param oldStatusLabel 变更前状况的中文名
     * @param newStatusLabel 变更后状况的中文名
     * @return 公告标题
     */
    public static String deviceStatusChanged(String deviceName,
                                             String oldStatusLabel, String newStatusLabel) {
        return deviceName + " 由 " + oldStatusLabel + " 转为 " + newStatusLabel;
    }

    /**
     * 机台退役的通知。
     *
     * <p>退役走的是逻辑删除（机器搬走、报废），不是一种「状况」——
     * 所以它不该出现在 {@code DeviceStatus} 枚举里，但它确实是一件发生的事，
     * 该在消息流里留一条。否则顾客会发现陈列页上少了一台机器，
     * 而公告栏里什么都没说。
     *
     * @param deviceName 机台名称
     * @return 公告标题
     */
    public static String deviceRetired(String deviceName) {
        return "机台 " + deviceName + " 已退役";
    }
}
