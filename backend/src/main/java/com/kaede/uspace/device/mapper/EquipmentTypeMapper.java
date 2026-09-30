package com.kaede.uspace.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kaede.uspace.device.entity.EquipmentType;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 设备类型字典的数据访问接口。
 *
 * <p>两个查询方法的区别只在一件事上：<b>要不要过滤停用的类型</b>。
 * 这个区分别小看 —— 用错会造成两类看起来毫不相干的问题：
 * <ul>
 *   <li>陈列组装误用 {@code selectEnabledList}：停用类型下的机台<b>整批从陈列页消失</b>，
 *       而数据库里它们好好的</li>
 *   <li>「可选类型」误用 {@code selectAllList}：已停用的类型又出现在下拉框里，
 *       运营以为删掉了却还能选</li>
 * </ul>
 *
 * <p>判断口径一句话：<b>「已经在用这个类型的机器/用户」用全量，
 * 「要新建的取值」用启用中的</b>。
 *
 * <p>手写 SQL 同样要自己带 {@code deleted = 0}（全局逻辑删除配置对手写 SQL 不生效）。
 */
public interface EquipmentTypeMapper extends BaseMapper<EquipmentType> {

    /**
     * 查询全部类型（含已停用），按排序权重排列。
     *
     * <p>供<b>陈列组装</b>与后台列表使用：机台挂着哪个类型，就必须能查到那个类型的
     * 名称与顺序 —— <b>哪怕类型已经停用</b>。一台机器不会因为运营停用了它的类型
     * 就凭空消失，停用的含义是「不再新增使用」，不是「删掉」。
     *
     * @return 类型列表；字典表为空时返回空列表
     */
    @Select("""
            SELECT *
              FROM biz_equipment_type
             WHERE deleted = 0
             ORDER BY sort ASC, id ASC
            """)
    List<EquipmentType> selectListAll();

    /**
     * 查询启用中的类型，按排序权重排列。
     *
     * <p>供<b>「可选类型」列表</b>使用：顾客选游玩偏好、管理员新增机台时挑类型，
     * 都只该看到还在用的那些。停用的类型不出现在这里。
     *
     * @return 启用中的类型列表；一条都没有时返回空列表
     */
    @Select("""
            SELECT *
              FROM biz_equipment_type
             WHERE deleted = 0
               AND enabled = 1
             ORDER BY sort ASC, id ASC
            """)
    List<EquipmentType> selectEnabledList();

    /**
     * 按 ID 查询类型（含已停用）。
     *
     * <p>供「新增/修改机台时校验类型是否存在」使用 —— 这里<b>不过滤 {@code enabled}</b>：
     * 把一台老机器改成它原本那个已停用的类型，是合法操作；停用只挡新增的取值。
     *
     * @param id 类型 ID
     * @return 类型；不存在或已删除时返回 null
     */
    @Select("""
            SELECT *
              FROM biz_equipment_type
             WHERE deleted = 0
               AND id = #{id}
            """)
    EquipmentType selectTypeById(@Param("id") Long id);
}
