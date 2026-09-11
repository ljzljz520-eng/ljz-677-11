CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL,
    password VARCHAR(100),
    real_name VARCHAR(50),
    email VARCHAR(100),
    phone VARCHAR(20),
    status TINYINT DEFAULT 1,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS excel_data (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    data_code VARCHAR(50),
    name VARCHAR(50),
    id_card VARCHAR(20),
    phone VARCHAR(20),
    amount DECIMAL(15,2),
    address VARCHAR(200),
    remark VARCHAR(500),
    batch_no VARCHAR(50) NOT NULL,
    report_status TINYINT DEFAULT 0,
    report_message VARCHAR(500),
    report_time DATETIME,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS import_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no VARCHAR(50) NOT NULL,
    file_name VARCHAR(200),
    file_size BIGINT,
    total_count INT DEFAULT 0,
    read_count INT DEFAULT 0,
    validated_count INT DEFAULT 0,
    success_count INT DEFAULT 0,
    fail_count INT DEFAULT 0,
    status TINYINT DEFAULT 0,
    stage VARCHAR(20) DEFAULT 'PENDING',
    percent INT DEFAULT 0,
    eta_seconds BIGINT,
    rows_per_second DOUBLE,
    file_path VARCHAR(500),
    started_at DATETIME,
    finished_at DATETIME,
    error_details CLOB,
    operator_id BIGINT,
    operator_name VARCHAR(50),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS import_row_error (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no VARCHAR(50) NOT NULL,
    row_index INT,
    data_code VARCHAR(50),
    name VARCHAR(50),
    id_card VARCHAR(20),
    phone VARCHAR(20),
    amount VARCHAR(50),
    address VARCHAR(200),
    remark VARCHAR(500),
    error_msg VARCHAR(1000),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);
