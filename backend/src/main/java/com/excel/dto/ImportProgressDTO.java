package com.excel.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 导入任务进度（GET /api/excel/import/{batchNo}/progress）
 * 页面重新打开后凭任务编号查询，进度全部来自数据库，进程重启也可恢复展示
 */
@Data
@Builder
public class ImportProgressDTO {

    /** 任务/批次号 */
    private String batchNo;

    private String fileName;

    /** 状态：0-解析中 1-完成 2-完成(有失败) 3-失败 */
    private Integer status;

    /** 阶段文字：COUNTING/PARSING/PROCESSING/DONE/FAILED */
    private String phase;

    /** 总行数（预扫描完成前可能为0，此时progress为-1表示未知） */
    private Integer totalCount;

    /** 已读取行数（SAX已解析） */
    private Integer readCount;

    /** 已校验行数 */
    private Integer processedCount;

    /** 校验通过行数 */
    private Integer successCount;

    /** 校验失败行数 */
    private Integer failCount;

    /** 0-100；总行数未知时为-1 */
    private Integer progress;

    /** 预估剩余秒数；未知时为null */
    private Long etaSeconds;

    /** 处理速度：行/秒 */
    private Double rowsPerSecond;

    /** 任务级错误信息 */
    private String errorMessage;
}
