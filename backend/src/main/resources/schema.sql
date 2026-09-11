-- 用户表
CREATE TABLE IF NOT EXISTS `sys_user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `username` VARCHAR(50) NOT NULL COMMENT '用户名',
    `password` VARCHAR(100) NOT NULL COMMENT '密码（BCrypt加密）',
    `real_name` VARCHAR(50) COMMENT '真实姓名',
    `email` VARCHAR(100) COMMENT '邮箱',
    `phone` VARCHAR(20) COMMENT '手机号',
    `status` TINYINT DEFAULT 1 COMMENT '状态：0-禁用 1-启用',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '是否删除：0-否 1-是',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- Excel数据表
CREATE TABLE IF NOT EXISTS `excel_data` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `data_code` VARCHAR(50) NOT NULL COMMENT '数据编号',
    `name` VARCHAR(50) NOT NULL COMMENT '姓名',
    `id_card` VARCHAR(20) COMMENT '身份证号',
    `phone` VARCHAR(20) COMMENT '手机号',
    `amount` DECIMAL(15,2) COMMENT '金额',
    `address` VARCHAR(200) COMMENT '地址',
    `remark` VARCHAR(500) COMMENT '备注',
    `batch_no` VARCHAR(50) NOT NULL COMMENT '导入批次号',
    `report_status` TINYINT DEFAULT 0 COMMENT '上报状态：0-待上报 1-已上报 2-上报失败',
    `report_message` VARCHAR(500) COMMENT '上报结果信息',
    `report_time` DATETIME COMMENT '上报时间',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '是否删除：0-否 1-是',
    PRIMARY KEY (`id`),
    INDEX `idx_batch_no` (`batch_no`),
    INDEX `idx_report_status` (`report_status`),
    INDEX `idx_data_code` (`data_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Excel数据表';

-- 导入记录表（异步任务）
CREATE TABLE IF NOT EXISTS `import_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `batch_no` VARCHAR(50) NOT NULL COMMENT '任务编号/批次号',
    `file_name` VARCHAR(200) COMMENT '文件名',
    `file_size` BIGINT COMMENT '文件大小（字节）',
    `total_count` INT DEFAULT 0 COMMENT '预估总行数',
    `read_count` INT DEFAULT 0 COMMENT '已读取行数',
    `validated_count` INT DEFAULT 0 COMMENT '已校验行数',
    `success_count` INT DEFAULT 0 COMMENT '成功数量',
    `fail_count` INT DEFAULT 0 COMMENT '失败数量',
    `status` TINYINT DEFAULT 0 COMMENT '状态：0-排队/处理中 1-完成 2-部分失败 3-失败/中断',
    `stage` VARCHAR(20) DEFAULT 'PENDING' COMMENT '阶段：PENDING/COUNTING/PARSING/SAVING/DONE/FAILED',
    `percent` INT DEFAULT 0 COMMENT '进度百分比0-100',
    `eta_seconds` BIGINT COMMENT '预计剩余秒数',
    `rows_per_second` DOUBLE COMMENT '处理速率（行/秒）',
    `file_path` VARCHAR(500) COMMENT '服务端临时文件路径',
    `started_at` DATETIME COMMENT '开始处理时间',
    `finished_at` DATETIME COMMENT '结束处理时间',
    `error_details` TEXT COMMENT '任务级错误信息',
    `operator_id` BIGINT COMMENT '操作人ID',
    `operator_name` VARCHAR(50) COMMENT '操作人姓名',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '是否删除：0-否 1-是',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_batch_no` (`batch_no`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='导入任务记录表';

-- 导入错误行表（校验失败的行落库，避免内存累积，支持分页查看/导出）
CREATE TABLE IF NOT EXISTS `import_row_error` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `batch_no` VARCHAR(50) NOT NULL COMMENT '任务编号',
    `row_index` INT COMMENT 'Excel行号（含表头偏移）',
    `data_code` VARCHAR(50) COMMENT '数据编号',
    `name` VARCHAR(50) COMMENT '姓名',
    `id_card` VARCHAR(20) COMMENT '身份证号',
    `phone` VARCHAR(20) COMMENT '手机号',
    `amount` VARCHAR(50) COMMENT '金额（原始值）',
    `address` VARCHAR(200) COMMENT '地址',
    `remark` VARCHAR(500) COMMENT '备注',
    `error_msg` VARCHAR(1000) COMMENT '错误原因',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_batch_row` (`batch_no`, `row_index`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='导入校验错误行表';

-- 兼容已存在的旧库：幂等补齐新列（存储过程判断列是否存在）
DROP PROCEDURE IF EXISTS `add_import_columns`;
DELIMITER //
CREATE PROCEDURE `add_import_columns`()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'import_record'
                     AND COLUMN_NAME = 'read_count') THEN
        ALTER TABLE `import_record`
            ADD COLUMN `read_count` INT DEFAULT 0 COMMENT '已读取行数' AFTER `total_count`,
            ADD COLUMN `validated_count` INT DEFAULT 0 COMMENT '已校验行数' AFTER `read_count`,
            ADD COLUMN `stage` VARCHAR(20) DEFAULT 'PENDING' COMMENT '阶段' AFTER `status`,
            ADD COLUMN `percent` INT DEFAULT 0 COMMENT '进度百分比' AFTER `stage`,
            ADD COLUMN `eta_seconds` BIGINT COMMENT '预计剩余秒数' AFTER `percent`,
            ADD COLUMN `rows_per_second` DOUBLE COMMENT '速率行/秒' AFTER `eta_seconds`,
            ADD COLUMN `file_path` VARCHAR(500) COMMENT '临时文件路径' AFTER `rows_per_second`,
            ADD COLUMN `started_at` DATETIME COMMENT '开始时间' AFTER `file_path`,
            ADD COLUMN `finished_at` DATETIME COMMENT '结束时间' AFTER `started_at`,
            ADD INDEX `idx_status` (`status`);
    END IF;
END //
DELIMITER ;
CALL `add_import_columns`();
DROP PROCEDURE IF EXISTS `add_import_columns`;

-- 管理员用户由应用启动时通过 DataInitializer 自动创建
-- 账号: admin  密码: admin123
