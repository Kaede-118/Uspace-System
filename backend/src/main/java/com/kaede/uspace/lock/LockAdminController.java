package com.kaede.uspace.lock;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.lock.dto.AddPasscodeRequest;
import com.kaede.uspace.lock.dto.LockStatus;
import com.kaede.uspace.lock.dto.PasscodeResult;
import com.kaede.uspace.lock.dto.PasscodeVo;
import com.kaede.uspace.lock.dto.RevokePasscodeRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 门锁管理接口（模块 5 的管理员侧）。
 *
 * <p>本类只做一件事：把 {@link LockService} 的调用包一层 HTTP，
 * 并把通通锁风格的 {@link PasscodeResult} 翻译成本系统的 {@link BizResult}。
 * <b>不再加一层 {@code LockAdminService}</b> —— {@code LockService} 本身就是
 * Service 层的接口，再包一层没有收益，只会多一个要同步维护的转发方法。
 *
 * <p><b>业务场景</b>：正常流程下密码由下单时自动下发（模块 8），不需要管理员介入。
 * 本接口面向的是<b>应急场景</b> —— 顾客手机没电、忘记密码、需要临时给维修人员开门等。
 * 另外它也是演示与联调时下发密码的入口（模拟门锁要求密码必须先下发才能开门）。
 *
 * <p><b>哪些实现会生效</b>：注入的是 {@code LockService} 接口，因此
 * {@code uspace.lock.provider} 配成 {@code mock} 或 {@code ttlock} 都能用 ——
 * 换实现时本类一行都不用改，这正是把接口按真实规格设计的收益。
 */
@RestController
@RequestMapping("/api/admin/lock")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class LockAdminController {

    private final LockService lockService;

    /**
     * 构造方法。
     *
     * @param lockService 门锁服务，由配置项 {@code uspace.lock.provider} 决定具体实现
     */
    public LockAdminController(LockService lockService) {
        this.lockService = lockService;
    }

    // ==================================================================
    // 密码
    // ==================================================================

    /**
     * 下发一个限时密码。
     *
     * <p>通通锁的硬约束（模拟实现同样会执行）：自定义密码<b>只能设为限时密码</b>，
     * 不给有效期会被拒绝；远程下发（{@code addType=2}）要求锁接入网关或本身是 Wi-Fi 锁，
     * 纯蓝牙锁只能先在 APP 内用蓝牙添加。
     *
     * <p>密码留空则由门锁服务随机生成，生成的密码在响应里返回。
     *
     * @param request 锁 ID、有效期与可选的密码内容、名称
     * @return 成功时 data 为下发的密码；失败时返回对应错误码
     */
    @PostMapping("/passcodes")
    public ResponseEntity<ApiResult<PasscodeVo>> addPasscode(
            @Valid @RequestBody AddPasscodeRequest request) {
        return ApiResult.of(translate(lockService.addPasscode(request)));
    }

    /**
     * 撤销（提前失效）一个已下发的密码。
     *
     * <p>幂等：密码本就不存在时同样返回成功，可安全重复调用。
     *
     * @param request 锁 ID 与要撤销的密码
     * @return 成功时 data 为 null；失败时返回对应错误码
     */
    @PostMapping("/passcodes/revoke")
    public ResponseEntity<ApiResult<Void>> revokePasscode(
            @Valid @RequestBody RevokePasscodeRequest request) {
        PasscodeResult result = lockService.deletePasscode(
                request.getLockId(), request.getKeyboardPwd());
        if (!result.isSuccess()) {
            return ApiResult.of(failureOf(result));
        }
        return ApiResult.of(BizResult.ok(null));
    }

    // ==================================================================
    // 状态
    // ==================================================================

    /**
     * 查询门锁的在线状态。
     *
     * <p>真实场景下要经网关读取锁的连接状态，会有明显延迟；模拟实现固定返回在线。
     * 供运营后台展示「锁是否可用」，也是排查「同步开门记录为空」时的辅助手段。
     *
     * <p>本接口不返回 {@code BizResult} —— 查不到状态本身就是一个有效答案
     * （{@link LockStatus#UNKNOWN}），不必当成错误。
     *
     * @param lockId 锁 ID
     * @return 锁状态：{@code ONLINE} 在线 / {@code OFFLINE} 离线 / {@code UNKNOWN} 未知
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResult<LockStatus>> status(@RequestParam Long lockId) {
        return ResponseEntity.ok(ApiResult.ok(lockService.queryStatus(lockId)));
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /**
     * 把门锁服务的返回翻译成 Service 层结果。
     *
     * @param result 门锁服务的返回
     * @return 成功时携带密码；失败时携带对应错误码与中文说明
     */
    private static BizResult<PasscodeVo> translate(PasscodeResult result) {
        if (!result.isSuccess()) {
            return BizResult.fail(errorOf(result), result.getErrmsg());
        }
        return BizResult.ok(PasscodeVo.of(result.getKeyboardPwd()));
    }

    /**
     * 判断失败该归到哪个错误码。
     *
     * <p>{@code -4043} 是通通锁对「该锁不支持此操作」的返回，最常见的情形是
     * 拿纯蓝牙锁去远程下发密码。<b>这是锁的接入方式配置问题，不是网络故障</b> ——
     * 若归到 502，管理员会以为「等会儿再试就好了」，而实际上重试多少次都不会成功，
     * 得去检查锁有没有接网关。所以单独走 409。
     *
     * @param result 门锁服务的失败返回
     * @return 对应的错误码
     */
    private static ErrorCode errorOf(PasscodeResult result) {
        if (Integer.valueOf(-4043).equals(result.getErrcode())) {
            return ErrorCode.BUSINESS_REJECTED;
        }
        return ErrorCode.LOCK_CLOUD_UNAVAILABLE;
    }

    /**
     * 由门锁服务的失败返回构造 {@code BizResult}。
     *
     * @param result 门锁服务的失败返回
     * @return 失败结果
     */
    private static BizResult<Void> failureOf(PasscodeResult result) {
        return BizResult.fail(errorOf(result), result.getErrmsg());
    }
}
