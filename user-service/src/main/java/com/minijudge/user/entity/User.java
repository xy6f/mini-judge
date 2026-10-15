package com.minijudge.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户实体（对应计划书 t_user）。passwordHash 永不随查询返回 */
@Data
@TableName("t_user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    @TableField(select = false)
    private String passwordHash;

    private String nickname;

    /** 角色：USER/ADMIN，rejudge 等管理接口校验用 */
    private String role;

    private LocalDateTime createdAt;
}
