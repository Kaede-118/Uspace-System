package com.kaede.uspace.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kaede.uspace.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 用户实体，对应 {@code sys_user} 表。
 *
 * <p>字段与建表脚本一一对应（{@code docs/sql/schema.sql} 的「模块 1」一节）。
 * 列名是下划线风格、属性是驼峰风格，由 MyBatis-Plus 自动映射，不必逐字段标注。
 *
 * <p><b>本类绝不可直接返回给前端</b> —— 它带着 {@code passwordHash}。
 * 对外一律走 {@code dto} 包里的 VO（{@code UserProfileVo} / {@code AdminUserVo}），
 * 由 VO 决定哪些字段可见。多写一个 VO 是多一点代码，但换来的是
 * 「新加一个敏感字段忘了排除」这类事故不可能发生 —— 因为 VO 是白名单，
 * 不写就不出现；而直接返回实体是黑名单，忘了排除就泄露。
 *
 * <p>关于 {@code @EqualsAndHashCode(callSuper = true)}：继承 {@code BaseEntity} 后，
 * Lombok 会警告生成的 {@code equals} / {@code hashCode} 没有调用父类的实现。
 * 这是本项目对「实体只用 @Data」这条例外的一处轻微放宽 ——
 * 替代方案是在每个实体里各写一遍那三个审计字段，代价更大。
 *
 * @see com.kaede.uspace.common.entity.BaseEntity
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {

    /** 主键。策略取 application.properties 里配的数据库自增 */
    @TableId
    private Long id;

    /** 登录名，唯一。注册后不可修改 */
    private String username;

    /**
     * 密码哈希（BCrypt）。
     *
     * <p>禁止任何形式的明文存储，也禁止 MD5 / SHA 一类的快速哈希 ——
     * 那些在离线爆破面前与明文无异。
     */
    private String passwordHash;

    /** 昵称。用于界面展示与 QQ 群播报，不可为空（注册时按三级规则兜底） */
    private String nickname;

    /** 手机号 */
    private String phone;

    /**
     * QQ 号。模块 11 靠它把群消息的发送者对应到系统用户。
     *
     * <p>唯一索引 {@code uk_qq} 保证不重号；可为空（不是每个用户都加群）。
     */
    private String qq;

    /**
     * 游玩偏好：逗号分隔的设备类型 code，多选，可为空。
     *
     * <p>取值来自模块 4 的字典表 {@code biz_equipment_type}，如 {@code ONGEKI,MAIMAI}。
     * 存逗号串而非关联表，是因为取值只有几个到十几个，
     * 且没有「按偏好精确筛选用户」这类需要索引的查询。
     *
     * <p><b>它是「倾向」而非「占用事实」</b> —— 说明该用户通常玩什么，
     * 不说明他此刻正占着哪台机器。
     */
    private String preference;

    /**
     * 头像地址，可为空。存<b>站内相对路径</b>，如
     * {@code /uploads/avatar/20260930/12_a1b2.jpg}。
     *
     * <p>存相对路径而非完整 URL：完整 URL 会把域名写进库，换域名要全表刷一遍。
     * 前端 {@code <img :src="avatar">} 直接加载；为空时前端回落到默认头像。
     *
     * <p>它<b>不参与</b> {@code PUT /api/user/me} 的全量替换 ——
     * 那个接口传 null 表示清空，把头像混进去会导致「只改昵称」的表单
     * 顺手把头像清掉，而且不报任何错。改头像走独立的
     * {@code POST /api/user/me/avatar}。
     */
    private String avatar;

    /**
     * 自定义背景图地址，可为空。约 6:1 的横长图，用作个人卡片（{@code UserCard}）的背景。
     *
     * <p>与 {@link #avatar} 同款：站内相对路径、不参与全量替换。
     * 为空时卡片回落到纯色背景（{@code --c-card}），<b>不能是破图</b>。
     */
    private String banner;

    /** 角色：{@code USER} 普通用户 / {@code ADMIN} 管理员。取值见 {@link com.kaede.uspace.user.UserRole} */
    private String role;

    /** 状态：1=正常，0=禁用。禁用后无法登录，已签发的凭证也立即失效 */
    private Integer status;

    /**
     * JWT 版本号。
     *
     * <p>签发凭证时把这个值写进载荷；每次请求校验时与库里的值比对，
     * 不一致即视为凭证已被撤销。封禁与改密时 +1，于是该用户所有已签发的凭证
     * 立即全部失效 —— 这是 JWT 这种「签发后即无状态」的凭证唯一可靠的撤销手段。
     *
     * <p>必须用<b>相对更新</b>（{@code SET token_version = token_version + 1}），
     * 不能「读出来 +1 再写回」—— 后者在并发下会丢更新。
     */
    private Integer tokenVersion;

    /** 累计订单实付（仅房间消费，终生累计）。只增不减，由订单支付成功时累加 */
    private BigDecimal orderPaid;

    /** 累计月卡充值实付。与 {@code orderPaid} 分开存，便于将来给两类消费各自定政策 */
    private BigDecimal cardPaid;

    /**
     * 累计实付总额 = {@code orderPaid} + {@code cardPaid}。
     *
     * <p>冗余列，供前端展示与「老客回馈」这类全表筛选 ——
     * 写成两列相加的表达式走不了索引。
     *
     * <p><b>注意它不计入月度优惠门槛</b>：优惠门槛是由 {@code biz_order} 按月聚合算的，
     * 且不含月卡充值。两个「累计消费」口径不同，勿混用。
     */
    private BigDecimal totalPaid;
}
