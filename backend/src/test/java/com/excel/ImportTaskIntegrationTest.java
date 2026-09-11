package com.excel;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.excel.dto.ExcelDataDTO;
import com.excel.dto.TaskProgressDTO;
import com.excel.entity.ExcelData;
import com.excel.entity.ImportRowError;
import com.excel.mapper.ExcelDataMapper;
import com.excel.mapper.ImportRowErrorMapper;
import com.excel.service.ExcelImportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.StopWatch;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 5 万行流式导入端到端验证：
 * - 分批解析、分批入库，结果计数正确
 * - 进度持续落库（已读取/已校验/失败行数、百分比、ETA）
 * - 错误行落库可分页查询
 * - 临时文件任务结束后清理
 */
@SpringBootTest
class ImportTaskIntegrationTest {

    @Autowired
    private ExcelImportService excelImportService;
    @Autowired
    private ExcelDataMapper excelDataMapper;
    @Autowired
    private ImportRowErrorMapper importRowErrorMapper;

    private static final int TOTAL = 50_000;
    /** 每 100 行造 1 条空姓名的坏数据 */
    private static final int BAD = TOTAL / 100;

    @Test
    void import_50k_rows_streaming_with_progress_and_errors() throws Exception {
        File xlsx = generateXlsx(TOTAL);
        try {
            MockMultipartFile multipart = new MockMultipartFile(
                    "file", "med-insurance-50k.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    Files.readAllBytes(xlsx.toPath()));

            // 1) 创建任务立即返回
            String taskNo = excelImportService.createTask(multipart, null);
            assertNotNull(taskNo);
            TaskProgressDTO initial = excelImportService.getProgress(taskNo);
            assertEquals(0, initial.getStatus());
            assertEquals(0, initial.getReadCount());

            // 2) 同步执行（测试环境直接跑，不经过线程池）
            StopWatch sw = new StopWatch();
            sw.start();
            excelImportService.runTask(taskNo);
            sw.stop();
            System.out.printf("5万行解析+校验+入库耗时: %.2fs%n", sw.getTotalTimeSeconds());

            // 3) 最终进度
            TaskProgressDTO done = excelImportService.getProgress(taskNo);
            assertEquals(2, done.getStatus(), "有失败行时状态应为2（完成但有失败）");
            assertEquals("DONE", done.getStage());
            assertEquals(100, done.getPercent());
            assertEquals(TOTAL, done.getReadCount(), "已读取行数应为5万");
            assertEquals(TOTAL, done.getValidatedCount(), "已校验行数应为5万");
            assertEquals(TOTAL - BAD, done.getSuccessCount());
            assertEquals(BAD, done.getFailCount());
            assertEquals(TOTAL, done.getTotalCount());
            assertEquals(0L, done.getEtaSeconds());
            assertNotNull(done.getRowsPerSecond(), "应记录平均处理速率");
            assertTrue(done.getRowsPerSecond() > 0, "速率应为正数");

            // 4) 成功数据确实分批落库
            Long dbCount = excelDataMapper.selectCount(
                    new LambdaQueryWrapper<ExcelData>().eq(ExcelData::getBatchNo, taskNo));
            assertEquals(TOTAL - BAD, dbCount);

            // 5) 错误行落库、可分页
            long errCount = importRowErrorMapper.selectCount(
                    new LambdaQueryWrapper<ImportRowError>().eq(ImportRowError::getBatchNo, taskNo));
            assertEquals(BAD, errCount);
            var errPage = excelImportService.getErrors(taskNo, 1, 20);
            assertEquals(BAD, errPage.getTotal());
            assertEquals(20, errPage.getRecords().size());
            assertNotNull(errPage.getRecords().get(0).getErrorMsg());
            assertNotNull(errPage.getRecords().get(0).getRowIndex());

            // 6) 临时文件被清理
            TaskProgressDTO again = excelImportService.getProgress(taskNo);
            assertNotNull(again);
            File temp = new File("./target/import-temp-test");
            File[] leftovers = temp.listFiles((d, name) -> name.startsWith(taskNo));
            assertTrue(leftovers == null || leftovers.length == 0, "临时文件应已清理");
        } finally {
            //noinspection ResultOfMethodCallIgnored
            xlsx.delete();
        }
    }

    /**
     * 生成 5 万行 xlsx，其中每 100 行有 1 行姓名为空（校验必失败）。
     * 用 ExcelDataDTO 作为表头模型，列名与生产解析完全一致；分块写出避免生成时占大内存。
     */
    private File generateXlsx(int rows) throws Exception {
        File file = new File("./target/test-50k.xlsx");
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();

        try (com.alibaba.excel.ExcelWriter writer =
                     EasyExcel.write(file, ExcelDataDTO.class).build()) {
            com.alibaba.excel.write.metadata.WriteSheet sheet = EasyExcel.writerSheet("清单").build();
            int chunk = 1000;
            for (int start = 0; start < rows; start += chunk) {
                List<ExcelDataDTO> buffer = new ArrayList<>(chunk);
                int end = Math.min(start + chunk, rows);
                for (int n = start; n < end; n++) {
                    boolean bad = n % 100 == 99;
                    ExcelDataDTO dto = new ExcelDataDTO();
                    dto.setDataCode("DATA" + String.format("%06d", n));
                    dto.setName(bad ? null : "张三" + n);
                    dto.setIdCard("110101199001011237");
                    dto.setPhone("138" + String.format("%08d", n % 100_000_000));
                    dto.setAmount(new BigDecimal("100.50"));
                    dto.setAddress("测试地址" + n);
                    dto.setRemark("备注");
                    buffer.add(dto);
                }
                writer.write(buffer, sheet);
            }
        }
        return file;
    }

    @Test
    void recover_interrupted_running_task_with_file_present() throws Exception {
        File xlsx = generateXlsx(1_000);
        try {
            MockMultipartFile multipart = new MockMultipartFile(
                    "file", "recover.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    Files.readAllBytes(xlsx.toPath()));
            String taskNo = excelImportService.createTask(multipart, null);

            // 任务停留在 PENDING/处理中（模拟进程被杀），临时文件仍在磁盘
            TaskProgressDTO before = excelImportService.getProgress(taskNo);
            assertEquals(0, before.getStatus());

            // 启动恢复：文件还在 -> 重新提交线程池并执行完成
            int recovered = excelImportService.recoverInterruptedTasks();
            assertTrue(recovered >= 1);

            // 等待异步线程完成（最多10秒）
            long deadline = System.currentTimeMillis() + 10_000;
            TaskProgressDTO p;
            do {
                Thread.sleep(200);
                p = excelImportService.getProgress(taskNo);
            } while (p.getStatus() == 0 && System.currentTimeMillis() < deadline);

            assertNotEquals(0, p.getStatus(), "恢复后任务应执行到终态");
            assertEquals(1_000, p.getReadCount());
            assertEquals("DONE", p.getStage());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            xlsx.delete();
        }
    }
}
