-- user-service 表（计划书第 5 章）。库 mini_judge 需手工先建：
-- CREATE DATABASE IF NOT EXISTS mini_judge DEFAULT CHARSET utf8mb4;
CREATE TABLE IF NOT EXISTS t_user (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '用户ID',
  username      VARCHAR(64)  NOT NULL COMMENT '登录名',
  password_hash VARCHAR(128) NOT NULL COMMENT 'BCrypt 散列',
  nickname      VARCHAR(64)  NOT NULL,
  role          VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT 'USER/ADMIN，rejudge 用',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_username (username)
) ENGINE=InnoDB COMMENT='用户表';
