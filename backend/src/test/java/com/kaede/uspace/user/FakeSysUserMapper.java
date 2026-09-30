package com.kaede.uspace.user;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kaede.uspace.user.entity.SysUser;
import com.kaede.uspace.user.mapper.SysUserMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存版的 {@link SysUserMapper}，让 Service 层的单元测试不依赖数据库。
 *
 * <p><b>为什么用动态代理而不是手写一个实现类</b>：{@code BaseMapper} 上有三十多个方法，
 * 而 {@code UserService} 只用到其中九个。手写实现意味着为二十多个用不到的方法
 * 写一堆空壳，既啰嗦又容易漏；动态代理只处理真正被调用的方法，
 * 遇到没实现的方法直接抛异常并给出提示 ——
 * 这反而成了一层保护：Service 一旦用了预期之外的方法，
 * 测试会立刻失败并告诉你该补哪个。
 *
 * <p>它模拟的是数据库行为的<b>语义</b>而非实现：逻辑删除过滤、
 * 唯一约束对应的查重、版本号的相对自增。这些都是 Service 层判断的依据，
 * 假实现必须与真实库保持一致，否则单测通过的结论就不可信了。
 *
 * <p><b>不模拟的东西</b>：SQL 是否正确、列名映射是否对得上、审计字段能否真的自动填充 ——
 * 这些只有连真库才能验证，由 {@code SysUserMapperIntegrationTests} 负责。
 * 两者分工明确：本类保证业务规则对，集成测试保证持久化对。
 *
 * <p><b>声明为 public</b>：模块 2（{@code auth} 包）的测试也要用它 ——
 * {@code AuthService} 的登录流程需要真实的 {@code UserService} 配合，
 * 而那又需要一份数据访问层。放在 {@code user} 包是因为它模拟的正是
 * {@code SysUserMapper}，语义上属于这里。
 */
public class FakeSysUserMapper implements InvocationHandler {

    /** 模拟数据表。用 LinkedHashMap 保持插入顺序，便于调试时观察 */
    private final Map<Long, SysUser> rows = new LinkedHashMap<>();

    /** 模拟自增主键 */
    private long nextId = 1;

    /**
     * 生成接口代理，交给 Service 使用。
     *
     * @return SysUserMapper 的假实现
     */
    public SysUserMapper asMapper() {
        return (SysUserMapper) Proxy.newProxyInstance(
                SysUserMapper.class.getClassLoader(),
                new Class<?>[]{SysUserMapper.class},
                this);
    }

    /**
     * 预置一条用户数据，模拟「库里已经有这个人」。
     *
     * @param user 用户，id 为空时自动分配
     * @return 同一个对象，便于链式构造
     */
    public SysUser seed(SysUser user) {
        if (user.getId() == null) {
            user.setId(nextId++);
        }
        if (user.getDeleted() == null) {
            user.setDeleted(0);
        }
        rows.put(user.getId(), user);
        return user;
    }

    /**
     * 按 ID 取出表中的当前状态，供测试断言。
     *
     * <p>取出的是同一个对象引用，因此测试看到的就是 Service 改过之后的样子。
     * 刻意不做拷贝：拷贝会让「Service 到底改了哪些字段」变得难以观察。
     *
     * @param id 用户 ID
     * @return 用户；不存在时返回 null
     */
    public SysUser get(Long id) {
        return rows.get(id);
    }

    /**
     * 方法分发。方法名唯一，所以按名字匹配即可。
     *
     * @param proxy  代理对象（未使用）
     * @param method 被调用的方法
     * @param args   调用参数
     * @return 方法返回值
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "insert" -> insert((SysUser) args[0]);
            case "selectById" -> selectById((Long) args[0]);
            case "selectByUsername" -> selectByUsername((String) args[0]);
            case "selectByQq" -> selectByQq((String) args[0]);
            case "updateProfile" -> updateProfile(args);
            case "updatePasswordAndBumpVersion" -> updatePasswordAndBumpVersion(args);
            case "updateStatus" -> updateStatus(args);
            case "updateRole" -> updateRole(args);
            case "selectPageByKeyword" -> selectPageByKeyword(args);
            case "addOrderPaidAmount" -> addOrderPaidAmount(args);
            case "addCardPaidAmount" -> addCardPaidAmount(args);
            default -> throw new UnsupportedOperationException(
                    "假 Mapper 未实现方法 " + method.getName()
                            + " —— 出现这个错误说明 Service 调用了预期之外的方法，"
                            + "请在 FakeSysUserMapper 中补上对应实现");
        };
    }

    // ------------------------------------------------------------------
    // 以下是各方法的内存实现
    // ------------------------------------------------------------------

    /**
     * 插入用户，模拟自增主键的分配。
     *
     * @param user 待插入的用户
     * @return 受影响行数，恒为 1
     */
    private int insert(SysUser user) {
        if (user.getId() == null) {
            user.setId(nextId++);
        }
        if (user.getDeleted() == null) {
            user.setDeleted(0);
        }
        rows.put(user.getId(), user);
        return 1;
    }

    /**
     * 累加订单消费额。
     *
     * <p>复刻真实 SQL 的关键点：<b>一条语句里同时累加两列</b>
     * （{@code order_paid} 与 {@code total_paid}），且用相对更新。
     * 假实现若只加一列，就测不出「两列同源」这条约定 ——
     * 而真实环境里漏加一列不会有任何报错，只会让展示的总额慢慢偏小。
     *
     * @param args 依次为用户 ID、金额
     * @return 受影响行数；0 表示用户不存在或已删除
     */
    private int addOrderPaidAmount(Object[] args) {
        SysUser user = selectById((Long) args[0]);
        if (user == null) {
            return 0;
        }
        BigDecimal amount = (BigDecimal) args[1];
        user.setOrderPaid(nullToZero(user.getOrderPaid()).add(amount));
        user.setTotalPaid(nullToZero(user.getTotalPaid()).add(amount));
        return 1;
    }

    /**
     * 累加月卡充值额。
     *
     * <p>与 {@link #addOrderPaidAmount} 分开实现，且<b>刻意不去动 {@code orderPaid}</b> ——
     * 两个累计口径的区别（房间消费 vs 卡费）正是这两个方法存在的理由，
     * 假实现若图省事复用同一段，支付侧的「按品类分派」就测不出来了。
     *
     * @param args 依次为用户 ID、金额
     * @return 受影响行数；0 表示用户不存在或已删除
     */
    private int addCardPaidAmount(Object[] args) {
        SysUser user = selectById((Long) args[0]);
        if (user == null) {
            return 0;
        }
        BigDecimal amount = (BigDecimal) args[1];
        user.setCardPaid(nullToZero(user.getCardPaid()).add(amount));
        user.setTotalPaid(nullToZero(user.getTotalPaid()).add(amount));
        return 1;
    }

    /**
     * 把可能为 null 的金额归零。
     *
     * <p>真库上这三列是 {@code NOT NULL DEFAULT 0}，不会为 null；
     * 但假实现里的对象是测试手工构造的，字段可能没设 —— 归零比抛 NPE 好排障。
     *
     * @param value 金额
     * @return 非 null 的金额
     */
    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * 按主键查询，模拟逻辑删除过滤 —— 已删除的行视同不存在。
     *
     * @param id 用户 ID
     * @return 用户；不存在或已逻辑删除时返回 null
     */
    private SysUser selectById(Long id) {
        SysUser user = rows.get(id);
        return isVisible(user) ? user : null;
    }

    /**
     * 按登录名查询。
     *
     * @param username 登录名
     * @return 用户；不存在时返回 null
     */
    private SysUser selectByUsername(String username) {
        return rows.values().stream()
                .filter(FakeSysUserMapper::isVisible)
                .filter(u -> u.getUsername().equals(username))
                .findFirst()
                .orElse(null);
    }

    /**
     * 按 QQ 号查询。
     *
     * @param qq QQ 号
     * @return 用户；不存在时返回 null
     */
    private SysUser selectByQq(String qq) {
        return rows.values().stream()
                .filter(FakeSysUserMapper::isVisible)
                .filter(u -> qq.equals(u.getQq()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 更新资料。模拟 PUT 的全量替换语义 —— 传 null 即写空。
     *
     * <p>这一点与真实实现（显式 SQL）保持一致很关键：若假实现改成
     * 「null 就跳过」，那么「清空手机号」这条路径在单测里会通过、
     * 到了真库上却写不进去，单测就失去了意义。
     *
     * @param args [id, nickname, phone, qq, preference]
     * @return 受影响行数
     */
    private int updateProfile(Object[] args) {
        SysUser user = selectById((Long) args[0]);
        if (user == null) {
            return 0;
        }
        user.setNickname((String) args[1]);
        user.setPhone((String) args[2]);
        user.setQq((String) args[3]);
        user.setPreference((String) args[4]);
        return 1;
    }

    /**
     * 更新密码并相对自增版本号。
     *
     * @param args [id, passwordHash]
     * @return 受影响行数
     */
    private int updatePasswordAndBumpVersion(Object[] args) {
        SysUser user = selectById((Long) args[0]);
        if (user == null) {
            return 0;
        }
        user.setPasswordHash((String) args[1]);
        user.setTokenVersion(user.getTokenVersion() + 1);
        return 1;
    }

    /**
     * 更新状态并按增量调整版本号。
     *
     * @param args [id, status, bumpDelta]
     * @return 受影响行数
     */
    private int updateStatus(Object[] args) {
        SysUser user = selectById((Long) args[0]);
        if (user == null) {
            return 0;
        }
        user.setStatus((Integer) args[1]);
        user.setTokenVersion(user.getTokenVersion() + (Integer) args[2]);
        return 1;
    }

    /**
     * 更新角色。刻意不动版本号 —— 与真实 SQL 保持一致。
     *
     * @param args [id, role]
     * @return 受影响行数
     */
    private int updateRole(Object[] args) {
        SysUser user = selectById((Long) args[0]);
        if (user == null) {
            return 0;
        }
        user.setRole((String) args[1]);
        return 1;
    }

    /**
     * 按关键字分页查询。只做关键字过滤与倒序，不做真正的分页切片 ——
     * 单测关心的是「筛选结果对不对」，分页切片由 MyBatis-Plus 的插件保证。
     *
     * @param args [IPage, keyword]
     * @return 装好记录的分页对象
     */
    @SuppressWarnings("unchecked")
    private IPage<SysUser> selectPageByKeyword(Object[] args) {
        IPage<SysUser> page = (IPage<SysUser>) args[0];
        String keyword = (String) args[1];

        List<SysUser> matched = rows.values().stream()
                .filter(FakeSysUserMapper::isVisible)
                .filter(u -> matchesKeyword(u, keyword))
                .sorted(Comparator.comparing(SysUser::getId).reversed())
                .toList();

        page.setRecords(matched);
        page.setTotal(matched.size());
        return page;
    }

    /**
     * 判断用户是否可见（未被逻辑删除）。
     *
     * @param user 用户
     * @return 可见返回 true
     */
    private static boolean isVisible(SysUser user) {
        return user != null && !Integer.valueOf(1).equals(user.getDeleted());
    }

    /**
     * 判断用户是否命中关键字，匹配登录名、昵称与 QQ 号。
     *
     * @param user    用户
     * @param keyword 关键字，为空表示全部命中
     * @return 命中返回 true
     */
    private static boolean matchesKeyword(SysUser user, String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return true;
        }
        return contains(user.getUsername(), keyword)
                || contains(user.getNickname(), keyword)
                || contains(user.getQq(), keyword);
    }

    /**
     * 空安全的子串判断。
     *
     * @param value   被搜索的字符串，可为 null
     * @param keyword 关键字
     * @return 包含返回 true
     */
    private static boolean contains(String value, String keyword) {
        return value != null && value.contains(keyword);
    }
}
