package com.excel.controller;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.excel.dto.*;
import com.excel.entity.ExcelData;
import com.excel.entity.ImportErrorRow;
import com.excel.entity.ImportRecord;
import com.excel.service.AsyncImportService;
import com.excel.service.ExcelImportService;
import com.excel.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/excel")
@RequiredArgsConstructor
@Tag(name = "Excel导入管理", description = "医保清单分批异步导入、进度查询与上报接口")
public class ExcelController {

    private static final Logger logger = LoggerFactory.getLogger(ExcelController.class);

    private final AsyncImportService asyncImportService;
    private final ExcelImportService excelImportService;
    private final ReportService reportService;

    @PostMapping("/import")
    @Operation(summary = "创建导入任务", description = "上传Excel，后端落盘后异步分批解析，立即返回任务编号")
    public ApiResponse<Map<String, String>> importExcel(
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            String batchNo = asyncImportService.createTask(file, userId);
            return ApiResponse.success("任务已创建", Map.of("batchNo", batchNo));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            logger.error("创建导入任务失败", e);
            return ApiResponse.error("创建导入任务失败: " + e.getMessage());
        }
    }

    @GetMapping("/import/{batchNo}/progress")
    @Operation(summary = "查询导入进度", description = "凭任务编号查看已读取/已校验/失败行数及预估剩余时间")
    public ApiResponse<ImportProgressDTO> getProgress(@PathVariable String batchNo) {
        return ApiResponse.success(asyncImportService.getProgress(batchNo));
    }

    @GetMapping("/import/{batchNo}/errors")
    @Operation(summary = "分页查询校验失败行")
    public ApiResponse<Page<ImportErrorRow>> getErrors(
            @PathVariable String batchNo,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return ApiResponse.success(asyncImportService.getErrorRows(batchNo, pageNum, pageSize));
    }

    @GetMapping("/import/{batchNo}/errors/export")
    @Operation(summary = "导出校验失败行", description = "流式分页写出，不把全部失败行加载进内存")
    public void exportImportErrors(@PathVariable String batchNo, HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = URLEncoder.encode("校验失败数据_" + batchNo, StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");
        response.setHeader("Content-disposition", "attachment;filename*=utf-''" + fileName + ".xlsx");
        asyncImportService.writeErrorExcel(batchNo, response.getOutputStream());
    }

    @GetMapping("/records")
    @Operation(summary = "获取导入记录", description = "分页获取导入记录列表")
    public ApiResponse<Page<ImportRecord>> getImportRecords(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<ImportRecord> page = excelImportService.getImportRecords(pageNum, pageSize);
        return ApiResponse.success(page);
    }

    @GetMapping("/data/{batchNo}")
    @Operation(summary = "获取批次数据", description = "根据批次号分页获取数据")
    public ApiResponse<Page<ExcelData>> getDataByBatch(
            @PathVariable String batchNo,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<ExcelData> page = excelImportService.getDataByBatch(batchNo, pageNum, pageSize);
        return ApiResponse.success(page);
    }

    @PostMapping("/report/{batchNo}")
    @Operation(summary = "上报数据", description = "将指定批次数据上报到国家平台")
    public ApiResponse<ReportResultDTO> reportData(@PathVariable String batchNo) {
        try {
            ReportResultDTO result = reportService.reportToNationalPlatform(batchNo);
            return ApiResponse.success("上报完成", result);
        } catch (Exception e) {
            logger.error("数据上报失败", e);
            return ApiResponse.error("上报失败: " + e.getMessage());
        }
    }

    @GetMapping("/report/failed/{batchNo}")
    @Operation(summary = "获取上报失败数据", description = "获取指定批次上报失败的数据")
    public ApiResponse<List<ExcelData>> getFailedReportData(@PathVariable String batchNo) {
        List<ExcelData> failedList = reportService.getFailedReportData(batchNo);
        return ApiResponse.success(failedList);
    }

    @PostMapping("/report/retry/{batchNo}")
    @Operation(summary = "重试上报", description = "重新上报失败的数据")
    public ApiResponse<ReportResultDTO> retryReport(@PathVariable String batchNo) {
        try {
            reportService.resetFailedData(batchNo);
            ReportResultDTO result = reportService.reportToNationalPlatform(batchNo);
            return ApiResponse.success("重新上报完成", result);
        } catch (Exception e) {
            logger.error("重新上报失败", e);
            return ApiResponse.error("重新上报失败: " + e.getMessage());
        }
    }

    @GetMapping("/template")
    @Operation(summary = "下载导入模板", description = "下载Excel导入模板")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = URLEncoder.encode("医保清单导入模板", StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");
        response.setHeader("Content-disposition", "attachment;filename*=utf-''" + fileName + ".xlsx");

        List<ExcelDataDTO> templateData = new ArrayList<>();
        ExcelDataDTO example = new ExcelDataDTO();
        example.setDataCode("DATA001");
        example.setName("张三");
        example.setIdCard("110101199001011234");
        example.setPhone("13800138000");
        example.setAmount("1000.00");
        example.setAddress("北京市朝阳区xxx街道");
        example.setRemark("示例数据");
        templateData.add(example);

        EasyExcel.write(response.getOutputStream(), ExcelDataDTO.class)
                .sheet("医保清单导入模板")
                .doWrite(templateData);
    }

    @GetMapping("/export/errors/{batchNo}")
    @Operation(summary = "导出上报失败数据", description = "导出上报失败的数据为Excel")
    public void exportErrors(@PathVariable String batchNo, HttpServletResponse response) throws IOException {
        List<ExcelData> failedList = reportService.getFailedReportData(batchNo);

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = URLEncoder.encode("上报失败数据_" + batchNo, StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");
        response.setHeader("Content-disposition", "attachment;filename*=utf-''" + fileName + ".xlsx");

        List<ExcelDataDTO> exportList = new ArrayList<>();
        for (ExcelData data : failedList) {
            ExcelDataDTO dto = new ExcelDataDTO();
            dto.setDataCode(data.getDataCode());
            dto.setName(data.getName());
            dto.setIdCard(data.getIdCard());
            dto.setPhone(data.getPhone());
            dto.setAmount(data.getAmount() == null ? null : data.getAmount().toPlainString());
            dto.setAddress(data.getAddress());
            dto.setRemark(data.getRemark());
            dto.setErrorMsg(data.getReportMessage());
            exportList.add(dto);
        }

        EasyExcel.write(response.getOutputStream(), ExcelDataDTO.class)
                .sheet("上报失败数据")
                .doWrite(exportList);
    }
}
