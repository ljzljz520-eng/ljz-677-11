package com.excel.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 导入失败行（行级错误落库，不在内存中堆积）
 */
@Data
@TableName("import_error_row")
public class ImportErrorRow {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String batchNo;

    /**
     * Excel行号（含表头，数据行从2开始）
     */
    private Integer rowIndex;

    private String dataCode;

    private String name;

    private String idCard;

    private String phone;

    /**
     * 金额以原文保留，防止格式异常无法反序列化
     */
    private String amount;

    private String address;

    private String remark;

    /**
     * 错误原因
     */
    private String errorMsg;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}
