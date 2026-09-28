package com.kaede.uspace.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 业务实体的公共基类。
 *
 * <p>承载各业务表共有的三个字段：创建时间、更新时间、逻辑删除标记。
 * 建表脚本里这三列几乎每张表都有，抽到基类后新增实体不必逐个重复声明，
 * 也不会出现「某张表漏了 updated_at」这种只在运行期才炸的疏漏。
 *
 * <p><b>审计字段由 MyBatis-Plus 自动填充</b>（见 {@code AuditMetaObjectHandler}），
 * 业务代码不要手工赋值 —— 手工赋值容易漏，且一旦漏了，
 * 这两个列是 {@code NOT NULL} 且无默认值，会在插入时才报错。
 *
 * <p><b>逻辑删除由 {@code application.properties} 的全局配置接管</b>
 * （{@code logic-delete-field=deleted}），因此这里不重复标注 {@code @TableLogic}。
 * 效果是：所有继承本类的实体，查询会自动过滤掉已删行，
 * 删除走 {@code UPDATE ... SET deleted = 1} 而非物理删除。
 * 订单等财务数据不允许物理删除，这条是硬性要求。
 *
 * <p><b>哪些实体不该继承本类</b>：日志、流水一类的表若不需要修改与软删
 * （如 {@code biz_access_record} 只有 created_at），就不必继承 ——
 * 多出来的字段反而会让人以为它们支持软删。
 */
@Data
public abstract class BaseEntity {

    /** 创建时间。插入时由框架自动填充，业务代码不要赋值 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间。插入与更新时都由框架自动填充 */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除标记：0=未删，1=已删。
     *
     * <p>查询时框架会自动追加 {@code deleted = 0} 条件，业务代码无需关心；
     * 删除时用 {@code removeById} 即可，框架会改写成 UPDATE 语句。
     */
    private Integer deleted;
}
