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
