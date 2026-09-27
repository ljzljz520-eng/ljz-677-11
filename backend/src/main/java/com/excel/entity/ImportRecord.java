package com.excel.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("import_record")
public class ImportRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 批次号
     */
    private String batchNo;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * 服务器临时文件存储路径
     */
    private String filePath;

    /**
     * 总记录数（预扫描后回填）
     */
    private Integer totalCount;

    /**
     * 已读取（SAX已解析）行数
     */
    private Integer readCount;

    /**
     * 已校验行数
     */
    private Integer processedCount;

    /**
     * 成功数量
     */
    private Integer successCount;

    /**
     * 失败数量
     */
    private Integer failCount;

    /**
     * 导入状态：0-解析中 1-完成 2-完成(有失败) 3-失败
     */
    private Integer status;

    /**
     * 处理阶段：COUNTING / PARSING / PROCESSING / DONE / FAILED
     */
    private String phase;

    /**
     * 任务级致命错误信息（行级错误存 import_error_row）
     */
    private String errorDetails;

    /**
     * 开始处理时间（用于 ETA 计算）
     */
    private LocalDateTime startedAt;

    /**
     * 操作人ID
     */
    private Long operatorId;

    /**
     * 操作人姓名
     */
    private String operatorName;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
