package com.kaede.uspace.user.dto;

import com.kaede.uspace.user.entity.SysUser;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 运营后台看到的用户信息。
 *
 * <p>比 {@link UserProfileVo} 多出 {@code status} 与两个分开的累计消费列 ——
 * 管理员需要判断「这个用户是不是被禁用了」「他是房间消费多还是买月卡多」，
 * 而这些不该暴露给普通用户自己看。
 *
 * <p>同样<b>不含 {@code passwordHash} 与 {@code tokenVersion}</b>：
 * 前者是绝对不能外泄的，后者是内部撤销机制，管理员也没有理由需要看到它 ——
 * 要「强制某用户下线」应当走封禁接口，那才是语义正确的操作。
 */
@Data
public class AdminUserVo {

    /** 用户 ID */
    private Long id;

    /** 登录名 */
    private String username;

    /** 昵称 */
    private String nickname;

    /** 手机号，可为空 */
    private String phone;

    /** QQ 号，可为空 */
    private String qq;

    /** 游玩偏好，逗号分隔的设备类型 code，可为空 */
    private String preference;

    /** 角色：USER / ADMIN */
    private String role;

    /** 状态：1=正常，0=禁用 */
    private Integer status;

    /** 累计订单实付（仅房间消费） */
    private BigDecimal orderPaid;

    /** 累计月卡充值实付 */
    private BigDecimal cardPaid;

    /** 累计实付总额 = orderPaid + cardPaid */
    private BigDecimal totalPaid;

    /**
     * 生效中的月卡类型：{@code ALL_DAY} / {@code NIGHT}；没有生效卡时为 null。
     *
     * <p>它是<b>算出来的</b>，不是 {@code sys_user} 上的列 —— 判定口径与
     * 结算免单、在店名册完全一致（状态为生效中、且今天落在卡的起止日期之间）。
     * 口径不一致的话，运营会看到「列表说他有卡、结账却照收钱」这种对不上的现象。
     *
     * <p>只给 code，中文名由前端映射（与在店名册的做法一致）。
     */
    private String cardType;

    /**
     * 累计在店时长（分钟）。<b>同样是算出来的</b>：对 {@code biz_order.stay_minutes} 求和。
     *
     * <p>⚠️ 与「累计消费」的口径差异要留意：<b>这个数包含了免费时段、也包含包场那几小时</b>
     * （在店时长是「人在店里待了多久」，不是「计费了多久」）。
     * 两个数字并排展示时它们本来就不该相等，别当成 bug。
     *
     * <p>只累加已结算的订单（{@code stay_minutes} 列在 {@code IN_USE} 期间是 NULL），
     * 所以「正在店里玩着」的那一段不计入 —— 它是尚未定局的数，
     * 计入的话刷新一次跳一次。
     */
    private Long totalStayMinutes;

    /** 注册时间 */
    private LocalDateTime createdAt;

    /** 最后更新时间。资料、状态、角色任一变更都会刷新它 */
    private LocalDateTime updatedAt;

    /**
     * 由实体构造 VO。
     *
     * @param user 用户实体
     * @return 运营后台的用户视图
     */
    public static AdminUserVo from(SysUser user) {
        AdminUserVo vo = new AdminUserVo();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setPhone(user.getPhone());
        vo.setQq(user.getQq());
        vo.setPreference(user.getPreference());
        vo.setRole(user.getRole());
        vo.setStatus(user.getStatus());
        vo.setOrderPaid(user.getOrderPaid());
        vo.setCardPaid(user.getCardPaid());
        vo.setTotalPaid(user.getTotalPaid());
        vo.setCreatedAt(user.getCreatedAt());
        vo.setUpdatedAt(user.getUpdatedAt());
        return vo;
    }
}
