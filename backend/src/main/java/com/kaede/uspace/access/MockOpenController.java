package com.kaede.uspace.access;

import com.kaede.uspace.access.dto.MockOpenRequest;
import com.kaede.uspace.access.dto.RecordOpenVo;
import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.lock.dto.AddPasscodeRequest;
import com.kaede.uspace.lock.dto.LockRecordDto;
import com.kaede.uspace.lock.dto.PasscodeResult;
import com.kaede.uspace.lock.mock.MockLockServiceImpl;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 模拟开门接口（<b>仅模拟门锁下存在</b>）。
 *
 * <p>真实场景下开门记录由锁自身产生、经网关上报到门锁云，本系统只负责拉取；
 * 模拟实现没有硬件，因此提供一个接口人为「制造」一次开门，让
 * 「下发密码 → 开门 → 写进出记录」这条链路能独立演示。类上的
 * {@code @ConditionalOnProperty} 保证<b>切换到真实门锁（{@code uspace.lock.provider=ttlock}）时
 * 这个接口连同本类一起从容器里消失</b>，不会在生产环境留一个能凭空造记录的口子。
 *
 * <p><b>为什么本类的方法体不是一行</b>（项目里其余 Controller 都是
 * {@code return ApiResult.of(service.xxx())}）：这里要编排三步 ——
 * 必要时先下发密码、模拟开门、再交给 {@link AccessRecordService} 落库。
 * 这段编排<b>不能下沉到 Service</b>，因为业务 Service 绝不能依赖
 * {@link MockLockServiceImpl} —— 它的 {@code simulateOpen} 不在 {@code LockService}
 * 接口上，业务代码一旦依赖它，将来切换到真实门锁时就要改业务代码。
 * 所以这段「只有模拟实现才需要」的编排只能待在 Controller 里，并靠条件注解隔离。
 */
@RestController
@RequestMapping("/api/admin/access-records/mock-open")
@PreAuthorize("hasRole('ADMIN')")
@Validated
@ConditionalOnProperty(prefix = "uspace.lock", name = "provider",
        havingValue = "mock", matchIfMissing = true)
public class MockOpenController {

    /** 未指定密码时自动下发的密码有效期（小时） */
    private static final long AUTO_PASSCODE_HOURS = 3;

    /** 密码名称里的时间格式，便于在门锁 APP 里辨认用途 */
    private static final DateTimeFormatter NAME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MockLockServiceImpl mockLockService;
    private final AccessRecordService accessRecordService;

    /**
     * 构造方法。
     *
     * <p>这里注入的是<b>具体实现类</b>而不是 {@code LockService} 接口 ——
     * 因为要用的 {@code simulateOpen} 只在模拟实现上有。类级条件注解保证了
     * 容器里一定存在这个 Bean，不会出现注入不到的情况。
     *
     * @param mockLockService     模拟门锁实现
     * @param accessRecordService 开门记录服务
     */
    public MockOpenController(MockLockServiceImpl mockLockService,
                              AccessRecordService accessRecordService) {
        this.mockLockService = mockLockService;
        this.accessRecordService = accessRecordService;
    }

    /**
     * 模拟一次开门并落库。
     *
     * <p>请求未带密码时，会先自动下发一个 3 小时有效的限时密码再用它开门 ——
     * 模拟门锁要求「密码必须已下发」才能开门，而当前还没有下单流程来下发密码
     * （模块 8 尚未落地），自动下发让演示一次请求即可走完全程。
     * 模块 8 落地后密码由下单流程给出，届时传值即可。
     *
     * @param request 锁 ID 与可选的密码、用户 ID、订单 ID
     * @return 落库后的记录与「本次是否新增」标记
     */
    @PostMapping
    public ResponseEntity<ApiResult<RecordOpenVo>> open(
            @Valid @RequestBody MockOpenRequest request) {
        return ApiResult.of(simulateAndSave(request));
    }

    /**
     * 编排「必要时下发密码 → 模拟开门 → 落库」。
     *
     * @param request 模拟开门入参
     * @return 落库结果；下发密码失败时返回 {@link ErrorCode#LOCK_CLOUD_UNAVAILABLE}
     */
    private BizResult<RecordOpenVo> simulateAndSave(MockOpenRequest request) {
        String passcode = request.getPasscode();
        if (passcode == null || passcode.isBlank()) {
            BizResult<String> issued = issuePasscode(request.getLockId());
            if (!issued.isSuccess()) {
                return BizResult.fail(issued.getError(), issued.resolveMessage());
            }
            passcode = issued.getData();
        }

        // 密码不是本系统下发过的时，simulateOpen 会抛 IllegalArgumentException ——
        // 那是「调用方给了一个无效密码」，属于参数问题，交给全局异常处理器转成 400，
        // 不在这里吞掉。此处刻意不 catch。
        LockRecordDto record = mockLockService.simulateOpen(
                request.getLockId(), passcode, request.getUserId());

        return accessRecordService.recordOpen(record, request.getOrderId());
    }

    /**
     * 下发一个用于演示的限时密码。
     *
     * <p>走的是真实的 {@code addPasscode}，因此通通锁的真实约束照样会被走到 ——
     * 必须给有效期、{@code addType=2} 需要锁支持远程下发（可用
     * {@code uspace.lock.mock.remote-supported=false} 模拟纯蓝牙锁被拒绝）。
     *
     * @param lockId 锁 ID
     * @return 成功时 data 为下发的密码；失败时返回 {@link ErrorCode#LOCK_CLOUD_UNAVAILABLE}
     */
    private BizResult<String> issuePasscode(Long lockId) {
        LocalDateTime now = LocalDateTime.now();

        AddPasscodeRequest addRequest = new AddPasscodeRequest();
        addRequest.setLockId(lockId);
        addRequest.setKeyboardPwdName("模拟开门 " + now.format(NAME_FORMATTER));
        addRequest.setStartTime(now);
        addRequest.setEndTime(now.plusHours(AUTO_PASSCODE_HOURS));

        PasscodeResult result = mockLockService.addPasscode(addRequest);
        if (!result.isSuccess()) {
            return BizResult.fail(ErrorCode.LOCK_CLOUD_UNAVAILABLE,
                    "模拟下发密码失败：" + result.getErrmsg());
        }
        return BizResult.ok(result.getKeyboardPwd());
    }
}
