package com.kaede.uspace.order.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 门店当前在店的一位顾客，供用户端「在店用户」页展示。
 *
 * <p><b>它是一份「谁在店里」的名册，不是账单</b> —— 因此<b>没有任何金额字段</b>。
 * 进店时刻与在店时长是进门就能看见的事实，而「他这单要付多少」属于个人消费信息，
 * 不进这份名单。这也是本接口比「在店用户 + 消费额」轻的那一档的由来。
 *
 * <p><b>与 {@link com.kaede.uspace.user.dto.UserProfileVo} 的区别</b>：
 * 那是「看自己」，这是「看别人」，所以手机号、QQ 号、累计消费都不在其中，
 * 只留头像昵称、游玩偏好与月卡状态这三样同店顾客互相看得见的信息。
 * 本类是白名单而非黑名单，将来给 {@code SysUser} 加敏感列也不会自动漏出来。
 *
 * <p><b>偏好只给 code，不给中文名</b>：{@code preference} 里是
 * {@code PAIPAI} 这样的字典 code（逗号分隔，可能多个），中文名由前端用
 * {@code GET /api/devices/types} 自己映射。那个接口是匿名开放的，
 * 且前端在「设置游玩偏好」时本来就要调它，多映射一次不增加任何成本；
 * 而后端为此引入一条 {@code order → device} 的依赖边则不划算。
 */
@Data
public class InstoreUserVo {

    /** 用户 ID */
    private Long userId;

    /** 昵称。用户记录查不到时为 null —— 见 {@code InstoreService} 的说明 */
    private String nickname;

    /** 头像地址（站内相对路径），可为空。前端 {@code <img :src>} 直接用，为空时回落默认头像 */
    private String avatar;

    /** 自定义背景图地址（站内相对路径），可为空。供卡片背景使用，为空时用纯色兜底 */
    private String banner;

    /**
     * 游玩偏好，逗号分隔的设备类型 code，可为空。
     *
     * <p><b>它是「倾向」，不是「占用事实」</b>：只说明这位顾客通常玩什么，
     * 不说明他此刻正占着哪台机器 —— 本系统不做设备级使用记录。
     * 中文名由前端映射（见类注释）。
     */
    private String preference;

    /**
     * 月卡类型：{@code ALL_DAY} 全天 / {@code NIGHT} 夜间；无卡时为 null。
     *
     * <p><b>判定按订单的开始日期，不按「今天」</b> —— 与结算的免单判定同源。
     * 按今天判的话，跨零点仍在店的夜单会出现「列表说他有卡、结账却照收钱」，
     * 而这两个数字在同一个页面上并排出现，用户一眼就能看出矛盾。
     */
    private String cardType;

    /** 月卡类型的中文名，如「全天月卡」。无卡时为 null，前端据此展示「全天月卡 · 生效中」 */
    private String cardTypeLabel;

    /** 进店时刻（= 订单的开始时刻） */
    private LocalDateTime startTime;

    /**
     * 在店时长（分钟），<b>实时算出，不读列</b>。
     *
     * <p>订单上的 {@code stay_minutes} 列要结算时才写入，{@code IN_USE} 期间是 NULL ——
     * 读列只会读到空。但算的口径与那一列严格一致（同样向下取整到分钟），
     * 所以结算之后两者对得上。
     */
    private Integer stayMinutes;
}
