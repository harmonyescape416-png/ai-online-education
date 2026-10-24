-- M15.20-C：开课失败补偿记录（独立扩展表；幂等）
CREATE TABLE IF NOT EXISTS enroll_compensation (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    order_id      BIGINT       NOT NULL COMMENT '订单ID',
    course_id     BIGINT       NOT NULL COMMENT '课程ID',
    user_id       BIGINT       NOT NULL COMMENT '用户ID',
    attempts      INT          NOT NULL DEFAULT 0 COMMENT '尝试次数',
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SUCCESS/FAILED_MANUAL',
    last_error    VARCHAR(500) NULL,
    next_retry_at DATETIME     NULL,
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_course (order_id, course_id),
    KEY idx_status_retry (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='开课失败补偿（M15.20-C）';
