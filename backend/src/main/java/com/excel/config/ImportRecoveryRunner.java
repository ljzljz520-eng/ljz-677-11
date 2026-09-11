package com.excel.config;

import com.excel.service.ExcelImportService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 服务启动后恢复中断的导入任务：
 * 临时文件仍在的重新入队解析，文件已丢失的标记为失败，页面凭任务编号可看到最终态。
 */
@Component
@RequiredArgsConstructor
public class ImportRecoveryRunner {

    private static final Logger logger = LoggerFactory.getLogger(ImportRecoveryRunner.class);

    private final ExcelImportService excelImportService;

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        int recovered = excelImportService.recoverInterruptedTasks();
        if (recovered > 0) {
            logger.info("启动恢复：{} 个未完成的导入任务已重新提交", recovered);
        }
    }
}
