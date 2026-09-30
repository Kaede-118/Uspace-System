package com.kaede.uspace.device;

import com.kaede.uspace.device.entity.EquipmentType;
import com.kaede.uspace.device.mapper.EquipmentTypeMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存版的 {@link EquipmentTypeMapper}，让模块 4 的单元测试不依赖数据库。
 *
 * <p><b>两个列表方法的区别是本假实现最要紧的地方</b>：
 * {@code selectListAll} 不过滤停用，{@code selectEnabledList} 只给启用的。
 * 用错会造成「停用类型下的机台整批从陈列里消失」或「已停用的类型又能被选」——
 * 两类问题都不会报错，只会让界面上的数据莫名其妙。因此这里逐字照抄 SQL 的过滤条件。
 *
 * <p>其余约定：动态代理、按方法名分发、模拟逻辑删除。
 */
public class FakeEquipmentTypeMapper implements InvocationHandler {

    /** 模拟数据表 */
    private final Map<Long, EquipmentType> rows = new LinkedHashMap<>();

    /** 模拟自增主键 */
    private long nextId = 1;

    /**
     * 生成接口代理，交给 Service 使用。
     *
     * @return EquipmentTypeMapper 的假实现
     */
    public EquipmentTypeMapper asMapper() {
        return (EquipmentTypeMapper) Proxy.newProxyInstance(
                EquipmentTypeMapper.class.getClassLoader(),
                new Class<?>[]{EquipmentTypeMapper.class},
                this);
    }

    /**
     * 预置一条类型。
     *
     * <p>{@code enabled} 未设置时按 1（启用）处理，与建表脚本的默认值一致。
     *
     * @param type 类型，id 为空时自动分配
     * @return 同一个对象，便于链式构造
     */
    public EquipmentType seed(EquipmentType type) {
        if (type.getId() == null) {
            type.setId(allocateId());
        }
        if (type.getEnabled() == null) {
            type.setEnabled(1);
        }
        if (type.getDeleted() == null) {
            type.setDeleted(0);
        }
        rows.put(type.getId(), type);
        return type;
    }

    /**
     * 分配一个未被占用的自增主键。
     *
     * @return 新 ID
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
     * @param id 类型 ID
     * @return 类型；不存在时返回 null
     */
    public EquipmentType get(Long id) {
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
            case "selectListAll" -> selectListAll();
            case "selectEnabledList" -> selectEnabledList();
            case "selectTypeById" -> selectTypeById((Long) args[0]);
            default -> throw new UnsupportedOperationException(
                    "假 Mapper 未实现方法 " + method.getName()
                            + " —— 出现这个错误说明 Service 调用了预期之外的方法，"
                            + "请在 FakeEquipmentTypeMapper 中补上对应实现");
        };
    }

    // ==================================================================
    // 各方法的模拟实现
    // ==================================================================

    /**
     * 查询全部类型（含已停用），按 {@code sort ASC, id ASC} 排列。
     *
     * @return 类型列表
     */
    private List<EquipmentType> selectListAll() {
        return rows.values().stream()
                .filter(this::isAlive)
                .sorted(Comparator.comparing(EquipmentType::getSort)
                        .thenComparing(EquipmentType::getId))
                .toList();
    }

    /**
     * 查询启用中的类型，按 {@code sort ASC, id ASC} 排列。
     *
     * @return 类型列表
     */
    private List<EquipmentType> selectEnabledList() {
        return rows.values().stream()
                .filter(this::isAlive)
                .filter(t -> t.getEnabled() != null && t.getEnabled() == 1)
                .sorted(Comparator.comparing(EquipmentType::getSort)
                        .thenComparing(EquipmentType::getId))
                .toList();
    }

    /**
     * 按 ID 查询类型（含已停用）。
     *
     * @param id 类型 ID
     * @return 类型；不存在或已删除时返回 null
     */
    private EquipmentType selectTypeById(Long id) {
        EquipmentType type = rows.get(id);
        return isAlive(type) ? type : null;
    }

    /**
     * 判断一行是否未被逻辑删除。
     *
     * @param type 类型，可为 null
     * @return 有效返回 true
     */
    private boolean isAlive(EquipmentType type) {
        return type != null && (type.getDeleted() == null || type.getDeleted() == 0);
    }
}
