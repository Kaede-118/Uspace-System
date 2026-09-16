package com.kaede.uspace.lock;

import com.kaede.uspace.lock.dto.AddPasscodeRequest;
import com.kaede.uspace.lock.dto.LockRecordDto;
import com.kaede.uspace.lock.dto.LockStatus;
import com.kaede.uspace.lock.dto.PasscodeResult;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 智能门锁服务接口。
 *
 * <p><b>本接口的方法与参数按通通锁（TTLock）云 API 的真实规格设计</b>，
 * 每个方法都标注了对应的云端接口。当前由 {@code mock} 子包下的模拟实现提供，
 * 后期接入真实门锁云时只需新增一个实现类，Controller 与上层业务代码无需改动。
 *
 * <p>实现类的选择由配置项 {@code uspace.lock.provider} 决定：
 * {@code mock}（默认，模拟）或 {@code ttlock}（真实云端，待实现）。
 *
 * @see com.kaede.uspace.lock.mock.MockLockServiceImpl
 */
public interface LockService {

    /**
     * 远程下发限时密码。
     *
     * <p>对应通通锁 {@code POST /v3/keyboardPwd/add}。
     *
     * <p>注意两条真实约束：
     * <ol>
     *   <li>远程下发要求 {@code addType=2}，即目标锁需接入网关或本身是 Wi-Fi 锁；
     *       纯蓝牙锁只能先在 APP 内用蓝牙添加、再调接口同步（{@code addType=1}）</li>
     *   <li>自定义密码只有三代锁支持，且只能设为限时密码 —— 必须给出有效期，
     *       无法签发永不过期的一次性密码</li>
     * </ol>
     *
     * @param request 下发参数，含锁 ID、密码内容与生效/失效时间
     * @return 下发结果，成功时包含最终生效的密码
     */
    PasscodeResult addPasscode(AddPasscodeRequest request);

    /**
     * 修改已有密码的有效期。
     *
     * <p>对应通通锁 {@code POST /v3/keyboardPwd/change}。
     * 用于订单续时、提前退房等场景。
     *
     * @param lockId      锁 ID
     * @param keyboardPwd 原密码
     * @param startTime   新的生效时间
     * @param endTime     新的失效时间
     * @return 修改结果
     */
    PasscodeResult changePasscode(Long lockId, String keyboardPwd, LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 删除密码。
     *
     * <p>对应通通锁 {@code POST /v3/keyboardPwd/delete}。订单结束或用户离场时调用，
     * 使密码立即失效。
     *
     * @param lockId      锁 ID
     * @param keyboardPwd 待删除的密码
     * @return 删除结果
     */
    PasscodeResult deletePasscode(Long lockId, String keyboardPwd);

    /**
     * 查询锁的在线状态。
     *
     * <p>对应通通锁 {@code POST /v3/lock/list} 或 {@code /v3/lock/detail}。
     * 供「设备管理」模块（模块 4）判断锁是否在线。
     *
     * @param lockId 锁 ID
     * @return 在线状态
     */
    LockStatus queryStatus(Long lockId);

    /**
     * 查询指定时间段内的开门记录。
     *
     * <p>对应通通锁 {@code POST /v3/lockRecord/list}。供「进出记录」模块（模块 6）使用。
     *
     * <p><b>调用额度提示</b>：通通锁开放平台的免费额度为 30000 次/月，
     * 本方法应按需调用，<b>不要做成定时轮询</b>，否则会快速耗尽额度。
     *
     * @param lockId 锁 ID
     * @param from   起始时间（含）
     * @param to     结束时间（含）
     * @return 开门记录列表，按开门时间升序
     */
    List<LockRecordDto> listRecords(Long lockId, LocalDateTime from, LocalDateTime to);
}
