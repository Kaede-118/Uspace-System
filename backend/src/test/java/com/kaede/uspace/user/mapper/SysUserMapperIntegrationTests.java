package com.kaede.uspace.user.mapper;

import com.kaede.uspace.user.entity.SysUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
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
        userMapper.insert(user);

        SysUser loaded = userMapper.selectById(user.getId());

        assertNotNull(loaded, "刚插入的行必须能查回来");
        assertEquals(user.getUsername(), loaded.getUsername());
        assertEquals("列名测试", loaded.getNickname());
        assertEquals("13800138000", loaded.getPhone());
        assertEquals("999000111", loaded.getQq());
        assertEquals("PAIPAI,TAISHOU", loaded.getPreference());
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
    @DisplayName("关键字分页查询：匹配登录名、昵称与 QQ 号")
    void selectPageByKeyword_matchesMultipleFields() {
        SysUser user = newUser("search");
        user.setNickname("搜索测试昵称");
        user.setQq("666000444");
        userMapper.insert(user);

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<SysUser> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);

        assertEquals(1, userMapper.selectPageByKeyword(page, "搜索测试昵称").getTotal(),
                "昵称也要能搜到 —— 运营未必记得住登录名");
        assertEquals(1, userMapper.selectPageByKeyword(page, "666000444").getTotal(),
                "QQ 号同理");
        assertTrue(userMapper.selectPageByKeyword(page, null).getTotal() >= 1,
                "不传关键字表示查全部");
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
