package com.kaede.uspace.notice;

import java.util.Arrays;

/**
 * 公告的发布方式：系统自动还是管理员手写。
 *
 * <p>这个字段回答的是「<b>这条公告是谁的意思</b>」—— 系统按规则算出来的，
 * 还是运营做的判断。它是<b>展示与筛选</b>用的。
 *
 * <p>与之并列的 {@link NoticeSourceType} 回答的是另一个问题：
 * 「<b>这条公告对应哪个业务对象</b>」，那是<b>幂等键</b>，写入路径用的，不对外暴露。
 * 两者不能互相推导：
 * <ul>
 *   <li>{@code AUTO} 若没有 source，幂等键就退化成「每次调用都新增一条」</li>
 *   <li>{@code MANUAL} 若有 source，就说明有人手写了一条挂着机台 ID 的公告，
 *       将来机台删了它不知道该不该跟着走</li>
 * </ul>
 *
 * <p><b>为什么是 VARCHAR + 枚举而不是 TINYINT 布尔</b>：与 {@code biz_order.status}、
 * {@code biz_device.status}、{@code biz_monthly_card.card_type} 保持同一风格。
 * 判据是「这个字段将来会不会长出第三态」—— 会就用枚举。
 * 把语义焊死在「是不是自动」这一个维度上，将来要区分「系统自动 / 管理员手写 /
 * 系统代管理员发（批量导入、QQ 机器人代发）」时，布尔就得改列类型、
 * 改所有判断、改所有 {@code WHERE}；枚举只需加一个取值。
 *
 * <p>而且校验机制只对字符串形态存在（见 {@link #isValid}）——
 * TINYINT 的 {@code 2}、{@code -1} 会静默写入，之后所有布尔判断的语义都变得不可知。
 *
 * <p><b>用户端要据此区分展示</b>：自动公告的内容是「某台机器在维护」「某时段有包场」，
 * 它长得像<b>通知</b>而不像<b>公告</b>。用户看到一条没人署名的通知会疑惑
 * 「这是谁说的、算不算数」。给自动公告加一个「系统」标记回答了这个问题，成本极低。
 * 反过来手写公告<b>不加</b>标记 —— 管理员写的通知本就没有「来源存疑」的问题，
 * 加个「人工」标签反而是噪音。
 */
public enum NoticePublishMode {

    /** 系统自动。由业务模块在状态变化时写入，内容不由人编辑 */
    AUTO("系统"),

    /** 管理员手写。内容由运营决定 */
    MANUAL("手写");

    /** 面向用户的中文说明，供前端直接渲染 */
    private final String label;

    NoticePublishMode(String label) {
        this.label = label;
    }

    /**
     * 取中文说明。
     *
     * @return 发布方式的中文名称
     */
    public String getLabel() {
        return label;
    }

    /**
     * 判断一个字符串是否为合法发布方式。
     *
     * <p>用于校验接口入参与解析库里的列值：发布方式是 {@code VARCHAR} 而非库级
     * {@code ENUM}，库不会替我们挡住非法值。
     *
     * <p><b>校验只认枚举，不另写一份正则</b> —— 正则就是第二份取值清单，
     * 将来加一态时容易漏改其中一份。理由同 {@code DeviceStatus#isValid}。
     *
     * @param name 待校验的发布方式名，可为 null
     * @return 合法返回 true；null 或不在枚举内返回 false
     */
    public static boolean isValid(String name) {
        return name != null && Arrays.stream(values()).anyMatch(m -> m.name().equals(name));
    }

    /**
     * 把发布方式名转成中文说明。
     *
     * <p>列是 {@code VARCHAR}，库里可能存着枚举之外的字符串（手工改过数据）。
     * 这种情况下<b>原样返回</b>，让异常数据在界面上一眼看得出来 ——
     * 伪装成一个正常的中文标签反而会把它藏起来。
     *
     * @param name 发布方式名，可为 null
     * @return 中文说明；认不出的取值原样返回
     */
    public static String labelOf(String name) {
        return isValid(name) ? valueOf(name).getLabel() : name;
    }
}
