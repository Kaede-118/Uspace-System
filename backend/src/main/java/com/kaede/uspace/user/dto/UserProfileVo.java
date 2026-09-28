package com.kaede.uspace.user.dto;

import com.kaede.uspace.user.entity.SysUser;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户自己的资料，用于「我的」页面与登录后的用户信息展示。
 *
 * <p><b>它是白名单，不是黑名单</b>：只有这里声明过的字段才会返回给前端。
 * {@code passwordHash}、{@code tokenVersion} 这类敏感字段<b>不在其中</b>，
 * 因此不存在「新加了敏感字段却忘了排除」的风险 ——
 * 这正是用 VO 而不是直接返回实体的意义。
 *
 * <p>与 {@link AdminUserVo} 的区别：这里只给用户看自己的信息，因此
 * 不含 {@code status}（封禁状态由接口直接拒绝体现，不必让用户读到状态码）
 * 与 {@code tokenVersion}（内部机制，暴露出去只会引起困惑）。
 */
@Data
public class UserProfileVo {

    /** 用户 ID */
    private Long id;

    /** 登录名。注册后不可修改，用于登录与对账 */
    private String username;

    /** 昵称，界面上展示的名字 */
    private String nickname;

    /** 手机号，可为空 */
    private String phone;

    /** QQ 号，可为空 */
    private String qq;

    /** 游玩偏好，逗号分隔的设备类型 code，可为空 */
    private String preference;

    /** 角色：USER / ADMIN。前端据此决定是否显示「运营后台」入口 */
    private String role;

    /**
     * 累计实付总额（订单消费 + 月卡充值，终生累计）。
     *
     * <p><b>注意它不等于「本月累计消费」</b> —— 后者才是月度优惠门槛的判定依据，
     * 由 {@code biz_order} 按月聚合算出，不落字段。两个口径用途不同，勿混。
     * 本月的数字待模块 8（订单管理）完成后，再由「我的」页面另行查询展示。
     */
    private BigDecimal totalPaid;

    /** 注册时间 */
    private LocalDateTime createdAt;

    /**
     * 由实体构造 VO。
     *
     * <p>写成静态工厂而不是构造函数，是为了让调用处读起来有语义：
     * {@code UserProfileVo.from(user)} 一眼能看出是在做视图转换。
     *
     * @param user 用户实体
     * @return 对外的资料视图
     */
    public static UserProfileVo from(SysUser user) {
        UserProfileVo vo = new UserProfileVo();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setPhone(user.getPhone());
        vo.setQq(user.getQq());
        vo.setPreference(user.getPreference());
        vo.setRole(user.getRole());
        vo.setTotalPaid(user.getTotalPaid());
        vo.setCreatedAt(user.getCreatedAt());
        return vo;
    }
}
