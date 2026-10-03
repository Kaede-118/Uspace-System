package com.kaede.uspace.lock.mock;

import com.kaede.uspace.lock.LockProperties;
import com.kaede.uspace.lock.LockService;
import com.kaede.uspace.lock.dto.AddPasscodeRequest;
import com.kaede.uspace.lock.dto.GetOneTimePasscodeRequest;
import com.kaede.uspace.lock.dto.LockRecordDto;
import com.kaede.uspace.lock.dto.LockStatus;
import com.kaede.uspace.lock.dto.OneTimePasscodeResult;
import com.kaede.uspace.lock.dto.PasscodeResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 门锁服务的<b>模拟实现</b>，不访问外网、不依赖真实硬件。
 *
 * <p>设计目标：让「下发密码 → 开门 → 写进出记录」这条链路可以脱离真实门锁
 * 独立跑通，用于开发与答辩演示（计费是纯计算，本就与门锁无关）。
 * 同时<b>保留真实接口的语义与约束</b>，
 * 使得将来换成 {@code TtlockServiceImpl} 时上层代码无需改动。
 *
 * <p>刻意保留的真实约束：
 * <ol>
 *   <li>自定义密码只能是限时密码 —— 不传有效期会被拒绝</li>
 *   <li>远程下发（{@code addType=2}）需要锁接入网关或为 Wi-Fi 锁，
 *       可用 {@code uspace.lock.mock.remote-supported=false} 模拟纯蓝牙锁</li>
 *   <li>网络调用可能失败 —— 用 {@code uspace.lock.mock.failure-rate} 注入失败概率，
 *       否则异常处理分支在演示时永远走不到</li>
 * </ol>
 *
 * <p>数据仅保存在内存中，进程重启即清空。
 *
 * @see LockService
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "uspace.lock", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockLockServiceImpl implements LockService {

    /** 模拟的开门方式取值，代表「键盘密码开门」。真实取值以通通锁文档为准 */
    private static final int MOCK_OPEN_TYPE_KEYBOARD_PWD = 2;

    /**
     * 一次性密码的固有窗口。
     *
     * <p>通通锁的单次密码（{@code keyboardPwdType=1}）规则是
     * <b>自生效时刻起 6 小时内只能使用一次</b>，本系统不参与决定这个时长。
     */
    private static final Duration SINGLE_USE_WINDOW = Duration.ofHours(6);

    /** 已下发的密码，key 为 {@code lockId:keyboardPwd} */
    private final Map<String, IssuedPasscode> issued = new ConcurrentHashMap<>();

    /**
     * 密码 ID 序列。真实场景由锁云分配，模拟实现用自增，
     * 起点 1000 是为了贴近官方文档示例里的 {@code 10236} 那种量级。
     */
    private final AtomicLong passcodeIdSequence = new AtomicLong(1000);

    /** 模拟产生的开门记录，按锁 ID 分组 */
    private final Map<Long, List<LockRecordDto>> records = new ConcurrentHashMap<>();

    /** 密码随机源。用 SecureRandom 而非 Random，保持与真实场景一致的安全习惯 */
    private final SecureRandom random = new SecureRandom();

    /** 门锁模块配置，提供 provider 切换与模拟参数 */
    private final LockProperties properties;

    /**
     * 构造方法，注入门锁配置。
     *
     * @param properties 门锁模块配置
     */
    public MockLockServiceImpl(LockProperties properties) {
        this.properties = properties;
        log.info("[Mock门锁] 模拟实现已启用（provider=mock），不会访问真实门锁云");
    }

    /**
     * {@inheritDoc}
     *
     * <p>模拟实现：校验真实约束后，把密码存入内存 Map，不产生任何网络调用。
     */
    @Override
    public PasscodeResult addPasscode(AddPasscodeRequest request) {
        if (!simulateNetwork("下发密码")) {
            return PasscodeResult.fail(-1, "模拟网络异常：下发密码失败");
        }

        // 约束一：通通锁的自定义密码只支持限时密码，必须给出有效期
        if (request.getStartTime() == null || request.getEndTime() == null) {
            return PasscodeResult.fail(-1, "通通锁自定义密码只支持限时密码，必须给出生效与失效时间");
        }
        if (!request.getEndTime().isAfter(request.getStartTime())) {
            return PasscodeResult.fail(-1, "失效时间必须晚于生效时间");
        }

        // 约束二：远程下发要求锁接入网关或本身是 Wi-Fi 锁
        if (Integer.valueOf(2).equals(request.getAddType()) && !properties.getMock().isRemoteSupported()) {
            return PasscodeResult.fail(-4043, "该锁未接入网关，不支持远程下发，请改用 addType=1");
        }

        String keyboardPwd = request.getKeyboardPwd();
        if (keyboardPwd == null || keyboardPwd.isBlank()) {
            keyboardPwd = randomPasscode(request.getLockId());
        }

        IssuedPasscode passcode = new IssuedPasscode(
                request.getLockId(),
                keyboardPwd,
                request.getStartTime(),
                request.getEndTime(),
                request.getKeyboardPwdName(),
                passcodeIdSequence.incrementAndGet(),
                false);
        issued.put(key(request.getLockId(), keyboardPwd), passcode);

        log.info("[Mock门锁] 下发限时密码成功 lockId={} pwd={} 有效期 {} ~ {} 名称={}",
                request.getLockId(), keyboardPwd, request.getStartTime(), request.getEndTime(),
                request.getKeyboardPwdName());
        return PasscodeResult.ok(keyboardPwd);
    }

    /**
     * {@inheritDoc}
     *
     * <p>模拟实现：随机生成一串 6 位数字，窗口取「生效起 6 小时」，
     * 并标记为一次性 —— {@link #simulateOpen} 用过一次就会把它从 {@code issued} 里移除。
     *
     * <p>沿用与 {@link #addPasscode} 相同的两道真实约束（模拟网络故障、远程下发能力）：
     * 真实的 {@code keyboardPwd/get} 同样是远程调用，模拟实现不该比真锁宽松。
     */
    @Override
    public OneTimePasscodeResult getOneTimePasscode(GetOneTimePasscodeRequest request) {
        if (!simulateNetwork("获取一次性密码")) {
            return OneTimePasscodeResult.fail(-1, "模拟网络异常：获取一次性密码失败");
        }
        // 远程能力：真实的 keyboardPwd/get 本就是远程调用（没有 addType 那种「本地蓝牙添加」
        // 的选项），纯蓝牙锁连不上网关就取不到 —— 模拟实现照此拒绝，不给自己开后门
        if (!properties.getMock().isRemoteSupported()) {
            return OneTimePasscodeResult.fail(-4043, "该锁未接入网关，无法远程获取密码");
        }

        LocalDateTime startTime = request.getStartTime() != null
                ? request.getStartTime() : LocalDateTime.now();
        LocalDateTime endTime = startTime.plus(SINGLE_USE_WINDOW);

        String keyboardPwd = randomPasscode(request.getLockId());
        IssuedPasscode passcode = new IssuedPasscode(
                request.getLockId(),
                keyboardPwd,
                startTime,
                endTime,
                request.getKeyboardPwdName(),
                passcodeIdSequence.incrementAndGet(),
                true);
        issued.put(key(request.getLockId(), keyboardPwd), passcode);

        log.info("[Mock门锁] 获取一次性密码成功 lockId={} pwd={} id={} 有效期 {} ~ {}（用一次即焚）",
                request.getLockId(), keyboardPwd, passcode.keyboardPwdId(), startTime, endTime);
        return OneTimePasscodeResult.ok(keyboardPwd, passcode.keyboardPwdId(), startTime, endTime);
    }

    /**
     * {@inheritDoc}
     *
     * <p>模拟实现：更新内存中该密码的有效期。
     */
    @Override
    public PasscodeResult changePasscode(Long lockId, String keyboardPwd,
                                         LocalDateTime startTime, LocalDateTime endTime) {
        if (!simulateNetwork("修改密码")) {
            return PasscodeResult.fail(-1, "模拟网络异常：修改密码失败");
        }

        String cacheKey = key(lockId, keyboardPwd);
        IssuedPasscode existing = issued.get(cacheKey);
        if (existing == null) {
            return PasscodeResult.fail(-1, "密码不存在：lockId=" + lockId);
        }
        if (startTime == null || endTime == null || !endTime.isAfter(startTime)) {
            return PasscodeResult.fail(-1, "失效时间必须晚于生效时间");
        }

        issued.put(cacheKey, existing.withValidity(startTime, endTime));
        log.info("[Mock门锁] 修改密码有效期 lockId={} pwd={} -> {} ~ {}",
                lockId, keyboardPwd, startTime, endTime);
        return PasscodeResult.ok(keyboardPwd);
    }

    /**
     * {@inheritDoc}
     *
     * <p>模拟实现：从内存中移除该密码。即使密码不存在也返回成功，
     * 以支持「重复删除」的幂等调用。
     */
    @Override
    public PasscodeResult deletePasscode(Long lockId, String keyboardPwd) {
        if (!simulateNetwork("删除密码")) {
            return PasscodeResult.fail(-1, "模拟网络异常：删除密码失败");
        }

        issued.remove(key(lockId, keyboardPwd));
        log.info("[Mock门锁] 删除密码 lockId={} pwd={}", lockId, keyboardPwd);
        return PasscodeResult.ok(keyboardPwd);
    }

    /**
     * {@inheritDoc}
     *
     * <p>模拟实现：固定返回在线。真实场景需经网关读取锁的实际连接状态。
     */
    @Override
    public LockStatus queryStatus(Long lockId) {
        if (!simulateNetwork("查询锁状态")) {
            return LockStatus.UNKNOWN;
        }
        return LockStatus.ONLINE;
    }

    /**
     * {@inheritDoc}
     *
     * <p>模拟实现：返回通过 {@link #simulateOpen} 制造的记录。
     */
    @Override
    public List<LockRecordDto> listRecords(Long lockId, LocalDateTime from, LocalDateTime to) {
        if (!simulateNetwork("查询开门记录")) {
            return List.of();
        }
        return records.getOrDefault(lockId, List.of()).stream()
                .filter(r -> !r.openTime().isBefore(from) && !r.openTime().isAfter(to))
                .sorted(Comparator.comparing(LockRecordDto::openTime))
                .toList();
    }

    // ==================================================================
    // 以下为模拟实现专有方法，不属于 LockService 接口
    // ==================================================================

    /**
     * 【仅模拟实现】手动制造一条开门记录。
     *
     * <p>真实场景下开门记录由锁自身产生、经网关上报到云端；模拟实现没有真实硬件，
     * 因此提供本方法供演示与测试触发，使「进出记录」（模块 6）与「计费管理」（模块 7）
     * 有数据可走。
     *
     * <p>注意：本方法<b>不在 {@link LockService} 接口中</b>，
     * 业务代码不应依赖它，否则将来切换真实实现会编译失败。
     * 仅供演示脚本与测试使用。
     *
     * @param lockId      锁 ID
     * @param keyboardPwd 开门所用密码，必须是已下发的密码
     * @param userId      系统内的用户 ID，可为 null
     * @return 新产生的开门记录
     * @throws IllegalArgumentException 当密码不存在或已失效时抛出
     */
    public LockRecordDto simulateOpen(Long lockId, String keyboardPwd, Long userId) {
        String cacheKey = key(lockId, keyboardPwd);
        IssuedPasscode passcode = issued.get(cacheKey);
        if (passcode == null) {
            throw new IllegalArgumentException("密码不存在或已失效：lockId=" + lockId);
        }

        // 有效期检查：真锁在窗口外会拒绝输入，模拟实现照此拒绝 ——
        // 少了这一条，模拟实现就比真锁宽松，超期密码在演示里照样能开门
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(passcode.startTime()) || now.isAfter(passcode.endTime())) {
            throw new IllegalArgumentException("密码已过期：lockId=" + lockId);
        }

        LockRecordDto record = new LockRecordDto(
                lockId, keyboardPwd, now, MOCK_OPEN_TYPE_KEYBOARD_PWD, userId);
        records.computeIfAbsent(lockId, k -> new CopyOnWriteArrayList<>()).add(record);

        // 一次性密码：用后即焚。真锁在正确输入一次之后就把单次密码作废，
        // 因此第二次使用会落到上面那条「密码不存在」的分支上
        if (passcode.singleUse()) {
            issued.remove(cacheKey);
            log.info("[Mock门锁] 一次性密码已使用并作废 lockId={} pwd={}", lockId, keyboardPwd);
        }

        log.info("[Mock门锁] 模拟开门 lockId={} pwd={} userId={}", lockId, keyboardPwd, userId);
        return record;
    }

    /**
     * 模拟一次网络调用：按配置注入延迟与随机失败。
     *
     * <p>对应配置项 {@code uspace.lock.mock.latency-millis} 与
     * {@code uspace.lock.mock.failure-rate}。默认两者均为 0，即不延迟、不失败。
     *
     * @param action 操作名称，仅用于日志
     * @return true 表示本次调用成功；false 表示模拟为失败
     */
    private boolean simulateNetwork(String action) {
        LockProperties.Mock mock = properties.getMock();

        if (mock.getLatencyMillis() > 0) {
            try {
                Thread.sleep(mock.getLatencyMillis());
            } catch (InterruptedException e) {
                // 恢复中断标志，交由上层感知，不吞掉中断
                Thread.currentThread().interrupt();
                log.warn("[Mock门锁] {}被中断", action);
                return false;
            }
        }

        if (mock.getFailureRate() > 0 && random.nextDouble() < mock.getFailureRate()) {
            log.warn("[Mock门锁] 模拟网络异常，{}失败", action);
            return false;
        }
        return true;
    }

    /**
     * 生成一个 6 位数字密码，且保证同一把锁上当前不重复。
     *
     * <p>通通锁的键盘密码为 6~7 位数字，具体位数由锁型号决定。
     *
     * <p>⚠️ <b>撞号必须重摇</b>：{@code issued} 的 key 是 {@code lockId:密码}，
     * 同一把锁上的固定限时密码与一次性密码若撞上同样的 6 位数字，会<b>静默覆盖</b> ——
     * 表现是「用一次性密码开了一次门，固定密码莫名其妙没了」，极难排查。
     *
     * @param lockId 锁 ID，用于查重
     * @return 6 位数字字符串，不足位补前导零
     * @throws IllegalStateException 连续 100 次都撞号（正常不可能发生）
     */
    private String randomPasscode(Long lockId) {
        for (int i = 0; i < 100; i++) {
            String candidate = String.format("%06d", random.nextInt(1_000_000));
            if (!issued.containsKey(key(lockId, candidate))) {
                return candidate;
            }
        }
        throw new IllegalStateException("无法生成不重复的密码：lockId=" + lockId);
    }

    /**
     * 拼装密码的内存缓存 key。
     *
     * @param lockId      锁 ID
     * @param keyboardPwd 密码
     * @return 形如 {@code 1001:123456} 的字符串
     */
    private static String key(Long lockId, String keyboardPwd) {
        return lockId + ":" + keyboardPwd;
    }

    /**
     * 模拟实现中记录的一条已下发密码。
     *
     * @param lockId          锁 ID
     * @param keyboardPwd     密码内容
     * @param startTime       生效时间
     * @param endTime         失效时间
     * @param keyboardPwdName 密码名称，便于后台识别用途
     * @param keyboardPwdId   密码 ID。真实场景由锁云分配，模拟实现自增
     * @param singleUse       是否一次性密码。为 true 时 {@link #simulateOpen} 用过一次即移除
     */
    private record IssuedPasscode(Long lockId, String keyboardPwd,
                                  LocalDateTime startTime, LocalDateTime endTime,
                                  String keyboardPwdName,
                                  Long keyboardPwdId,
                                  boolean singleUse) {

        /**
         * 返回一个仅有效期不同、其余字段相同的新实例。
         *
         * @param startTime 新的生效时间
         * @param endTime   新的失效时间
         * @return 新的密码记录
         */
        IssuedPasscode withValidity(LocalDateTime startTime, LocalDateTime endTime) {
            return new IssuedPasscode(lockId, keyboardPwd, startTime, endTime,
                    keyboardPwdName, keyboardPwdId, singleUse);
        }
    }
}
