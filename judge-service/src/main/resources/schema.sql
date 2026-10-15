-- judge-service 表。源码存 DB（64KB 上限）换实现简单；量大后的演进是对象存储，注释记债
CREATE TABLE IF NOT EXISTS t_submission (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id        BIGINT       NOT NULL,
  problem_id     BIGINT       NOT NULL,
  language       VARCHAR(8)   NOT NULL COMMENT 'JAVA17/CPP17',
  code           MEDIUMTEXT   NOT NULL,
  status         VARCHAR(8)   NOT NULL COMMENT 'QUEUED/RUNNING/DONE/REQUEUE',
  result_code    VARCHAR(8)   NULL COMMENT 'AC/WA/TLE/MLE/RE/CE',
  passed_cases   INT          NULL,
  total_cases    INT          NULL,
  time_used_ms   BIGINT       NULL,
  memory_used_kb BIGINT       NULL,
  error_msg      VARCHAR(1024) NULL,
  submit_time    DATETIME     NOT NULL,
  judged_time    DATETIME     NULL,
  -- 队列消费核心索引：WHERE status='QUEUED' ORDER BY submit_time 全命中
  KEY idx_status_submit (status, submit_time),
  KEY idx_user_problem (user_id, problem_id)
) ENGINE=InnoDB COMMENT='提交记录=队列表';

CREATE TABLE IF NOT EXISTS t_judge_log (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  submission_id  BIGINT      NOT NULL,
  event          VARCHAR(32) NOT NULL COMMENT 'QUEUED/DISPATCHED/COMPILED/CASE_n/RECOVERED',
  detail         VARCHAR(512) NULL,
  created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_submission (submission_id)
) ENGINE=InnoDB COMMENT='评测时间线，运维排查与复盘报告数据源';

CREATE TABLE IF NOT EXISTS t_language_config (
  language       VARCHAR(8)  PRIMARY KEY COMMENT 'JAVA17/CPP17',
  judge_image    VARCHAR(64) NOT NULL COMMENT '沙箱镜像名',
  compile_cmd    VARCHAR(256) NOT NULL,
  run_cmd_template VARCHAR(256) NOT NULL,
  enabled        TINYINT     NOT NULL DEFAULT 1
) ENGINE=InnoDB COMMENT='语言->镜像/命令映射：扩展新语言改配置不改代码';

-- 预置配置（INSERT IGNORE 幂等）
INSERT IGNORE INTO t_language_config (language, judge_image, compile_cmd, run_cmd_template)
VALUES ('JAVA17', 'judge-java:latest', 'javac -d /work/out /work/Main.java', 'java -cp /work/out Main');
