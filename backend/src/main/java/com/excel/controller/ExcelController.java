package com.excel.controller;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.excel.dto.ApiResponse;
import com.excel.dto.ExcelDataDTO;
import com.excel.dto.ReportResultDTO;
import com.excel.dto.TaskProgressDTO;
import com.excel.entity.ExcelData;
import com.excel.entity.ImportRecord;
import com.excel.entity.ImportRowError;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

@RestController
@RequestMapping("/api/excel")
@RequiredArgsConstructor
@Tag(name = "Excel导入管理", description = "Excel异步分批导入、进度查询与上报接口")
public class ExcelController {

    private static final Logger logger = LoggerFactory.getLogger(ExcelController.class);

    private final ExcelImportService excelImportService;
    private final ReportService reportService;
    private final ExecutorService importExecutor;

    @PostMapping("/tasks")
    @Operation(summary = "创建导入任务", description = "上传Excel，立即返回任务编号；后台流式分批解析")
    public ApiResponse<Map<String, String>> createTask(@RequestParam("file") MultipartFile file,
                                                       Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            String taskNo = excelImportService.createTask(file, userId);
            // 提交后台解析，HTTP 请求立即结束
            importExecutor.execute(() -> excelImportService.runTask(taskNo));

            Map<String, String> data = new HashMap<>();
            data.put("taskNo", taskNo);
            return ApiResponse.success("任务已创建", data);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            logger.error("创建导入任务失败", e);
            return ApiResponse.error("创建任务失败: " + e.getMessage());
        }
    }

    @GetMapping("/tasks/{taskNo}")
    @Operation(summary = "查询导入进度", description = "凭任务编号查看已读取/已校验/失败行数及预计完成时间")
    public ApiResponse<TaskProgressDTO> getTaskProgress(@PathVariable String taskNo) {
        TaskProgressDTO progress = excelImportService.getProgress(taskNo);
        if (progress == null) {
            return ApiResponse.error(404, "任务不存在: " + taskNo);
        }
        return ApiResponse.success(progress);
    }

    @GetMapping("/tasks/{taskNo}/errors")
    @Operation(summary = "分页查询失败行", description = "任务完成后分页查看校验失败的数据行")
    public ApiResponse<Page<ImportRowError>> getTaskErrors(
            @PathVariable String taskNo,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return ApiResponse.success(excelImportService.getErrors(taskNo, pageNum, pageSize));
    }

    @GetMapping("/tasks/{taskNo}/errors/export")
    @Operation(summary = "导出失败行", description = "将失败行及错误原因导出为Excel（流式写出）")
    public void exportTaskErrors(@PathVariable String taskNo, HttpServletResponse response) throws IOException {
        List<ImportRowError> errors = excelImportService.getAllErrors(taskNo);

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = URLEncoder.encode("导入失败数据_" + taskNo, StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");
        response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fileName + ".xlsx");

        List<ExcelDataDTO> exportList = new ArrayList<>(errors.size());
        for (ImportRowError e : errors) {
            ExcelDataDTO dto = new ExcelDataDTO();
            dto.setRowIndex(e.getRowIndex());
            dto.setDataCode(e.getDataCode());
            dto.setName(e.getName());
            dto.setIdCard(e.getIdCard());
            dto.setPhone(e.getPhone());
            if (e.getAmount() != null && !e.getAmount().isBlank()) {
                try {
                    dto.setAmount(new BigDecimal(e.getAmount()));
                } catch (NumberFormatException ignore) {
                    // 原始非法金额无法转数值时留空，错误原因里保留
                }
            }
            dto.setAddress(e.getAddress());
            dto.setRemark(e.getRemark());
            dto.setErrorMsg(e.getErrorMsg());
            exportList.add(dto);
        }

        EasyExcel.write(response.getOutputStream(), ExcelDataDTO.class)
                .sheet("导入失败数据")
                .doWrite(exportList);
    }

    @GetMapping("/records")
    @Operation(summary = "获取导入记录", description = "分页获取导入任务列表")
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
            // 先重置失败数据状态
            reportService.resetFailedData(batchNo);
            // 再次上报
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
        String fileName = URLEncoder.encode("数据导入模板", StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");
        response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fileName + ".xlsx");

        // 生成模板数据
        List<ExcelDataDTO> templateData = new ArrayList<>();
        ExcelDataDTO example = new ExcelDataDTO();
        example.setDataCode("DATA001");
        example.setName("张三");
        example.setIdCard("110101199001011234");
        example.setPhone("13800138000");
        example.setAmount(new BigDecimal("1000.00"));
        example.setAddress("北京市朝阳区xxx街道");
        example.setRemark("示例数据");
        templateData.add(example);

        EasyExcel.write(response.getOutputStream(), ExcelDataDTO.class)
                .sheet("数据导入模板")
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
        response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fileName + ".xlsx");

        // 转换为DTO
        List<ExcelDataDTO> exportList = new ArrayList<>();
        for (ExcelData data : failedList) {
            ExcelDataDTO dto = new ExcelDataDTO();
            dto.setDataCode(data.getDataCode());
            dto.setName(data.getName());
            dto.setIdCard(data.getIdCard());
            dto.setPhone(data.getPhone());
            dto.setAmount(data.getAmount());
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
