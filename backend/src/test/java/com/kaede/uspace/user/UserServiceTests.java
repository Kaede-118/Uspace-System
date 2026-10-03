package com.kaede.uspace.user;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.user.dto.AdminUserQuery;
import com.kaede.uspace.user.dto.AdminUserUpdateRequest;
import com.kaede.uspace.user.dto.AdminUserVo;
import com.kaede.uspace.user.dto.ChangePasswordRequest;
import com.kaede.uspace.user.dto.QqVerifyIssueVo;
import com.kaede.uspace.user.dto.RegisterRequest;
import com.kaede.uspace.user.dto.UpdateProfileRequest;
import com.kaede.uspace.user.dto.UserProfileVo;
import com.kaede.uspace.user.entity.SysUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** 管理员 ID，供「后台改资料」那组用例的留痕参数使用 */
    private static final Long OPERATOR_ID = 9001L;

    /**
     * 自动生成 QQ 号的序号。
     *
     * <p>从 100001 起递增 —— 与用例里显式写死的那些（10001 / 20001 / 30001）不重合，
     * 也不会撞 {@code uk_qq}。用递增而不是随机：随机数偶尔会撞，
     * 而那种失败是「跑十次错一次」的，最难查。
     */
    private final AtomicInteger qqSequence = new AtomicInteger(100000);

    /** 内存版数据访问层 */
    private final FakeSysUserMapper fakeMapper = new FakeSysUserMapper();

    /** 密码编码器。强度 4 是为了让测试跑得快，生产用 10 */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    /**
     * QQ 验证服务。内存实现、不连库、没有外部依赖，所以直接用真的 ——
     * 「填了 QQ 就必须先过验证」那条规则要真的走一遍 consume 才算测到。
     */
    private final QqVerifyService qqVerifyService =
            new QqVerifyService(new QqVerifyProperties(), Clock.systemDefaultZone());

    /** 被测服务 */
    private final UserService userService =
            new UserService(fakeMapper.asMapper(), passwordEncoder, qqVerifyService);

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
    @DisplayName("注册：填了 QQ 却没完成群内验证时拒绝")
    void register_rejectsUnverifiedQq() {
        RegisterRequest request = newRequest(USERNAME);
        request.setQq("10001");
        // 不带 challengeId —— 相当于用户跳过了「取码发到群里」那一步

        BizResult<UserProfileVo> result = userService.register(request);

        assertEquals(ErrorCode.QQ_VERIFY_REQUIRED, result.getError(),
                "QQ 号必须被证明是本人的 —— 否则「群里来的人是谁」永远对不上，"
                        + "而那正是模块 11 全部播报与查询的地基");
    }

    @Test
    @DisplayName("注册：不填 QQ 号直接被拒 —— 它是必填项（2026-10-01 起）")
    void register_rejectsBlankQq() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(USERNAME);
        request.setPassword(RAW_PASSWORD);
        // 不设 qq，也不设 challengeId

        BizResult<UserProfileVo> result = userService.register(request);

        assertEquals(ErrorCode.PARAM_INVALID, result.getError(),
                "店里没有店员，顾客是谁、该收多少钱全靠系统 —— "
                        + "「不绑 QQ 也能进店」等于留一个出了事找不到主的顾客");
    }

    @Test
    @DisplayName("注册：QQ 号已被他人绑定时返回冲突错误码")
    void register_failsWhenQqAlreadyBound() {
        RegisterRequest first = newRequest("userA");
        first.setQq("10001");
        first.setChallengeId(verifyQq("10001"));
        userService.register(first);

        RegisterRequest second = newRequest("userB");
        second.setQq("10001");
        // ⚠️ 刻意【不】给 second 配验证 —— 这条用例顺带钉住一个顺序决策：
        // 查重必须排在 consume 之前。反过来写的话它拿到的是 QQ_VERIFY_REQUIRED，
        // 而真正的原因是「这个 QQ 已经被占用了」，用户据此完全不知道该改什么
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
        request.setChallengeId(verifyQq("10001"));

        BizResult<UserProfileVo> result = userService.register(request);

        assertEquals("小枫", result.getData().getNickname(), "填了就用填的");
    }

    @Test
    @DisplayName("注册：昵称三级兜底 —— 留空则用 QQ 号")
    void register_fallsBackToQqWhenNicknameBlank() {
        RegisterRequest request = newRequest(USERNAME);
        request.setQq("10001");
        request.setChallengeId(verifyQq("10001"));
        // nickname 留空

        BizResult<UserProfileVo> result = userService.register(request);

        assertEquals("10001", result.getData().getNickname(),
                "群播报的读者认 QQ 号，留空时用它比用登录名更贴合群里的称呼习惯");
    }

    @Test
    @DisplayName("注册：昵称留空时必落到 QQ 号上，不会是空")
    void register_fillsNicknameWhenBlank() {
        BizResult<UserProfileVo> result = register(USERNAME);

        assertNotNull(result.getData().getNickname(),
                "昵称不能为空 —— 空昵称会让「谁在店里」这类播报失去意义");
        assertEquals(result.getData().getQq(), result.getData().getNickname(),
                "三级兜底里 QQ 在用户名之前 —— 群里的人认 QQ 号，不认登录名");
        // 三级兜底的【最后一级】（退回用户名）在注册路径上已经不可达了：
        // QQ 自 2026-10-01 起是必填项。那一级现在只能靠管理员解绑 QQ 才走得到，
        // 由 updateProfile_fallsBackToUsernameWhenQqCleared 覆盖
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
        request.setChallengeId(verifyQq("10001"));
        Long userId = userService.register(request).getData().getId();

        UpdateProfileRequest update = new UpdateProfileRequest();
        update.setNickname("小枫");
        // phone 留空表示清空；qq 必须原样带上 —— 它【不】适用「传 null 即清空」那条规则
        update.setQq("10001");
        BizResult<UserProfileVo> result = userService.updateProfile(userId, update);

        assertTrue(result.isSuccess());
        assertNull(fakeMapper.get(userId).getPhone(), "PUT 是全量替换，不传即清空");
        assertEquals("10001", fakeMapper.get(userId).getQq(),
                "QQ 号是这条全量替换语义的例外 —— 它不参与「传 null 即清空」，也不许本人改");
        assertEquals("小枫", fakeMapper.get(userId).getNickname());
    }

    @Test
    @DisplayName("改资料：本人不能改 QQ 号，一律要求联系管理员")
    void updateProfile_rejectsQqChange() {
        Long userId = register(USERNAME).getData().getId();

        UpdateProfileRequest update = new UpdateProfileRequest();
        update.setNickname(USERNAME);
        update.setQq("10001");   // 从「没绑」改成另一个号

        BizResult<UserProfileVo> result = userService.updateProfile(userId, update);

        assertEquals(ErrorCode.QQ_CHANGE_REQUIRES_ADMIN, result.getError(),
                "QQ 号是机器人在群里认人的唯一依据，而注册时它经过一次群内验证 —— "
                        + "允许本人改，那次验证就等于白做（先随便填一个号注册，再改成别人的）");
    }

    @Test
    @DisplayName("改资料：清空 QQ 号同样被拒 —— 否则「先清空再填别人的」会绕开上面那条")
    void updateProfile_rejectsQqClearing() {
        RegisterRequest request = newRequest(USERNAME);
        request.setQq("10001");
        request.setChallengeId(verifyQq("10001"));
        Long userId = userService.register(request).getData().getId();

        UpdateProfileRequest update = new UpdateProfileRequest();
        update.setNickname(USERNAME);
        // qq 留空，表示清空

        BizResult<UserProfileVo> result = userService.updateProfile(userId, update);

        assertEquals(ErrorCode.QQ_CHANGE_REQUIRES_ADMIN, result.getError(),
                "只挡「改成别的号」是不够的：先清空、再设成别人的，正好绕过去。"
                        + "规则简单才守得住 —— 注册时定下，之后要改找管理员");
    }

    @Test
    @DisplayName("后台：管理员可以改 QQ 号 —— 这正是 40937 说的「联系管理员」")
    void adminUpdateUser_canChangeQq() {
        Long userId = register(USERNAME).getData().getId();

        AdminUserUpdateRequest update = new AdminUserUpdateRequest();
        update.setNickname("小枫");
        update.setQq("10001");

        BizResult<AdminUserVo> result = userService.adminUpdateUser(userId, update, OPERATOR_ID);

        assertTrue(result.isSuccess(),
                "本人改不了、管理员也改不了的话，「联系管理员」就是一句空话");
        assertEquals("10001", fakeMapper.get(userId).getQq());
        assertEquals("小枫", fakeMapper.get(userId).getNickname());
    }

    @Test
    @DisplayName("后台：管理员改 QQ 时照样查重")
    void adminUpdateUser_rejectsQqTakenByAnother() {
        RegisterRequest owner = newRequest("owner");
        owner.setQq("10001");
        owner.setChallengeId(verifyQq("10001"));
        userService.register(owner);

        Long userId = register(USERNAME).getData().getId();
        AdminUserUpdateRequest update = new AdminUserUpdateRequest();
        update.setNickname(USERNAME);
        update.setQq("10001");

        BizResult<AdminUserVo> result = userService.adminUpdateUser(userId, update, OPERATOR_ID);

        assertEquals(ErrorCode.QQ_ALREADY_BOUND, result.getError(),
                "免掉的是「证明这个号是本人的」那一步，不是查重 —— "
                        + "两个账号绑同一个 QQ 会让机器人的播报指向不确定的人");
    }

    @Test
    @DisplayName("后台：修改不存在的用户返回 404")
    void adminUpdateUser_returns404WhenMissing() {
        BizResult<AdminUserVo> result = userService.adminUpdateUser(
                999999L, new AdminUserUpdateRequest(), OPERATOR_ID);

        assertEquals(ErrorCode.USER_NOT_FOUND, result.getError());
    }

    @Test
    @DisplayName("改资料：原样提交自己的 QQ 号不应被判定为冲突")
    void updateProfile_allowsKeepingOwnQq() {
        RegisterRequest request = newRequest(USERNAME);
        request.setQq("10001");
        request.setChallengeId(verifyQq("10001"));
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
        request.setChallengeId(verifyQq("10001"));
        Long userId = userService.register(request).getData().getId();

        UpdateProfileRequest update = new UpdateProfileRequest();
        update.setNickname("   ");
        update.setQq("10001");   // 全量替换语义：QQ 必须原样带上，否则会被当成「改 QQ」拒掉
        BizResult<UserProfileVo> result = userService.updateProfile(userId, update);

        assertEquals("10001", result.getData().getNickname(),
                "昵称是播报与后台列表的标识，清空它会让播报失去意义 —— "
                        + "有 QQ 时按三级规则兜底到 QQ 号");
    }

    @Test
    @DisplayName("改资料：QQ 被管理员解绑后，昵称兜底到登录名")
    void updateProfile_fallsBackToUsernameWhenQqCleared() {
        Long userId = register(USERNAME).getData().getId();
        // 管理员把这个用户的 QQ 解绑（比如那个号换了人）——
        // 这是「昵称兜底到登录名」那支唯一走得到的路径，
        // 因为 QQ 在注册时是必填的，用户自己又改不了
        AdminUserUpdateRequest clear = new AdminUserUpdateRequest();
        clear.setNickname(USERNAME);
        userService.adminUpdateUser(userId, clear, OPERATOR_ID);

        UpdateProfileRequest update = new UpdateProfileRequest();
        update.setNickname("   ");
        BizResult<UserProfileVo> result = userService.updateProfile(userId, update);

        assertEquals(USERNAME, result.getData().getNickname(),
                "三级兜底只剩用户名可用时，它得顶上 —— 播报里不能出现空名字");
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
        a.setChallengeId(verifyQq("20001"));
        userService.register(a);
        register("bob");

        assertEquals(1, listOf("alice").getTotal());
        assertEquals(1, listOf("爱丽丝").getTotal(),
                "运营未必记得住登录名，昵称也要能搜到");
        assertEquals(1, listOf("20001").getTotal());
        assertEquals(2, listOf(null).getTotal(), "不传关键字表示查全部");
    }

    @Test
    @DisplayName("列表：筛选与排序")
    void listUsers_filtersAndSorts() {
        // 一个有钱有卡的、一个什么都没有的
        RegisterRequest rich = newRequest("rich");
        rich.setQq("30001");
        rich.setChallengeId(verifyQq("30001"));
        Long richId = userService.register(rich).getData().getId();
        register("poor");

        // 按角色筛：两个都是普通用户
        AdminUserQuery userRole = new AdminUserQuery();
        userRole.setRole(UserRole.USER.name());
        assertEquals(2, userService.listUsers(userRole, null, null, 1, 10)
                .getData().getTotal());

        // 按状态筛：都是启用中
        AdminUserQuery enabled = new AdminUserQuery();
        enabled.setStatus(1);
        assertEquals(2, userService.listUsers(enabled, null, null, 1, 10)
                .getData().getTotal());

        AdminUserQuery disabled = new AdminUserQuery();
        disabled.setStatus(0);
        assertEquals(0, userService.listUsers(disabled, null, null, 1, 10)
                .getData().getTotal());

        // 认不出的排序键要回落，不能把整张列表搞成 500 —— 也绝不能拼进 SQL
        assertEquals(2, userService.listUsers(null, "'; DROP TABLE sys_user; --", true, 1, 10)
                .getData().getTotal(),
                "白名单外的排序键必须被丢掉并回落到默认排序");
        assertNotNull(fakeMapper.get(richId), "上一条顺带证明：表还在");
    }

    @Test
    @DisplayName("列表：累计在店时长与月卡来自查询，不是实体字段")
    void listUsers_carriesAggregatedColumns() {
        register(USERNAME);

        // 假 Mapper 里没有聚合能力（真实实现是相关子查询），所以这里断言的是
        // 「Service 把 IPage<AdminUserVo> 原样透传」这条路径没有把聚合列弄丢 ——
        // 两个字段的取值正确性靠集成测试与手工验证
        AdminUserVo vo = userService.listUsers(new AdminUserQuery(), null, null, 1, 10)
                .getData().getRecords().get(0);

        assertNotNull(vo.getUsername());
    }

    @Test
    @DisplayName("列表：对外视图不含密码哈希")
    void listUsers_doesNotExposePasswordHash() {
        register(USERNAME);

        AdminUserVo vo = userService.listUsers(new AdminUserQuery(), null, null, 1, 10)
                .getData().getRecords().get(0);

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
     * 构造一个可直接提交的注册请求：用户名、密码，以及一个<b>唯一且已通过验证</b>的 QQ 号。
     *
     * <p>⚠️ <b>QQ 号自 2026-10-01 起是注册必填项，且必须走完群内验证</b> ——
     * 所以这里顺手把它备齐。不备的话，每个只想造一个用户的用例都要多写三行样板，
     * 而漏写的后果是注册被静默拒掉、后面一路 NPE（这次改规则时正是这么炸的）。
     *
     * <p>QQ 用递增序号生成，保证用例之间不撞 {@code uk_qq} 唯一键。
     * 需要指定 QQ 的用例可以覆盖它 —— 但那时<b>记得同时覆盖 challengeId</b>。
     *
     * @param username 用户名
     * @return 可直接提交的注册请求
     */
    private RegisterRequest newRequest(String username) {
        String qq = String.valueOf(qqSequence.incrementAndGet());
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setPassword(RAW_PASSWORD);
        request.setQq(qq);
        request.setChallengeId(verifyQq(qq));
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
     * 按关键字查一页用户，取回分页结果。
     *
     * <p>（列表演进成了「条件对象 + 排序 + 分页」，直接调会淹在参数里，
     * 所以把最常用的那一种收成一个方法。）
     *
     * @param keyword 关键字，可为 null
     * @return 分页结果
     */
    private PageResult<AdminUserVo> listOf(String keyword) {
        AdminUserQuery query = new AdminUserQuery();
        query.setKeyword(keyword);
        return userService.listUsers(query, null, null, 1, 10).getData();
    }

    /**
     * 走完一次完整的 QQ 验证，返回可用的验证凭证。
     *
     * <p>注册要求「填了 QQ 就必须先证明这个号是本人的」，而证明的<b>另一端在 QQ 群里</b>：
     * 用户把 6 位码发到群里，机器人看到后回执给后端。这个方法扮演群里那一半 ——
     * 拿到码直接调 {@code confirm}。真机上那一步由群消息触发
     * （见 {@code qqbot} 包的 {@code QqCommandService}）。
     *
     * <p>有了它，用例才能把「注册」当成一件独立的事来测；否则每个带 QQ 的用例
     * 都得先铺一套群消息的场景。
     *
     * @param qq 要验证的 QQ 号
     * @return 填进 {@link RegisterRequest#getChallengeId()} 的凭证
     */
    private String verifyQq(String qq) {
        QqVerifyIssueVo issued = qqVerifyService.issue(qq).getData();
        qqVerifyService.confirm(qq, issued.getCode());
        return issued.getChallengeId();
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
