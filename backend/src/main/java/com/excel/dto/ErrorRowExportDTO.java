package com.excel.dto;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import lombok.Data;

/**
 * 校验失败行导出模型（含行号与错误原因）
 */
@Data
public class ErrorRowExportDTO {

    @ExcelProperty(value = "Excel行号", index = 0)
    @ColumnWidth(10)
    private Integer rowIndex;

    @ExcelProperty(value = "数据编号", index = 1)
    @ColumnWidth(18)
    private String dataCode;

    @ExcelProperty(value = "姓名", index = 2)
    @ColumnWidth(12)
    private String name;

    @ExcelProperty(value = "身份证号", index = 3)
    @ColumnWidth(22)
    private String idCard;

    @ExcelProperty(value = "手机号", index = 4)
    @ColumnWidth(15)
    private String phone;

    @ExcelProperty(value = "金额", index = 5)
    @ColumnWidth(12)
    private String amount;

    @ExcelProperty(value = "地址", index = 6)
    @ColumnWidth(30)
    private String address;

    @ExcelProperty(value = "备注", index = 7)
    @ColumnWidth(20)
    private String remark;

    @ExcelProperty(value = "错误原因", index = 8)
    @ColumnWidth(40)
    private String errorMsg;
}
