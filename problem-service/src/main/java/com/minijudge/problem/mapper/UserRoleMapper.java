package com.minijudge.problem.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 跨服务读表（MVP 妥协，重要设计说明）：t_user 的属主是 user-service，
 * 本接口直接 SELECT 它的 role 列，仅因当前 MVP 三个服务共用同一个
 * mini_judge 库、链路最短。这属于"共享数据库反模式"的有意残留——
 * 属主服务改表结构会悄悄破坏这里，且无法按服务做权限边界。
 *
 * <p>生产演进：与 judge-service 的 rejudge 收口对齐，改用 Feign 调
 * user-service 的 GET /api/users/{id}/role（窄接口、跨库隔离）；
 * judge-service 已是该形态的现成范例（UserClient），切换时本类整体删除。</p>
 */
@Mapper
public interface UserRoleMapper {

    /**
     * 按用户 id 查角色。返回值可能为 null（用户不存在，或历史行 role 列为空）——
     * 调用方必须按"非 ADMIN 即拒绝"处理（fail-closed + 无角色数据默认 USER）。
     */
    @Select("SELECT role FROM t_user WHERE id = #{userId}")
    String selectRoleById(@Param("userId") Long userId);
}
