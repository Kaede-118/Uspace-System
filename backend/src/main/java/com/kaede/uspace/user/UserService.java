package com.kaede.uspace.user;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.user.dto.AdminUserVo;
import com.kaede.uspace.user.dto.ChangePasswordRequest;
import com.kaede.uspace.user.dto.RegisterRequest;
import com.kaede.uspace.user.dto.UpdateProfileRequest;
import com.kaede.uspace.user.dto.UserProfileVo;
import com.kaede.uspace.user.entity.SysUser;
import com.kaede.uspace.user.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/**
 * 用户服务（模块 1）。
 *
 * <p>职责：注册、资料维护、密码修改、凭据校验，以及管理员侧的用户管理。
 * 具体规则见各方法注释。
 *
 * <p><b>本类不感知 JWT。</b>登录时「校验用户名密码」这一步由
 * {@link #verifyCredentials} 完成并返回用户实体，签发凭证是模块 2 的事。
 * 这条边界有两个用处：一是 Web 端与 QQ 机器人能复用同一套业务逻辑
 * （机器人从群消息取 QQ 号定位用户，全程不经过 HTTP 认证层）；
 * 二是密码哈希不会流到模块 2，那边只拿到「认证通过的用户」这一个结果。
 *
 * <p><b>参数错误抛异常，业务失败走返回值</b>：前者如「游玩偏好格式不对」，
 * 属于调用方给错了数据，抛 {@link IllegalArgumentException} 由全局异常处理器
 * 统一转成 400；后者如「用户名已被占用」，属于业务规则不接受，
 * 用 {@link BizResult#fail} 表达，调用方一眼能看出这是可预期的分支。
 *
 * <p><b>依赖注入用构造器而非 {@code @Autowired} 字段</b>：
 * 构造器注入让依赖在编译期就是 final 的，且不必启动 Spring 容器就能
 * 在单测里直接 {@code new UserService(假Mapper, 真编码器)} 组装出被测对象。
 */
@Slf4j
@Service
public class UserService {

    /** 新用户注册时的默认角色 */
    private static final String DEFAULT_ROLE = UserRole.USER.name();

    /** 账号正常状态 */
    private static final int STATUS_ENABLED = 1;

    /** 账号禁用状态 */
    private static final int STATUS_DISABLED = 0;

    private final SysUserMapper userMapper;

    /**
     * 密码编码器（BCrypt）。
     *
     * <p>Bean 定义在模块 2 的 {@code SecurityConfig} 里 —— 密码如何编码
     * 是认证策略的一部分，放在一处统一决定，模块 1 只负责「调用它」。
     * 这里依赖的是 Spring Security 的接口，不是 auth 包的类，
     * 因此不会形成 {@code user → auth} 的包依赖。
     */
    private final PasswordEncoder passwordEncoder;

    public UserService(SysUserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        log.info("[用户] 用户服务已就绪");
    }

    // ==================================================================
    // 注册与凭据校验
    // ==================================================================

    /**
     * 注册新用户。
     *
     * <p>校验项：用户名唯一、QQ 号唯一（若填写）、游玩偏好格式合法。
     * 新用户角色固定为 {@code USER}、状态为启用、token 版本号从 0 起 ——
     * <b>注册入口不能创建管理员</b>，否则任何人都能给自己开后台权限。
     * 管理员只能由既有管理员通过改角色接口任命，或由建表脚本预置。
     *
     * <p>这里是「先查重、再插入」，两步之间存在竞态窗口。真正兜底的是
     * 库上的唯一索引（{@code uk_username} / {@code uk_qq}）——
     * 并发冲突会抛 {@code DuplicateKeyException}，由全局异常处理器
     * 翻译成 409。应用层查重的作用是给出友好提示，不是保证唯一性。
     *
     * @param request 注册请求
     * @return 成功时返回新用户的资料视图；失败时返回对应的错误码
     * @throws IllegalArgumentException 游玩偏好格式不合法时抛出
     */
    @Transactional
    public BizResult<UserProfileVo> register(RegisterRequest request) {
        String username = request.getUsername().trim();

        if (userMapper.selectByUsername(username) != null) {
            return BizResult.fail(ErrorCode.USERNAME_EXISTS);
        }

        String qq = trimToNull(request.getQq());
        if (qq != null && userMapper.selectByQq(qq) != null) {
            return BizResult.fail(ErrorCode.QQ_ALREADY_BOUND);
        }

        String preference = trimToNull(request.getPreference());
        validatePreference(preference);

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setNickname(resolveNickname(request.getNickname(), qq, username));
        user.setPhone(trimToNull(request.getPhone()));
        user.setQq(qq);
        user.setPreference(preference);
        user.setRole(DEFAULT_ROLE);
        user.setStatus(STATUS_ENABLED);
        user.setTokenVersion(0);
        // 三个累计消费列在库里有 DEFAULT 0.00，但实体显式给 0 更清楚 ——
        // 免得读代码的人以为它们可能是 null
        user.setOrderPaid(BigDecimal.ZERO);
        user.setCardPaid(BigDecimal.ZERO);
        user.setTotalPaid(BigDecimal.ZERO);

        userMapper.insert(user);
        log.info("[用户] 注册成功 userId={} username={} nickname={}",
                user.getId(), user.getUsername(), user.getNickname());

        return BizResult.ok(UserProfileVo.from(user));
    }

    /**
     * 校验用户名与密码，供登录流程使用。
     *
     * <p><b>本方法不知道 JWT，也不签发任何东西</b> —— 它只回答
     * 「这组凭据对应哪个用户」，签发凭证由模块 2 的 {@code AuthService} 负责。
     *
     * <p>三条安全规则，实现时逐条对照：
     * <ol>
     *   <li><b>「用户不存在」与「密码错误」返回同一个错误码</b>。
     *       若分开返回，攻击者可以拿一个字典挨个试用户名，
     *       靠错误码的差异就能枚举出系统里有哪些账号</li>
     *   <li><b>「账号已禁用」必须在密码校验通过之后才返回</b>。
     *       若先判状态，那么「密码错」得到的是「密码错误」、
     *       「密码对但被禁用」得到的是「账号已禁用」，同样泄露了账号是否存在</li>
     *   <li><b>被逻辑删除的用户视同不存在</b>，走第 1 条的同一个错误码</li>
     * </ol>
     *
     * @param username    登录名
     * @param rawPassword 密码明文
     * @return 成功时 data 为认证通过的用户实体；失败时错误码为
     *         {@code BAD_CREDENTIALS}（用户名或密码错）或
     *         {@code ACCOUNT_DISABLED}（凭据正确但账号被禁用）
     */
    public BizResult<SysUser> verifyCredentials(String username, String rawPassword) {
        SysUser user = userMapper.selectByUsername(username);
        if (user == null || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            // 刻意不区分「用户不存在」与「密码错误」，理由见方法注释第 1 条
            return BizResult.fail(ErrorCode.BAD_CREDENTIALS);
        }
        if (!Integer.valueOf(STATUS_ENABLED).equals(user.getStatus())) {
            // 走到这里说明密码是对的，可以安全地告知「账号被禁用」
            return BizResult.fail(ErrorCode.ACCOUNT_DISABLED);
        }
        return BizResult.ok(user);
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 按 ID 查询用户实体。
     *
     * <p>供认证过滤器每次请求校验凭证时调用。已逻辑删除的用户由框架自动过滤掉，
     * 因此返回 null 既能表示「不存在」，也能表示「已删除」——
     * 对调用方而言这两种情况的处置相同。
     *
     * <p><b>不做缓存</b>：本项目规模下每请求一次主键查询开销可接受，
     * 而缓存会带来「封禁后多久生效」的问题 —— 用 TTL 过期就做不到立即生效，
     * 而要精确失效还不如不缓存。真到瓶颈时再说，届时也必须做写时失效。
     *
     * @param userId 用户 ID
     * @return 用户实体；不存在或已删除时返回 null
     */
    public SysUser findById(Long userId) {
        return userMapper.selectById(userId);
    }

    /**
     * 查询当前用户自己的资料。
     *
     * @param userId 用户 ID
     * @return 成功时返回资料视图；用户不存在时返回 {@code USER_NOT_FOUND}
     */
    public BizResult<UserProfileVo> getProfile(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            return BizResult.fail(ErrorCode.USER_NOT_FOUND);
        }
        return BizResult.ok(UserProfileVo.from(user));
    }

    // ==================================================================
    // 资料与密码维护
    // ==================================================================

    /**
     * 修改个人资料（全量替换语义）。
     *
     * <p>昵称留空时会按三级规则重新兜底，不会真的存成空 ——
     * 昵称是 QQ 群播报与后台列表的标识，空值会让播报失去意义。
     *
     * <p>改 QQ 号时会检查是否已被他人占用。注意<b>排除自己</b>：
     * 用户把 QQ 号原样提交（表单全量提交时很常见）不该被判定为冲突。
     *
     * @param userId  当前用户 ID
     * @param request 资料请求
     * @return 成功时返回更新后的资料视图
     * @throws IllegalArgumentException 游玩偏好格式不合法时抛出
     */
    @Transactional
    public BizResult<UserProfileVo> updateProfile(Long userId, UpdateProfileRequest request) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            return BizResult.fail(ErrorCode.USER_NOT_FOUND);
        }

        String qq = trimToNull(request.getQq());
        if (qq != null && !qq.equals(user.getQq())) {
            SysUser occupied = userMapper.selectByQq(qq);
            if (occupied != null && !occupied.getId().equals(userId)) {
                return BizResult.fail(ErrorCode.QQ_ALREADY_BOUND);
            }
        }

        String preference = trimToNull(request.getPreference());
        validatePreference(preference);

        String nickname = resolveNickname(request.getNickname(), qq, user.getUsername());

        userMapper.updateProfile(userId, nickname, trimToNull(request.getPhone()), qq, preference);
        log.info("[用户] 资料已更新 userId={} nickname={}", userId, nickname);

        // 重新查一次而不是在内存里改实体：updated_at 由 SQL 的 NOW() 填充，
        // 手工拼一个实体会让返回的更新时间与库里不一致
        SysUser updated = userMapper.selectById(userId);
        return BizResult.ok(UserProfileVo.from(updated));
    }

    /**
     * 修改密码。
     *
     * <p>成功后该用户的 token 版本号 +1，<b>所有已签发的凭证立即失效</b>，
     * 前端需要跳回登录页。这是刻意的：改密码的常见动机就是「怀疑密码泄露」，
     * 若旧凭证还能继续用，改密码就失去了意义。
     *
     * <p>要求新密码与原密码不同：改成一个相同的值会让用户误以为
     * 「已经改过了、别人被踢下线了」，而实际效果只是把自己也踢下线一次。
     *
     * @param userId  当前用户 ID
     * @param request 改密请求
     * @return 成功时 data 为 null；旧密码错误或新旧相同时返回对应的错误码
     */
    @Transactional
    public BizResult<Void> changePassword(Long userId, ChangePasswordRequest request) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            return BizResult.fail(ErrorCode.USER_NOT_FOUND);
        }
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPasswordHash())) {
            return BizResult.fail(ErrorCode.OLD_PASSWORD_MISMATCH);
        }
        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            return BizResult.fail(ErrorCode.PASSWORD_UNCHANGED);
        }

        userMapper.updatePasswordAndBumpVersion(userId, passwordEncoder.encode(request.getNewPassword()));
        log.info("[用户] 密码已修改并撤销该用户全部凭证 userId={}", userId);

        return BizResult.ok(null);
    }

    // ==================================================================
    // 管理员操作
    // ==================================================================

    /**
     * 分页查询用户列表，供运营后台使用。
     *
     * @param keyword 搜索关键字，匹配登录名 / 昵称 / QQ 号；可为空表示不限
     * @param pageNo  页码，从 1 开始
     * @param pageSize 每页条数，由分页插件的上限截断至 100
     * @return 分页结果
     */
    public BizResult<PageResult<AdminUserVo>> listUsers(String keyword, long pageNo, long pageSize) {
        IPage<SysUser> page = userMapper.selectPageByKeyword(
                new Page<>(pageNo, pageSize), trimToNull(keyword));
        return BizResult.ok(PageResult.of(page, AdminUserVo::from));
    }

    /**
     * 查询指定用户的详情，供运营后台使用。
     *
     * @param targetId 目标用户 ID
     * @return 成功时返回用户详情；不存在时返回 {@code USER_NOT_FOUND}
     */
    public BizResult<AdminUserVo> getUserDetail(Long targetId) {
        SysUser user = userMapper.selectById(targetId);
        if (user == null) {
            return BizResult.fail(ErrorCode.USER_NOT_FOUND);
        }
        return BizResult.ok(AdminUserVo.from(user));
    }

    /**
     * 启用或禁用用户。
     *
     * <p><b>禁用时会把 token 版本号 +1</b>，让该用户手上所有未过期的凭证立即失效 ——
     * 否则「禁用」只是挡住了下次登录，眼前这个凭证还能继续用，
     * 对「离场未付款就拉黑」这类场景毫无作用。
     * 启用时不升版本号：旧凭证在禁用那一刻就已经失效了，再升一次没有意义，
     * 还会把用户在其他设备上正常的登录态一并踢掉。
     *
     * <p><b>禁止管理员操作自己</b>：把自己禁用掉之后就再也登不进后台了，
     * 只能改库恢复。同理，改角色接口也禁止操作自己（防止把自己降级）。
     *
     * @param targetId   目标用户 ID
     * @param status     目标状态：1=启用，0=禁用
     * @param operatorId 操作者（管理员）ID
     * @return 成功时 data 为 null；目标是操作者自己时返回 {@code SELF_OPERATION_FORBIDDEN}
     */
    @Transactional
    public BizResult<Void> updateStatus(Long targetId, Integer status, Long operatorId) {
        if (targetId.equals(operatorId)) {
            return BizResult.fail(ErrorCode.SELF_OPERATION_FORBIDDEN);
        }
        SysUser target = userMapper.selectById(targetId);
        if (target == null) {
            return BizResult.fail(ErrorCode.USER_NOT_FOUND);
        }

        boolean disabling = STATUS_DISABLED == status;
        int affected = userMapper.updateStatus(targetId, status, disabling ? 1 : 0);
        if (affected == 0) {
            return BizResult.fail(ErrorCode.USER_NOT_FOUND);
        }

        log.info("[用户] 管理员 {} 将用户 {} 置为{}{}", operatorId, targetId,
                disabling ? "禁用" : "启用", disabling ? "，其全部凭证已撤销" : "");
        return BizResult.ok(null);
    }

    /**
     * 修改用户角色。
     *
     * <p><b>刻意不动 token 版本号</b>：鉴权时角色取自数据库而不是凭证载荷，
     * 所以改完立即生效 —— 升为管理员后刷新页面即可进后台，
     * 降为普通用户后也立即失去后台权限，不必等凭证过期，也不必把用户踢下线。
     *
     * <p>禁止操作自己：管理员把自己降成普通用户后就再也进不去后台了。
     *
     * @param targetId   目标用户 ID
     * @param role       目标角色名，取值 USER 或 ADMIN
     * @param operatorId 操作者（管理员）ID
     * @return 成功时 data 为 null；角色取值非法时返回 {@code ROLE_INVALID}
     */
    @Transactional
    public BizResult<Void> updateRole(Long targetId, String role, Long operatorId) {
        if (targetId.equals(operatorId)) {
            return BizResult.fail(ErrorCode.SELF_OPERATION_FORBIDDEN);
        }
        UserRole parsed = UserRole.parse(role).orElse(null);
        if (parsed == null) {
            return BizResult.fail(ErrorCode.ROLE_INVALID);
        }
        SysUser target = userMapper.selectById(targetId);
        if (target == null) {
            return BizResult.fail(ErrorCode.USER_NOT_FOUND);
        }

        int affected = userMapper.updateRole(targetId, parsed.name());
        if (affected == 0) {
            return BizResult.fail(ErrorCode.USER_NOT_FOUND);
        }

        log.info("[用户] 管理员 {} 将用户 {} 的角色改为 {}", operatorId, targetId, parsed.name());
        return BizResult.ok(null);
    }

    // ==================================================================
    // 内部工具方法
    // ==================================================================

    /**
     * 计算昵称：填了就用填的，没填则用 QQ 号，QQ 号也没有就退回用户名。
     *
     * <p>为什么昵称必须有个值：它要出现在 QQ 群的到店 / 离店播报与后台列表里，
     * 空昵称会让「谁在店里」这类播报失去意义。
     *
     * <p>为什么优先用 QQ 号而不是用户名：群播报的读者是群成员，
     * 他们认的是 QQ 号，而用户名只是为了登录方便起的一串字符。
     * 严格说 QQ 昵称比 QQ 号更贴合群里的称呼习惯，但注册是 Web 端行为，
     * 此时系统尚未接触过该用户，拿不到昵称 —— 由模块 11 在收到该用户群消息时
     * 回填昵称属于后续增强，不在本次范围内。
     *
     * @param nickname 用户填写的昵称，可为空
     * @param qq       QQ 号，可为空
     * @param username 登录名，兜底用，必不为空
     * @return 最终昵称
     */
    private static String resolveNickname(String nickname, String qq, String username) {
        String trimmed = trimToNull(nickname);
        if (trimmed != null) {
            return trimmed;
        }
        if (qq != null) {
            return qq;
        }
        return username;
    }

    /**
     * 校验游玩偏好的格式。
     *
     * <p>只校验格式（不能有空项、不能有重复项），<b>不校验 code 是否真实存在</b> ——
     * 那是模块 4 的字典表 {@code biz_equipment_type} 的职责。
     * 本次实现按「原样存取」处理，字典校验与前端选项一并留待模块 4。
     *
     * <p>格式问题抛异常而不是返回错误码：它属于「调用方给错了数据」，
     * 与「用户名已被占用」这类业务规则不接受的情况性质不同。
     *
     * @param preference 游玩偏好字符串，可为 null
     * @throws IllegalArgumentException 存在空项或重复项时抛出
     */
    private static void validatePreference(String preference) {
        if (preference == null) {
            return;
        }
        // 用 -1 作为 limit，保留末尾的空串，这样 "A," 这种写法能被检出
        String[] codes = preference.split(",", -1);
        Set<String> seen = new HashSet<>();
        for (String code : codes) {
            if (code.isBlank()) {
                throw new IllegalArgumentException("游玩偏好中存在空的类型代码，请检查是否多写了逗号");
            }
            if (!seen.add(code)) {
                throw new IllegalArgumentException("游玩偏好中存在重复的类型代码：" + code);
            }
        }
    }

    /**
     * 去除首尾空白，全空白视为 null。
     *
     * <p>表单提交里「只敲了几个空格」是很常见的情况，
     * 若原样存进去，展示时看起来是空的、判定时又不等于 null，
     * 会在各处制造难以理解的边界。统一在此处收敛成 null。
     *
     * @param value 原始值
     * @return 去空白后的值；原值为 null 或全空白时返回 null
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
