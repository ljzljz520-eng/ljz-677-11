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
    `batch_no` VARCHAR(50) NOT NULL COMMENT '任务/批次号',
    `file_name` VARCHAR(200) COMMENT '文件名',
    `file_size` BIGINT COMMENT '文件大小（字节）',
    `file_path` VARCHAR(500) COMMENT '服务器临时存储路径',
    `total_count` INT DEFAULT 0 COMMENT '总记录数（预扫描后回填）',
    `read_count` INT DEFAULT 0 COMMENT '已读取（SAX已解析）行数',
    `processed_count` INT DEFAULT 0 COMMENT '已校验行数',
    `success_count` INT DEFAULT 0 COMMENT '校验通过并入库数量',
    `fail_count` INT DEFAULT 0 COMMENT '校验失败数量',
    `status` TINYINT DEFAULT 0 COMMENT '状态：0-解析中 1-完成 2-完成(有失败) 3-失败',
    `phase` VARCHAR(20) DEFAULT 'PARSING' COMMENT '阶段：PARSING/COUNTING/PROCESSING/DONE/FAILED',
    `error_details` VARCHAR(1000) COMMENT '任务级致命错误信息（行级错误存import_error_row）',
    `started_at` DATETIME COMMENT '开始处理时间（用于ETA）',
    `operator_id` BIGINT COMMENT '操作人ID',
    `operator_name` VARCHAR(50) COMMENT '操作人姓名',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '是否删除：0-否 1-是',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_batch_no` (`batch_no`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='导入记录表';

-- 导入失败行表（行级错误落库，避免内存堆积）
CREATE TABLE IF NOT EXISTS `import_error_row` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `batch_no` VARCHAR(50) NOT NULL COMMENT '任务/批次号',
    `row_index` INT COMMENT 'Excel行号（含表头，从2开始）',
    `data_code` VARCHAR(50) COMMENT '数据编号',
    `name` VARCHAR(50) COMMENT '姓名',
    `id_card` VARCHAR(20) COMMENT '身份证号',
    `phone` VARCHAR(20) COMMENT '手机号',
    `amount` VARCHAR(50) COMMENT '金额（原样保留）',
    `address` VARCHAR(200) COMMENT '地址',
    `remark` VARCHAR(500) COMMENT '备注',
    `error_msg` VARCHAR(1000) COMMENT '错误原因',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '是否删除：0-否 1-是',
    PRIMARY KEY (`id`),
    KEY `idx_batch_no` (`batch_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='导入失败行表';

-- 管理员用户由应用启动时通过 DataInitializer 自动创建
-- 账号: admin  密码: admin123
