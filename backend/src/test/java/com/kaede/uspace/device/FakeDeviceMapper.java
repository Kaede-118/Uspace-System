package com.kaede.uspace.device;

import com.kaede.uspace.device.entity.Device;
import com.kaede.uspace.device.mapper.DeviceMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存版的 {@link DeviceMapper}，让模块 4 的单元测试不依赖数据库。
 *
 * <p><b>排序与过滤的口径必须与真实 SQL 逐字一致</b>：列表都是
 * {@code ORDER BY sort ASC, id ASC}，都是「先过滤已删除、再过滤门店」。
 * 差别一个字，分组顺序的用例就会得出相反结论，而单测照样通过 ——
 * 那比没有测试更危险。理由同 {@code FakeClosureMapper}。
 *
 * <p>其余约定：动态代理、按方法名分发、模拟逻辑删除。
 */
public class FakeDeviceMapper implements InvocationHandler {

    /** 模拟数据表 */
    private final Map<Long, Device> rows = new LinkedHashMap<>();

    /** 模拟自增主键 */
    private long nextId = 1;

    /**
     * 生成接口代理，交给 Service 使用。
     *
     * @return DeviceMapper 的假实现
     */
    public DeviceMapper asMapper() {
        return (DeviceMapper) Proxy.newProxyInstance(
                DeviceMapper.class.getClassLoader(),
                new Class<?>[]{DeviceMapper.class},
                this);
    }

    /**
     * 预置一条机台。
     *
     * @param device 机台，id 为空时自动分配
     * @return 同一个对象，便于链式构造
     */
    public Device seed(Device device) {
        if (device.getId() == null) {
            device.setId(allocateId());
        }
        if (device.getDeleted() == null) {
            device.setDeleted(0);
        }
        rows.put(device.getId(), device);
        return device;
    }

    /**
     * 分配一个未被占用的自增主键。
     *
     * <p>必须跳过已占用的 ID：测试里可能用显式 ID 预置数据，
     * 若游标不跳过它，后续不带 ID 的 {@code seed} 会分配到同一个 ID
     * 并把先前的记录悄悄覆盖掉。
     */
    private long allocateId() {
        while (rows.containsKey(nextId)) {
            nextId++;
        }
        return nextId++;
    }

    /**
     * 按 ID 取出表中的当前状态，供测试断言。
     *
     * @param id 机台 ID
     * @return 机台；不存在时返回 null
     */
    public Device get(Long id) {
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
            case "insert" -> insert((Device) args[0]);
            case "selectById" -> selectById((Long) args[0]);
            case "selectListByStore" -> selectListByStore((Long) args[0]);
            case "selectListByName" -> selectListByName(args);
            case "countByDeviceNo" -> countByDeviceNo(args);
            case "updateDevice" -> updateDevice(args);
            case "updateStatus" -> updateStatus(args);
            case "deleteById" -> deleteById((Long) args[0]);
            default -> throw new UnsupportedOperationException(
                    "假 Mapper 未实现方法 " + method.getName()
                            + " —— 出现这个错误说明 Service 调用了预期之外的方法，"
                            + "请在 FakeDeviceMapper 中补上对应实现");
        };
    }

    // ==================================================================
    // 各方法的模拟实现
    // ==================================================================

    /**
     * 插入。模拟自增主键与逻辑删除标记的默认值。
     *
     * @param device 待插入的机台
     * @return 受影响行数，恒为 1
     */
    private int insert(Device device) {
        device.setId(allocateId());
        if (device.getDeleted() == null) {
            device.setDeleted(0);
        }
        rows.put(device.getId(), device);
        return 1;
    }

    /**
     * 按 ID 查询，过滤已逻辑删除的行。
     *
     * <p>⚠️ <b>返回的是内存里的那一个对象本身，不是副本</b> —— 与真实 MyBatis 的行为
     * <b>不同</b>：真实实现每次都从 ResultSet 构造新对象。由此产生一个容易被坑的差别：
     * <pre>
     *   Device existing = mapper.selectById(id);   // 拿到引用
     *   mapper.updateStatus(id, "NEW");
     *   existing.getStatus();                      // 真实环境 = 旧值；假 Mapper = 新值
     * </pre>
     * 所以 Service 里<b>凡是需要「变更前的值」，必须在写入之前取出来存进局部变量</b>，
     * 不能等写完再去读那个对象。DeviceService 的 updateDevice / updateStatus 就是这么写的。
     *
     * <p>（不把本方法改成返回副本，是因为那会牵动既有用例里
     * {@code seed()} 返回引用、断言同一对象的写法。记录在这里比默默改掉更稳妥。）
     *
     * @param id 机台 ID
     * @return 机台；不存在或已删除时返回 null
     */
    private Device selectById(Long id) {
        Device device = rows.get(id);
        return isAlive(device) ? device : null;
    }

    /**
     * 查询某门店的全部机台，按 {@code sort ASC, id ASC} 排列。
     *
     * <p>排序口径与真实 SQL 逐字一致 —— 见类注释。
     *
     * @param storeId 门店 ID
     * @return 机台列表
     */
    private List<Device> selectListByStore(Long storeId) {
        return rows.values().stream()
                .filter(this::isAlive)
                .filter(d -> storeId.equals(d.getStoreId()))
                .sorted(Comparator.comparing(Device::getSort)
                        .thenComparing(Device::getId))
                .toList();
    }

    /**
     * 按名字查同门店的机台，按 {@code id ASC} 排列。
     *
     * <p>名字用 {@code equals} 比对，模拟 SQL 里 {@code name = #{name}}：
     * <b>完全一致才算命中</b>（不 trim、不模糊）—— 与真实 SQL 逐字一致，
     * 差别一个字，「打错半个字就改错机器」那条守门用例就会得出相反结论。
     *
     * @param args 依次为门店 ID、机台名
     * @return 同名机台；没有时返回空列表
     */
    private List<Device> selectListByName(Object[] args) {
        Long storeId = (Long) args[0];
        String name = (String) args[1];
        return rows.values().stream()
                .filter(this::isAlive)
                .filter(d -> storeId.equals(d.getStoreId()))
                .filter(d -> name != null && name.equals(d.getName()))
                .sorted(Comparator.comparing(Device::getId))
                .toList();
    }

    /**
     * 统计同门店内编号重复的机台数。
     *
     * <p>用 {@code equals} 比对，模拟 SQL 里 {@code device_no = #{deviceNo}} 的语义：
     * 编号为 null 时任何行都不匹配（{@code NULL = NULL} 不成立），返回 0。
     *
     * @param args 依次为门店 ID、编号、排除的机台 ID
     * @return 重复的机台数
     */
    private int countByDeviceNo(Object[] args) {
        Long storeId = (Long) args[0];
        String deviceNo = (String) args[1];
        Long excludeId = (Long) args[2];

        return (int) rows.values().stream()
                .filter(this::isAlive)
                .filter(d -> storeId.equals(d.getStoreId()))
                .filter(d -> deviceNo != null && deviceNo.equals(d.getDeviceNo()))
                .filter(d -> excludeId == null || !excludeId.equals(d.getId()))
                .count();
    }

    /**
     * 全量更新机台字段。
     *
     * @param args 依次为 id、name、deviceNo、typeId、location、status、sort、remark
     * @return 受影响行数；0 表示机台不存在或已删除
     */
    private int updateDevice(Object[] args) {
        Device device = selectById((Long) args[0]);
        if (device == null) {
            return 0;
        }
        device.setName((String) args[1]);
        device.setDeviceNo((String) args[2]);
        device.setTypeId((Long) args[3]);
        device.setLocation((String) args[4]);
        device.setStatus((String) args[5]);
        device.setSort((Integer) args[6]);
        device.setRemark((String) args[7]);
        return 1;
    }

    /**
     * 只更新状况。
     *
     * <p>刻意<b>不碰其他字段</b>，与真实 SQL 一致 —— 这条正是
     * 「改状况不会覆盖别人刚改的名称」的守门用例所依赖的行为。
     *
     * @param args 依次为 id、status
     * @return 受影响行数；0 表示机台不存在或已删除
     */
    private int updateStatus(Object[] args) {
        Device device = selectById((Long) args[0]);
        if (device == null) {
            return 0;
        }
        device.setStatus((String) args[1]);
        return 1;
    }

    /**
     * 逻辑删除。模拟真实库里 {@code DELETE} 被改写成 {@code UPDATE ... SET deleted = 1}。
     *
     * @param id 机台 ID
     * @return 受影响行数；0 表示机台不存在或已删除
     */
    private int deleteById(Long id) {
        Device device = selectById(id);
        if (device == null) {
            return 0;
        }
        device.setDeleted(1);
        return 1;
    }

    /**
     * 判断一行是否未被逻辑删除。
     *
     * @param device 机台，可为 null
     * @return 有效返回 true
     */
    private boolean isAlive(Device device) {
        return device != null && (device.getDeleted() == null || device.getDeleted() == 0);
    }
}
