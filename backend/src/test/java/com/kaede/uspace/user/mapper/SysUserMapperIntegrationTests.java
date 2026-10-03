package com.kaede.uspace.user.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kaede.uspace.user.dto.AdminUserQuery;
import com.kaede.uspace.user.dto.AdminUserVo;
import com.kaede.uspace.user.entity.SysUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SysUserMapper} 的集成测试，连本机真实 MySQL。
 *
 * <p><b>为什么单测之外还需要它</b>：{@code UserServiceTests} 用的是内存版假 Mapper，
 * 它模拟了数据库行为的<b>语义</b>却绕过了真实的 SQL —— 列名映射写错、
 * 审计字段没被自动填充、全局逻辑删除没生效、唯一索引没建，
 * 这些问题在假 Mapper 面前统统不会暴露，却会在运行时炸掉。
 * 本测试专门覆盖这些「只有真库能验证」的部分。
 *
 * <p><b>三重保护不污染开发库</b>：
 * <ol>
 *   <li>{@code @Transactional} —— 每个用例结束后自动回滚，一行数据都不会留下</li>
 *   <li>{@code @EnabledIfEnvironmentVariable} —— 没配 {@code MYSQL_PASSWORD}
 *       的机器（如刚 clone 下来还没配置的环境）会整个跳过，而不是报错失败</li>
 *   <li>测试数据用 {@code it_} 前缀命名，万一回滚失效也一眼能认出来</li>
 * </ol>
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+",
        disabledReason = "未配置 MYSQL_PASSWORD，跳过需要真实数据库的集成测试")
class SysUserMapperIntegrationTests {

    /** 测试用户名前缀，便于在库里辨认测试残留 */
    private static final String PREFIX = "it_";

    @Autowired
    private SysUserMapper userMapper;

    /**
     * 直接写库的通道，只给「后台列表查询」那组用。
     *
     * <p>那几条用例要预置 {@code biz_order} 与 {@code biz_monthly_card} 的数据，
     * 而它们的 Mapper 在另一个包里、本测试不该依赖 —— 用 JdbcTemplate 直插最直接。
     * 事务同样会回滚，不会留下痕迹。
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================================================================
    // 审计字段自动填充
    // ==================================================================

    @Test
    @DisplayName("插入时自动填充 created_at 与 updated_at —— 两列 NOT NULL 且无默认值")
    void insert_fillsAuditTimestamps() {
        SysUser user = newUser("audit");

        int affected = userMapper.insert(user);

        assertEquals(1, affected);
        assertNotNull(user.getId(), "主键应当由数据库自增填回实体");
        assertNotNull(user.getCreatedAt(),
                "created_at 是 NOT NULL 且无默认值，漏填会在插入时报错 —— "
                        + "这条用例确保自动填充真的生效，而不是等到线上注册时才炸");
        assertNotNull(user.getUpdatedAt());
    }

    @Test
    @DisplayName("查询能读回全部字段 —— 列名映射的回归网")
    void entity_columnsMapCorrectly() {
        SysUser user = newUser("columns");
        user.setNickname("列名测试");
        user.setPhone("13800138000");
        user.setQq("999000111");
        user.setPreference("PAIPAI,TAISHOU");
        user.setAvatar("/uploads/avatar/it_columns_1.png");
        user.setBanner("/uploads/banner/it_columns_1.png");
        userMapper.insert(user);

        SysUser loaded = userMapper.selectById(user.getId());

        assertNotNull(loaded, "刚插入的行必须能查回来");
        assertEquals(user.getUsername(), loaded.getUsername());
        assertEquals("列名测试", loaded.getNickname());
        assertEquals("13800138000", loaded.getPhone());
        assertEquals("999000111", loaded.getQq());
        assertEquals("PAIPAI,TAISHOU", loaded.getPreference());
        assertEquals("/uploads/avatar/it_columns_1.png", loaded.getAvatar(),
                "avatar 列写错名字或漏建时，这条会以 Unknown column 报出来，"
                        + "而不是等到用户上传完头像才发现存不进去");
        assertEquals("/uploads/banner/it_columns_1.png", loaded.getBanner());
        assertEquals("USER", loaded.getRole());
        assertEquals(1, loaded.getStatus().intValue());
        assertEquals(0, loaded.getTokenVersion().intValue());
        assertEquals(0, BigDecimal.ZERO.compareTo(loaded.getOrderPaid()));
        assertEquals(0, BigDecimal.ZERO.compareTo(loaded.getCardPaid()));
        assertEquals(0, BigDecimal.ZERO.compareTo(loaded.getTotalPaid()));
        assertEquals(0, loaded.getDeleted().intValue());
    }

    // ==================================================================
    // 逻辑删除
    // ==================================================================

    @Test
    @DisplayName("已逻辑删除的行查不出来 —— 全局逻辑删除配置确实生效")
    void selectById_excludesLogicallyDeletedRow() {
        SysUser user = newUser("deleted");
        userMapper.insert(user);

        // 直接改标记位，绕开 MyBatis-Plus 的删除方法，模拟「别处把行删了」
        userMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<SysUser>()
                        .eq("id", user.getId())
                        .set("deleted", 1));

        assertNull(userMapper.selectById(user.getId()),
                "配置漏了的话，被删的用户还能登录、还能用凭证访问 —— 删号就成了摆设");
    }

    // ==================================================================
    // 唯一索引：应用层查重之外的最终防线
    // ==================================================================

    @Test
    @DisplayName("用户名重复时抛唯一约束异常 —— 并发注册的最终防线")
    void insert_duplicateUsername_throwsDuplicateKeyException() {
        userMapper.insert(newUser("dup"));

        assertThrows(DuplicateKeyException.class,
                () -> userMapper.insert(newUser("dup")),
                "应用层查重挡不住并发，唯一索引才是最终防线；"
                        + "全局异常处理器会把它翻译成 409，而不是 500");
    }

    // ==================================================================
    // 相对更新：并发下不能丢更新
    // ==================================================================

    @Test
    @DisplayName("token 版本号是相对自增，连续两次调用加 2")
    void updatePasswordAndBumpVersion_isRelative() {
        SysUser user = newUser("bump");
        userMapper.insert(user);
        int before = user.getTokenVersion();

        userMapper.updatePasswordAndBumpVersion(user.getId(), "$2a$10$fakehashfakehashfakehash");
        userMapper.updatePasswordAndBumpVersion(user.getId(), "$2a$10$fakehashfakehashfakehash");

        SysUser loaded = userMapper.selectById(user.getId());
        assertEquals(before + 2, loaded.getTokenVersion().intValue(),
                "必须写成 token_version = token_version + 1；"
                        + "若实现成「读出来 +1 再写回」，并发下会丢更新，撤销就会漏掉");
    }

    // ==================================================================
    // 显式 SQL 换来的一处能力：把字段清空
    // ==================================================================

    @Test
    @DisplayName("资料更新能把可选字段写成 NULL —— 用显式 SQL 换来的全量替换语义")
    void updateProfile_canClearNullableColumns() {
        SysUser user = newUser("clear");
        user.setPhone("13900139000");
        user.setQq("888000222");
        user.setPreference("PAIPAI");
        userMapper.insert(user);

        userMapper.updateProfile(user.getId(), "新昵称", null, null, null);

        SysUser loaded = userMapper.selectById(user.getId());
        assertEquals("新昵称", loaded.getNickname());
        assertNull(loaded.getPhone(),
                "换成 updateById 就写不进 null（默认策略会跳过 null 字段），"
                        + "用户也就无法清空手机号");
        assertNull(loaded.getQq());
        assertNull(loaded.getPreference());
    }

    // ==================================================================
    // 其余具名方法
    // ==================================================================

    @Test
    @DisplayName("头像与背景图各自独立更新 —— 换一个不会动另一个")
    void updateAvatarAndBanner_touchOnlyTheirOwnColumn() {
        SysUser user = newUser("images");
        user.setAvatar("/uploads/avatar/old.png");
        user.setBanner("/uploads/banner/old.png");
        userMapper.insert(user);

        userMapper.updateAvatar(user.getId(), "/uploads/avatar/new.jpg");

        SysUser afterAvatar = userMapper.selectById(user.getId());
        assertEquals("/uploads/avatar/new.jpg", afterAvatar.getAvatar());
        assertEquals("/uploads/banner/old.png", afterAvatar.getBanner(),
                "两列合成一条 SQL 更新的话，换头像会把背景图一起写回旧值 —— "
                        + "并发上传时表现为「换好的背景图自己变回去了」");

        userMapper.updateBanner(user.getId(), "/uploads/banner/new.webp");

        SysUser afterBanner = userMapper.selectById(user.getId());
        assertEquals("/uploads/banner/new.webp", afterBanner.getBanner());
        assertEquals("/uploads/avatar/new.jpg", afterBanner.getAvatar(), "反向同理");
    }

    @Test
    @DisplayName("头像可以写回 NULL：恢复默认头像")
    void updateAvatar_canClearToNull() {
        SysUser user = newUser("clearimg");
        user.setAvatar("/uploads/avatar/x.png");
        userMapper.insert(user);

        userMapper.updateAvatar(user.getId(), null);

        assertNull(userMapper.selectById(user.getId()).getAvatar(),
                "写的是 avatar = #{avatar} 而不是 COALESCE —— 非 null 的语义"
                        + "正是「恢复默认」，不该被当成「不修改」");
    }

    @Test
    @DisplayName("按登录名与 QQ 号查询")
    void selectByUsernameAndQq() {
        SysUser user = newUser("lookup");
        user.setQq("777000333");
        userMapper.insert(user);

        assertEquals(user.getId(), userMapper.selectByUsername(PREFIX + "lookup").getId());
        assertEquals(user.getId(), userMapper.selectByQq("777000333").getId());
        assertNull(userMapper.selectByUsername(PREFIX + "nobody"), "查不到应当返回 null 而非抛异常");
    }

    @Test
    @DisplayName("封禁时版本号 +1，解封时不加")
    void updateStatus_bumpsOnlyWhenDisabling() {
        SysUser user = newUser("status");
        userMapper.insert(user);
        int before = user.getTokenVersion();

        userMapper.updateStatus(user.getId(), 0, 1);
        SysUser banned = userMapper.selectById(user.getId());
        assertEquals(0, banned.getStatus().intValue());
        assertEquals(before + 1, banned.getTokenVersion().intValue());

        userMapper.updateStatus(user.getId(), 1, 0);
        SysUser unbanned = userMapper.selectById(user.getId());
        assertEquals(1, unbanned.getStatus().intValue());
        assertEquals(before + 1, unbanned.getTokenVersion().intValue(),
                "解封不该再升版本号 —— 旧凭证在封禁那一刻就已经失效了");
    }

    @Test
    @DisplayName("改角色不升版本号：鉴权读库，改完立即生效")
    void updateRole_doesNotBumpTokenVersion() {
        SysUser user = newUser("role");
        userMapper.insert(user);
        int before = user.getTokenVersion();

        userMapper.updateRole(user.getId(), "ADMIN");

        SysUser loaded = userMapper.selectById(user.getId());
        assertEquals("ADMIN", loaded.getRole());
        assertEquals(before, loaded.getTokenVersion().intValue());
    }

    @Test
    @DisplayName("后台列表查询：关键字匹配登录名、昵称与 QQ 号")
    void selectAdminUserPage_matchesMultipleFields() {
        SysUser user = newUser("search");
        user.setNickname("搜索测试昵称");
        user.setQq("666000444");
        userMapper.insert(user);

        assertEquals(1, queryUsers(keywordOnly("搜索测试昵称")).getTotal(),
                "昵称也要能搜到 —— 运营未必记得住登录名");
        assertEquals(1, queryUsers(keywordOnly("666000444")).getTotal(), "QQ 号同理");
        assertTrue(queryUsers(keywordOnly(null)).getTotal() >= 1, "不传关键字表示查全部");
    }

    @Test
    @DisplayName("后台列表查询：累计在店时长只累加已结算的订单")
    void selectAdminUserPage_sumsStayMinutesOfSettledOrdersOnly() {
        SysUser user = newUser("stay");
        userMapper.insert(user);

        // 一条已结算的（90 分钟）
        jdbcTemplate.update("""
                INSERT INTO biz_order (order_no, user_id, store_id, lock_id, start_time, end_time,
                                       stay_minutes, status, payable_amount, total_amount,
                                       discount_amount, card_free_amount, created_at, updated_at, deleted)
                VALUES (?, ?, 1, 1, NOW(), NOW(), 90, 'PAID', 8.00, 8.00, 0.00, 0.00, NOW(), NOW(), 0)
                """, "IT-STAY-1", user.getId());
        // 一条还在店里的（stay_minutes 为 NULL）—— 它不该被算进去
        jdbcTemplate.update("""
                INSERT INTO biz_order (order_no, user_id, store_id, lock_id, start_time,
                                       status, payable_amount, total_amount,
                                       discount_amount, card_free_amount, created_at, updated_at, deleted)
                VALUES (?, ?, 1, 1, NOW(), 'IN_USE', 0.00, 0.00, 0.00, 0.00, NOW(), NOW(), 0)
                """, "IT-STAY-2", user.getId());

        AdminUserVo vo = queryUsers(keywordOnly("stay")).getRecords().get(0);

        assertEquals(90L, vo.getTotalStayMinutes(),
                "只累加已结算的 —— 还在玩的那一段尚未定局，计入的话刷新一次跳一次");
    }

    @Test
    @DisplayName("后台列表查询：月卡状态按「今天落在卡的有效期内」判定")
    void selectAdminUserPage_resolvesActiveCard() {
        SysUser withCard = newUser("hascard");
        userMapper.insert(withCard);
        jdbcTemplate.update("""
                INSERT INTO biz_monthly_card (card_no, user_id, card_type, price, start_date, end_date,
                                              status, created_at, updated_at, deleted)
                VALUES (?, ?, 'ALL_DAY', 600.00, DATE_SUB(CURDATE(), INTERVAL 1 DAY),
                        DATE_ADD(CURDATE(), INTERVAL 10 DAY), 'ACTIVE', NOW(), NOW(), 0)
                """, "IT-CARD-1", withCard.getId());

        assertEquals("ALL_DAY", queryUsers(keywordOnly("hascard")).getRecords().get(0).getCardType());

        // 按「有月卡」筛，能筛出他；按「没有」筛，筛不出
        AdminUserQuery hasCard = keywordOnly("hascard");
        hasCard.setHasCard(true);
        assertEquals(1, queryUsers(hasCard).getTotal());

        AdminUserQuery noCard = keywordOnly("hascard");
        noCard.setHasCard(false);
        assertEquals(0, queryUsers(noCard).getTotal(),
                "「只看没有月卡」必须真的把他排除掉 —— 两个方向都要对，"
                        + "只测一个方向的话，条件写反了也照样绿");
    }

    // ==================================================================
    // 本组辅助
    // ==================================================================

    /** 只带关键字的查询条件 */
    private static AdminUserQuery keywordOnly(String keyword) {
        AdminUserQuery query = new AdminUserQuery();
        query.setKeyword(keyword);
        return query;
    }

    /** 跑一次后台列表查询，取第一页 */
    private IPage<AdminUserVo> queryUsers(AdminUserQuery query) {
        return userMapper.selectAdminUserPage(new Page<>(1, 20), query, "u.id DESC");
    }

    @Test
    @DisplayName("累加订单消费额：order_paid 与 total_paid 两列同步增长，card_paid 不受影响")
    void addOrderPaidAmount_updatesBothColumns() {
        SysUser user = newUser("paid");
        userMapper.insert(user);

        userMapper.addOrderPaidAmount(user.getId(), new BigDecimal("22.00"));
        userMapper.addOrderPaidAmount(user.getId(), new BigDecimal("16.50"));

        SysUser loaded = userMapper.selectById(user.getId());
        assertEquals(0, new BigDecimal("38.50").compareTo(loaded.getOrderPaid()),
                "两次累加的结果应当是 38.50");
        assertEquals(0, new BigDecimal("38.50").compareTo(loaded.getTotalPaid()),
                "total_paid 是 order_paid 与 card_paid 之和的冗余列，必须同步 —— "
                        + "漏加一列不会报任何错，只会让展示的总额慢慢偏小");
        assertEquals(0, BigDecimal.ZERO.compareTo(loaded.getCardPaid()),
                "月卡充值不该被订单支付影响 —— 两列分开存就是为了让两类消费各自记账");
    }

    @Test
    @DisplayName("累加订单消费额：在既有金额上做相对累加，不覆盖")
    void addOrderPaidAmount_accumulatesOnExistingValue() {
        SysUser user = newUser("scale");
        user.setOrderPaid(new BigDecimal("100.00"));
        user.setTotalPaid(new BigDecimal("100.00"));
        userMapper.insert(user);

        userMapper.addOrderPaidAmount(user.getId(), new BigDecimal("0.05"));

        SysUser loaded = userMapper.selectById(user.getId());
        assertEquals(0, new BigDecimal("100.05").compareTo(loaded.getOrderPaid()),
                "用相对更新（order_paid = order_paid + x）而非先读后写 —— "
                        + "支付回调可能并发，先读后写会丢更新");
    }

    // ==================================================================
    // 测试辅助
    // ==================================================================

    /**
     * 构造一个符合库表约束的新用户（未插入）。
     *
     * <p>只填必填字段，其余留空由库表默认值或后续步骤补 ——
     * 这样每条用例都能看出「它依赖的是哪些字段」。
     *
     * @param suffix 用户名后缀，会拼上 {@link #PREFIX}
     * @return 用户实体
     */
    private static SysUser newUser(String suffix) {
        SysUser user = new SysUser();
        user.setUsername(PREFIX + suffix);
        user.setPasswordHash("$2a$10$integrationtestintegrationtestintegrationte");
        user.setNickname("集成测试");
        user.setRole("USER");
        user.setStatus(1);
        user.setTokenVersion(0);
        user.setOrderPaid(BigDecimal.ZERO);
        user.setCardPaid(BigDecimal.ZERO);
        user.setTotalPaid(BigDecimal.ZERO);
        return user;
    }
}
