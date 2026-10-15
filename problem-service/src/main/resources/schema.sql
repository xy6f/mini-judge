-- problem-service 表（库 mini_judge 共用）。用例数据存文件，表只存元信息
CREATE TABLE IF NOT EXISTS t_problem (
  id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  title            VARCHAR(128) NOT NULL,
  difficulty       VARCHAR(8)   NOT NULL DEFAULT 'EASY' COMMENT 'EASY/MEDIUM/HARD',
  time_limit_ms    INT          NOT NULL DEFAULT 2000 COMMENT '单用例CPU时限',
  memory_limit_kb  INT          NOT NULL DEFAULT 262144 COMMENT '内存上限256MB',
  description      TEXT         NOT NULL,
  -- PUBLISHED 是 9 个字符：VARCHAR(8) 会把建题 insert 直接顶成
  -- Data too long for column 'status'（500），这里留 16 宽裕量
  status           VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED',
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='题目表';

CREATE TABLE IF NOT EXISTS t_test_case (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  problem_id  BIGINT      NOT NULL,
  input_path  VARCHAR(512) NOT NULL COMMENT '用例输入文件路径，容器只读挂载',
  output_path VARCHAR(512) NOT NULL,
  is_sample   TINYINT     NOT NULL DEFAULT 0 COMMENT '1=题面可见样例',
  order_no    INT         NOT NULL,
  UNIQUE KEY uk_problem_order (problem_id, order_no),
  KEY idx_problem (problem_id)
) ENGINE=InnoDB COMMENT='测试用例元信息';
