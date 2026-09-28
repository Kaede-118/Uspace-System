package com.kaede.uspace.user;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.user.dto.AdminUserVo;
import com.kaede.uspace.user.dto.ChangePasswordRequest;
import com.kaede.uspace.user.dto.RegisterRequest;
import com.kaede.uspace.user.dto.UpdateProfileRequest;
import com.kaede.uspace.user.dto.UserProfileVo;
import com.kaede.uspace.user.entity.SysUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UserService} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring、不连数据库</b> —— 数据访问由
 * {@link FakeSysUserMapper} 在内存里顶替。这样跑得快，也不必为了测一条
 * 业务规则先去准备数据库。
 *
 * <p>覆盖的重点是<b>安全性质</b>而非功能是否跑通：
 * 密码不能明文落库、登录失败不能泄露账号是否存在、
 * 改密与封禁必须撤销旧凭证、解封与改角色则不该撤销。
 * 这些规则写错了不会报错，只会静默地让系统变得不安全 ——
 * 所以必须有用例钉住。
 *
 * <p>BCrypt 的强度参数取 4（生产用 10）：强度越高哈希越慢，
 * 生产上这点延迟换来的是离线爆破成本的指数上升，而测试里只关心
 * 「加密与校验能不能对上」，用最低强度让测试跑得快些。
 */
class UserServiceTests {

    /** 测试用户名 */
    private static final String USERNAME = "xiaofeng";

    /** 测试密码。长度满足注册接口的 8 位下限 */
    private static final String RAW_PASSWORD = "Test@1234";

    /** 另一个合法密码，用于改密场景 */
    private static final String NEW_PASSWORD = "NewPass@5678";

    /** 内存版数据访问层 */
    private final FakeSysUserMapper fakeMapper = new FakeSysUserMapper();

    /** 密码编码器。强度 4 是为了让测试跑得快，生产用 10 */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    /** 被测服务 */
    private final UserService userService = new UserService(fakeMapper.asMapper(), passwordEncoder);

    // ==================================================================
    // 注册
    // ==================================================================

    @Test
    @DisplayName("注册：库里存的是 BCrypt 哈希，绝不是明文")
    void register_storesBcryptHashNotPlaintext() {
        BizResult<UserProfileVo> result = register(USERNAME);

        assertTrue(result.isSuccess(), "正常注册应当成功");
        SysUser stored = fakeMapper.get(result.getData().getId());

        assertNotEquals(RAW_PASSWORD, stored.getPasswordHash(), "密码绝不能明文落库");
        assertTrue(stored.getPasswordHash().startsWith("$2"),
                "应当是 BCrypt 格式的哈希（以 $2 开头），而不是 MD5 / SHA 一类的快速哈希");
        assertTrue(passwordEncoder.matches(RAW_PASSWORD, stored.getPasswordHash()),
                "哈希必须能验过原密码，否则用户永远登不进来");
    }

    @Test
    @DisplayName("注册：角色默认 USER、状态启用、版本号从 0 起")
    void register_appliesDefaults() {
        BizResult<UserProfileVo> result = register(USERNAME);
        SysUser stored = fakeMapper.get(result.getData().getId());

        assertEquals("USER", stored.getRole(),
                "注册入口绝不能创建管理员 —— 否则任何人都能给自己开后台权限");
        assertEquals(1, stored.getStatus(), "新用户应当是启用状态");
        assertEquals(0, stored.getTokenVersion(),
                "版本号从 0 起，这是「封禁后旧凭证失效」这套机制能够成立的前提");
        assertEquals(0, BigDecimal.ZERO.compareTo(stored.getTotalPaid()),
                "累计消费初始为 0");
    }

    @Test
    @DisplayName("注册：用户名已被占用时返回冲突错误码")
    void register_failsWhenUsernameTaken() {
        register(USERNAME);

        BizResult<UserProfileVo> again = register(USERNAME);

        assertFalse(again.isSuccess());
        assertEquals(ErrorCode.USERNAME_EXISTS, again.getError());
    }

    @Test
    @DisplayName("注册：QQ 号已被他人绑定时返回冲突错误码")
    void register_failsWhenQqAlreadyBound() {
        RegisterRequest first = newRequest("userA");
        first.setQq("10001");
        userService.register(first);

        RegisterRequest second = newRequest("userB");
        second.setQq("10001");
        BizResult<UserProfileVo> result = userService.register(second);

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.QQ_ALREADY_BOUND, result.getError(),
                "QQ 号重号会让机器人的查询指向不确定的人，必须拒绝");
    }

    @Test
    @DisplayName("注册：昵称三级兜底 —— 显式填写的优先")
    void register_keepsExplicitNickname() {
        RegisterRequest request = newRequest(USERNAME);
        request.setNickname("小枫");
        request.setQq("10001");

        BizResult<UserProfileVo> result = userService.register(request);

        assertEquals("小枫", result.getData().getNickname(), "填了就用填的");
    }

    @Test
    @DisplayName("注册：昵称三级兜底 —— 留空则用 QQ 号")
    void register_fallsBackToQqWhenNicknameBlank() {
        RegisterRequest request = newRequest(USERNAME);
        request.setQq("10001");
        // nickname 留空

        BizResult<UserProfileVo> result = userService.register(request);

        assertEquals("10001", result.getData().getNickname(),
                "群播报的读者认 QQ 号，留空时用它比用登录名更贴合群里的称呼习惯");
    }

    @Test
    @DisplayName("注册：昵称三级兜底 —— QQ 也没有才退回用户名")
    void register_fallsBackToUsernameWhenBothBlank() {
        BizResult<UserProfileVo> result = register(USERNAME);

        assertEquals(USERNAME, result.getData().getNickname(),
                "昵称不能为空 —— 空昵称会让「谁在店里」这类播报失去意义");
    }

    @Test
    @DisplayName("注册：游玩偏好存在空项时拒绝")
    void register_rejectsPreferenceWithBlankItem() {
        RegisterRequest request = newRequest(USERNAME);
        request.setPreference("PAIPAI,,TAISHOU");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> userService.register(request));
        assertTrue(ex.getMessage().contains("空"), "错误消息要说清是哪种格式问题，便于排查");
    }

    @Test
    @DisplayName("注册：游玩偏好存在重复项时拒绝")
    void register_rejectsPreferenceWithDuplicate() {
        RegisterRequest request = newRequest(USERNAME);
        request.setPreference("PAIPAI,TAISHOU,PAIPAI");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> userService.register(request));
        assertTrue(ex.getMessage().contains("PAIPAI"), "错误消息应当指出是哪个 code 重复了");
    }

    @Test
    @DisplayName("注册：游玩偏好留空是合法状态")
    void register_allowsBlankPreference() {
        RegisterRequest request = newRequest(USERNAME);
        request.setPreference("  ");

        BizResult<UserProfileVo> result = userService.register(request);

        assertTrue(result.isSuccess(), "没选偏好是正常的，不该被拒绝");
        assertNull(fakeMapper.get(result.getData().getId()).getPreference(),
                "纯空白的输入应当收敛成 null，而不是存一个空串进去");
    }

    // ==================================================================
    // 凭据校验：三条安全规则
    // ==================================================================

    @Test
    @DisplayName("凭据校验：用户不存在时返回「用户名或密码错误」，不暴露账号是否存在")
    void verifyCredentials_hidesUserNotFound() {
        BizResult<SysUser> result = userService.verifyCredentials("nobody", RAW_PASSWORD);

        assertEquals(ErrorCode.BAD_CREDENTIALS, result.getError(),
                "若单独返回「用户不存在」，攻击者就能靠错误码差异枚举出系统里有哪些账号");
    }

    @Test
    @DisplayName("凭据校验：密码错误时返回同一个错误码")
    void verifyCredentials_rejectsWrongPassword() {
        register(USERNAME);

        BizResult<SysUser> result = userService.verifyCredentials(USERNAME, "WrongPass@999");

        assertEquals(ErrorCode.BAD_CREDENTIALS, result.getError());
    }

    @Test
    @DisplayName("凭据校验：密码正确但账号被禁用，才返回「账号已禁用」")
    void verifyCredentials_reportsDisabledWhenPasswordCorrect() {
        Long userId = register(USERNAME).getData().getId();
        fakeMapper.get(userId).setStatus(0);

        BizResult<SysUser> result = userService.verifyCredentials(USERNAME, RAW_PASSWORD);

        assertEquals(ErrorCode.ACCOUNT_DISABLED, result.getError(),
                "密码对了才能告诉用户「账号被禁用」，此时他已证明自己是账号主人");
    }

    @Test
    @DisplayName("凭据校验：密码错误 + 账号被禁用时，返回的仍是「密码错误」")
    void verifyCredentials_checksPasswordBeforeStatus() {
        Long userId = register(USERNAME).getData().getId();
        fakeMapper.get(userId).setStatus(0);

        BizResult<SysUser> result = userService.verifyCredentials(USERNAME, "WrongPass@999");

        assertEquals(ErrorCode.BAD_CREDENTIALS, result.getError(),
                "先判状态会把「这个账号存在且被禁用了」泄露给不知道密码的人");
    }

    // ==================================================================
    // 修改密码：必须撤销旧凭证
    // ==================================================================

    @Test
    @DisplayName("改密：原密码不正确时拒绝")
    void changePassword_rejectsWrongOldPassword() {
        Long userId = register(USERNAME).getData().getId();

        BizResult<Void> result = userService.changePassword(userId, changePasswordRequest("WrongPass@999"));

        assertEquals(ErrorCode.OLD_PASSWORD_MISMATCH, result.getError());
        assertTrue(passwordEncoder.matches(RAW_PASSWORD, fakeMapper.get(userId).getPasswordHash()),
                "失败时密码必须保持不变");
    }

    @Test
    @DisplayName("改密：成功后 token 版本号 +1 —— 这是「把别人踢下线」的实现方式")
    void changePassword_bumpsTokenVersion() {
        Long userId = register(USERNAME).getData().getId();
        int before = fakeMapper.get(userId).getTokenVersion();

        BizResult<Void> result = userService.changePassword(userId, changePasswordRequest(RAW_PASSWORD));

        assertTrue(result.isSuccess());
        assertEquals(before + 1, fakeMapper.get(userId).getTokenVersion(),
                "不升版本号的话，改完密码旧凭证还能继续用，改密码就白改了");
        assertTrue(passwordEncoder.matches(NEW_PASSWORD, fakeMapper.get(userId).getPasswordHash()),
                "新密码必须生效");
        assertFalse(passwordEncoder.matches(RAW_PASSWORD, fakeMapper.get(userId).getPasswordHash()),
                "旧密码必须失效");
    }

    @Test
    @DisplayName("改密：新密码与原密码相同时拒绝")
    void changePassword_rejectsSamePassword() {
        Long userId = register(USERNAME).getData().getId();

        // 新旧密码都填原密码，模拟「改成一个和原来一样的值」
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword(RAW_PASSWORD);
        request.setNewPassword(RAW_PASSWORD);
        BizResult<Void> result = userService.changePassword(userId, request);

        assertEquals(ErrorCode.PASSWORD_UNCHANGED, result.getError(),
                "改成一个相同的值会让用户误以为已经生效，实际只是把自己也踢下线了一次");
    }

    // ==================================================================
    // 修改资料
    // ==================================================================

    @Test
    @DisplayName("改资料：全量替换语义 —— 传 null 即清空该项")
    void updateProfile_canClearOptionalFields() {
        RegisterRequest request = newRequest(USERNAME);
        request.setPhone("13800138000");
        request.setQq("10001");
        Long userId = userService.register(request).getData().getId();

        UpdateProfileRequest update = new UpdateProfileRequest();
        update.setNickname("小枫");
        // phone 与 qq 都留空，表示清空
        BizResult<UserProfileVo> result = userService.updateProfile(userId, update);

        assertTrue(result.isSuccess());
        assertNull(fakeMapper.get(userId).getPhone(), "PUT 是全量替换，不传即清空");
        assertNull(fakeMapper.get(userId).getQq());
        assertEquals("小枫", fakeMapper.get(userId).getNickname());
    }

    @Test
    @DisplayName("改资料：QQ 号被他人占用时拒绝")
    void updateProfile_rejectsQqTakenByAnother() {
        RegisterRequest owner = newRequest("userA");
        owner.setQq("10001");
        userService.register(owner);

        Long userId = register("userB").getData().getId();
        UpdateProfileRequest update = new UpdateProfileRequest();
        update.setNickname("userB");
        update.setQq("10001");

        BizResult<UserProfileVo> result = userService.updateProfile(userId, update);

        assertEquals(ErrorCode.QQ_ALREADY_BOUND, result.getError());
    }

    @Test
    @DisplayName("改资料：原样提交自己的 QQ 号不应被判定为冲突")
    void updateProfile_allowsKeepingOwnQq() {
        RegisterRequest request = newRequest(USERNAME);
        request.setQq("10001");
        Long userId = userService.register(request).getData().getId();

        UpdateProfileRequest update = new UpdateProfileRequest();
        update.setNickname("小枫");
        update.setQq("10001");
        BizResult<UserProfileVo> result = userService.updateProfile(userId, update);

        assertTrue(result.isSuccess(),
                "表单全量提交时会把当前值一起带上，排除掉自己才不会误报冲突");
    }

    @Test
    @DisplayName("改资料：昵称留空时重新按三级规则兜底，不会真的存成空")
    void updateProfile_refillsNicknameWhenBlank() {
        RegisterRequest request = newRequest(USERNAME);
        request.setQq("10001");
        Long userId = userService.register(request).getData().getId();

        UpdateProfileRequest update = new UpdateProfileRequest();
        update.setNickname("   ");
        // qq 也清空，于是只能退回用户名
        BizResult<UserProfileVo> result = userService.updateProfile(userId, update);

        assertEquals(USERNAME, result.getData().getNickname(),
                "昵称是播报与后台列表的标识，清空它会让播报失去意义");
    }

    // ==================================================================
    // 管理员操作
    // ==================================================================

    @Test
    @DisplayName("封禁：token 版本号 +1，旧凭证立即失效")
    void updateStatus_banBumpsTokenVersion() {
        Long userId = register(USERNAME).getData().getId();
        int before = fakeMapper.get(userId).getTokenVersion();

        BizResult<Void> result = userService.updateStatus(userId, 0, 999L);

        assertTrue(result.isSuccess());
        assertEquals(0, fakeMapper.get(userId).getStatus());
        assertEquals(before + 1, fakeMapper.get(userId).getTokenVersion(),
                "不升版本号的话，用户虽然登不进来，但手上那个未过期的凭证还能继续调接口");
    }

    @Test
    @DisplayName("解封：不升版本号 —— 旧凭证在封禁时就已经死了")
    void updateStatus_unbanKeepsTokenVersion() {
        Long userId = register(USERNAME).getData().getId();
        userService.updateStatus(userId, 0, 999L);
        int afterBan = fakeMapper.get(userId).getTokenVersion();

        BizResult<Void> result = userService.updateStatus(userId, 1, 999L);

        assertTrue(result.isSuccess());
        assertEquals(1, fakeMapper.get(userId).getStatus());
        assertEquals(afterBan, fakeMapper.get(userId).getTokenVersion(),
                "再升一次没有意义，还会把用户在其他设备上的正常登录态一并踢掉");
    }

    @Test
    @DisplayName("封禁：禁止操作自己")
    void updateStatus_rejectsSelfOperation() {
        Long adminId = register("admin1").getData().getId();

        BizResult<Void> result = userService.updateStatus(adminId, 0, adminId);

        assertEquals(ErrorCode.SELF_OPERATION_FORBIDDEN, result.getError(),
                "把自己禁用掉之后就再也进不去后台了，只能改库恢复");
        assertEquals(1, fakeMapper.get(adminId).getStatus(), "状态必须保持不变");
    }

    @Test
    @DisplayName("改角色：取值不合法时拒绝")
    void updateRole_rejectsUnknownRole() {
        Long userId = register(USERNAME).getData().getId();

        BizResult<Void> result = userService.updateRole(userId, "SUPER_ADMIN", 999L);

        assertEquals(ErrorCode.ROLE_INVALID, result.getError());
        assertEquals("USER", fakeMapper.get(userId).getRole(), "非法取值不该改动任何数据");
    }

    @Test
    @DisplayName("改角色：禁止操作自己")
    void updateRole_rejectsSelfOperation() {
        Long adminId = register("admin1").getData().getId();

        BizResult<Void> result = userService.updateRole(adminId, "USER", adminId);

        assertEquals(ErrorCode.SELF_OPERATION_FORBIDDEN, result.getError(),
                "管理员把自己降级后就再也进不去后台了");
    }

    @Test
    @DisplayName("改角色：不升版本号 —— 鉴权读库，改完立即生效")
    void updateRole_doesNotBumpTokenVersion() {
        Long userId = register(USERNAME).getData().getId();
        int before = fakeMapper.get(userId).getTokenVersion();

        BizResult<Void> result = userService.updateRole(userId, "ADMIN", 999L);

        assertTrue(result.isSuccess());
        assertEquals("ADMIN", fakeMapper.get(userId).getRole());
        assertEquals(before, fakeMapper.get(userId).getTokenVersion(),
                "角色取自数据库，改完立即生效，没必要把用户踢下线");
    }

    @Test
    @DisplayName("查询：已逻辑删除的用户视同不存在")
    void findById_returnsNullForDeletedUser() {
        Long userId = register(USERNAME).getData().getId();
        fakeMapper.get(userId).setDeleted(1);

        assertNull(userService.findById(userId),
                "已删除的用户不该还能用凭证访问 —— 否则删号只是个摆设");
    }

    @Test
    @DisplayName("列表：关键字匹配登录名 / 昵称 / QQ 号")
    void listUsers_matchesKeywordAcrossFields() {
        RegisterRequest a = newRequest("alice");
        a.setNickname("爱丽丝");
        a.setQq("20001");
        userService.register(a);
        register("bob");

        assertEquals(1, userService.listUsers("alice", 1, 10).getData().getTotal());
        assertEquals(1, userService.listUsers("爱丽丝", 1, 10).getData().getTotal(),
                "运营未必记得住登录名，昵称也要能搜到");
        assertEquals(1, userService.listUsers("20001", 1, 10).getData().getTotal());
        assertEquals(2, userService.listUsers(null, 1, 10).getData().getTotal(),
                "不传关键字表示查全部");
    }

    @Test
    @DisplayName("列表：对外视图不含密码哈希")
    void listUsers_doesNotExposePasswordHash() {
        register(USERNAME);

        AdminUserVo vo = userService.listUsers(null, 1, 10).getData().getRecords().get(0);

        assertNotNull(vo.getUsername());
        // AdminUserVo 里压根没有密码字段 —— 这条用例的价值在于：若将来有人
        // 图省事把实体直接返回，它会在编译期就失败（类型不匹配），不会静默泄露
        assertFalse(vo.toString().contains("$2a$"),
                "响应的任何角落都不该出现密码哈希的痕迹");
    }

    // ==================================================================
    // 测试辅助
    // ==================================================================

    /**
     * 构造一个只填了用户名与密码的注册请求。
     *
     * @param username 用户名
     * @return 注册请求
     */
    private static RegisterRequest newRequest(String username) {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setPassword(RAW_PASSWORD);
        return request;
    }

    /**
     * 按用户名注册一个用户，密码用默认测试密码。
     *
     * @param username 用户名
     * @return 注册结果
     */
    private BizResult<UserProfileVo> register(String username) {
        return userService.register(newRequest(username));
    }

    /**
     * 构造改密请求。
     *
     * @param oldPassword 原密码
     * @return 改密请求，新密码固定为 {@link #NEW_PASSWORD}
     */
    private static ChangePasswordRequest changePasswordRequest(String oldPassword) {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword(oldPassword);
        request.setNewPassword(NEW_PASSWORD);
        return request;
    }
}
