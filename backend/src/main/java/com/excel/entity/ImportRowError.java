package com.excel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 导入校验失败的行（落库，避免内存累积）
 */
@Data
@TableName("import_row_error")
public class ImportRowError {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String batchNo;

    /**
     * Excel 行号（与文件中的行号一致，含表头偏移）
     */
    private Integer rowIndex;

    private String dataCode;

    private String name;

    private String idCard;

    private String phone;

    /**
     * 金额原始文本
     */
    private String amount;

    private String address;

    private String remark;

    private String errorMsg;

    private LocalDateTime createTime;
}
