package com.kaede.uspace.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.user.entity.SysUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * 用户表的数据访问接口。
 *
 * <p>通过 {@code @MapperScan("com.kaede.uspace.**.mapper")} 自动注册，无需 {@code @Mapper} 注解。
 *
 * <p><b>为什么全部用「具名方法 + 注解 SQL」而不在 Service 里拼 QueryWrapper</b>：
 * 本项目约定不用 Mockito，单元测试靠手写假实现替换 Mapper。
 * 假实现能替换的只有接口上声明过的方法 —— 如果 Service 直接用
 * {@code selectList(new QueryWrapper<>().eq("username", ...))}，
 * 那么假实现就得去模拟整个 Wrapper 机制，等于把 MyBatis-Plus 重写一遍。
 * 换成具名方法后，假实现只需实现几个简单方法即可，
 * 「用户已存在时应当拒绝注册」这类规则才能真正被单测覆盖。
 *
 * <p><b>手写 SQL 必须自己带 {@code deleted = 0}</b>：全局逻辑删除配置只作用于
 * MyBatis-Plus 自己生成的方法（{@code selectById}、{@code selectList} 等），
 * 对注解里手写的 SQL 不生效。漏掉的后果是已删除的用户还能被查到，
 * 或是「删掉的用户名无法重新注册」。{@code selectById} 由 BaseMapper 提供，无需重复写。
 */
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 按登录名查询用户（未删除的）。
     *
     * <p>用于登录与注册查重。用户名有唯一索引，所以最多返回一条。
     *
     * @param username 登录名
     * @return 用户；不存在时返回 null
     */
    @Select("SELECT * FROM sys_user WHERE username = #{username} AND deleted = 0")
    SysUser selectByUsername(@Param("username") String username);

    /**
     * 按 QQ 号查询用户（未删除的）。
     *
     * <p>用于注册查重，以及模块 11 从群消息定位用户身份。
     *
     * @param qq QQ 号
     * @return 用户；不存在时返回 null
     */
    @Select("SELECT * FROM sys_user WHERE qq = #{qq} AND deleted = 0")
    SysUser selectByQq(@Param("qq") String qq);

    /**
     * 更新用户资料。
     *
     * <p><b>用显式 SQL 而非 {@code updateById}</b>，是因为后者的默认策略会忽略 null 字段 ——
     * 那样用户就无法把手机号「清空」，只能改成另一个非空值。
     * 本方法配合 PUT 的全量替换语义：传 null 即清空该字段。
     *
     * <p>若要保留「不传就不改」的语义，应当改用 PATCH，而不是在这里做特殊处理。
     *
     * @param id         用户 ID
     * @param nickname   昵称
     * @param phone      手机号，可为 null
     * @param qq         QQ 号，可为 null
     * @param preference 游玩偏好（逗号分隔的设备类型 code），可为 null
     * @return 受影响行数；0 表示用户不存在或已删除
     */
    @Update("""
            UPDATE sys_user
               SET nickname   = #{nickname},
                   phone      = #{phone},
                   qq         = #{qq},
                   preference = #{preference},
                   updated_at = NOW()
             WHERE id = #{id}
               AND deleted = 0
            """)
    int updateProfile(@Param("id") Long id,
                      @Param("nickname") String nickname,
                      @Param("phone") String phone,
                      @Param("qq") String qq,
                      @Param("preference") String preference);

    /**
     * 更新头像地址。
     *
     * <p><b>刻意单独成一个方法，而不是并进 {@link #updateProfile}</b>：
     * 那个方法是 PUT 的全量替换语义（传 null 即清空），而头像走的是
     * 「上传成功就换掉」这一条独立路径。合进去的话，前端提交「只改昵称」的表单时
     * 会因为没带 avatar 而<b>把头像清空，且不报任何错</b>。
     * 两者语义不同，就不该共用一条 SQL。
     *
     * <p>写成 {@code #{avatar}} 而非 {@code COALESCE(*, avatar)}：本方法的调用方
     * 永远是「刚把新图写进磁盘、拿到了新路径」，不存在「不传就不改」的需求。
     *
     * @param id     用户 ID
     * @param avatar 新的头像地址（站内相对路径），可为 null 表示恢复默认头像
     * @return 受影响行数；0 表示用户不存在或已删除
     */
    @Update("""
            UPDATE sys_user
               SET avatar     = #{avatar},
                   updated_at = NOW()
             WHERE id = #{id}
               AND deleted = 0
            """)
    int updateAvatar(@Param("id") Long id, @Param("avatar") String avatar);

    /**
     * 更新自定义背景图地址。
     *
     * <p>与 {@link #updateAvatar} 严格对称、同样独立成方法，理由见那边的注释。
     * <b>两列分开更新而不是合成一个方法</b>：换头像不该顺带把背景图也写一遍 ——
     * 那会在并发上传时出现「换头像的请求把背景图改回旧值」这类丢更新，
     * 且因为两列都被显式赋值，连 {@code COALESCE} 都救不了。
     *
     * @param id     用户 ID
     * @param banner 新的背景图地址（站内相对路径），可为 null 表示回落纯色背景
     * @return 受影响行数；0 表示用户不存在或已删除
     */
    @Update("""
            UPDATE sys_user
               SET banner     = #{banner},
                   updated_at = NOW()
             WHERE id = #{id}
               AND deleted = 0
            """)
    int updateBanner(@Param("id") Long id, @Param("banner") String banner);

    /**
     * 更新密码，同时把 token 版本号加一。
     *
     * <p>两件事必须在<b>同一条 SQL</b> 里完成：改完密码却漏了升版本号，
     * 意味着旧密码虽然换了，但用旧密码登录时拿到的凭证仍然有效 ——
     * 改密码这一动作就失去了「把别人踢下线」的意义。
     *
     * <p>版本号用相对更新 {@code token_version + 1} 而非先读后写，
     * 避免并发下丢更新。
     *
     * @param id           用户 ID
     * @param passwordHash 新密码的 BCrypt 哈希（已加密，方法内不再处理）
     * @return 受影响行数；0 表示用户不存在或已删除
     */
    @Update("""
            UPDATE sys_user
               SET password_hash = #{passwordHash},
                   token_version = token_version + 1,
                   updated_at    = NOW()
             WHERE id = #{id}
               AND deleted = 0
            """)
    int updatePasswordAndBumpVersion(@Param("id") Long id, @Param("passwordHash") String passwordHash);

    /**
     * 更新用户状态，并按需把 token 版本号加一。
     *
     * <p>封禁时传 {@code bumpDelta = 1} —— 否则用户虽然登不进来，
     * 但手上那个还没过期的凭证依然能继续调接口，「封禁」就成了摆设。
     *
     * <p>解封时传 {@code bumpDelta = 0}：旧凭证在封禁那一刻版本号就对不上了，
     * 已经全部失效，再升一次没有意义，还会把用户其他设备上正常的登录态一并踢掉。
     *
     * @param id        用户 ID
     * @param status    目标状态：1=正常，0=禁用
     * @param bumpDelta 版本号增量，取 0 或 1
     * @return 受影响行数；0 表示用户不存在或已删除
     */
    @Update("""
            UPDATE sys_user
               SET status        = #{status},
                   token_version = token_version + #{bumpDelta},
                   updated_at    = NOW()
             WHERE id = #{id}
               AND deleted = 0
            """)
    int updateStatus(@Param("id") Long id,
                     @Param("status") Integer status,
                     @Param("bumpDelta") int bumpDelta);

    /**
     * 更新用户角色。
     *
     * <p><b>刻意不动 token 版本号</b>：鉴权时角色取自数据库而非凭证载荷，
     * 所以改完立即生效，无需借版本号把用户踢下线。
     * 这与「封禁」「改密」处理方式不同的原因见 {@code JwtAuthenticationFilter}。
     *
     * @param id   用户 ID
     * @param role 目标角色名，取值见 {@link com.kaede.uspace.user.UserRole}
     * @return 受影响行数；0 表示用户不存在或已删除
     */
    @Update("""
            UPDATE sys_user
               SET role       = #{role},
                   updated_at = NOW()
             WHERE id = #{id}
               AND deleted = 0
            """)
    int updateRole(@Param("id") Long id, @Param("role") String role);

    /**
     * 累加用户的订单消费额。
     *
     * <p>供模块 8 的支付回调在<b>订单支付成功</b>时调用。月卡充值走另一个方法
     * （模块 9 实现）—— 两类消费分开存是为了将来能给它们各自定政策。
     *
     * <p><b>一条 SQL 里同时累加两列</b>：{@code order_paid} 是订单消费的本体，
     * {@code total_paid} 是它与 {@code card_paid} 之和的冗余列（供前端展示与全表筛选，
     * 写成表达式走不了索引）。两列同源，必须一起动 —— 分成两条 SQL 就可能出现
     * 「订单消费加了、总额没加」的不一致，且这种不一致不会有任何报错。
     *
     * <p>用相对更新 {@code order_paid + #{amount}} 而非先读后写：支付回调可能并发，
     * 先读后写会丢更新。这也是回调幂等的第二道防线（见模块 8 的 PaymentService）。
     *
     * @param id     用户 ID
     * @param amount 本次实付金额（元）
     * @return 受影响行数；0 表示用户不存在或已删除
     */
    @Update("""
            UPDATE sys_user
               SET order_paid = order_paid + #{amount},
                   total_paid = total_paid + #{amount},
                   updated_at = NOW()
             WHERE id = #{id}
               AND deleted = 0
            """)
    int addOrderPaidAmount(@Param("id") Long id, @Param("amount") BigDecimal amount);

    /**
     * 累加用户的月卡充值额。
     *
     * <p>供模块 8 的支付回调在<b>月卡付款成功</b>时调用。
     *
     * <p>与 {@link #addOrderPaidAmount} 严格对称：一条 SQL 里同时累加
     * {@code card_paid} 与 {@code total_paid}，用相对更新防并发丢更新。
     * 两列同源，分成两条 SQL 就可能出现「卡费加了、总额没加」的不一致，
     * 且不会有任何报错。同理，本方法<b>绝不能</b>去动 {@code order_paid} ——
     * 那样两个累计口径就错位了，而前台展示与老客回馈筛选都建立在这个区分上。
     *
     * @param id     用户 ID
     * @param amount 本次实付金额（元）
     * @return 受影响行数；0 表示用户不存在或已删除
     */
    @Update("""
            UPDATE sys_user
               SET card_paid  = card_paid + #{amount},
                   total_paid = total_paid + #{amount},
                   updated_at = NOW()
             WHERE id = #{id}
               AND deleted = 0
            """)
    int addCardPaidAmount(@Param("id") Long id, @Param("amount") BigDecimal amount);

    /**
     * 按关键字分页查询用户，供运营后台的用户列表使用。
     *
     * <p>关键字同时匹配登录名、昵称与 QQ 号 —— 运营通常记得住其中一个，
     * 让他先选「按什么搜」是多余的一步。三个字段都没有索引，
     * 但用户表规模在千级以内，全表扫描的开销可以接受。
     *
     * <p>用 {@code ORDER BY id DESC} 让新注册的用户排在最前 ——
     * 运营看用户列表多半是为了处理刚发生的事。
     *
     * @param page    分页参数，由 MyBatis-Plus 的分页插件处理
     * @param keyword 搜索关键字，为 null 或空串时返回全部用户
     * @return 分页结果
     */
    @Select("""
            SELECT *
              FROM sys_user
             WHERE deleted = 0
               AND (#{keyword} IS NULL
                    OR #{keyword} = ''
                    OR username LIKE CONCAT('%', #{keyword}, '%')
                    OR nickname LIKE CONCAT('%', #{keyword}, '%')
                    OR qq       LIKE CONCAT('%', #{keyword}, '%'))
             ORDER BY id DESC
            """)
    IPage<SysUser> selectPageByKeyword(IPage<SysUser> page, @Param("keyword") String keyword);
}
