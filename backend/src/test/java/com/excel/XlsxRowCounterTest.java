package com.excel;

import com.alibaba.excel.EasyExcel;
import com.excel.dto.ExcelDataDTO;
import com.excel.utils.XlsxRowCounter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XlsxRowCounterTest {

    @Test
    void countDataRows_excludes_header_and_matches_written_rows(@TempDir Path dir) {
        File file = dir.resolve("sample.xlsx").toFile();
        int rows = 50_000;

        try (com.alibaba.excel.ExcelWriter writer =
                     EasyExcel.write(file, ExcelDataDTO.class).build()) {
            var sheet = EasyExcel.writerSheet("清单").build();
            int chunk = 2000;
            for (int start = 0; start < rows; start += chunk) {
                List<ExcelDataDTO> buffer = new ArrayList<>(chunk);
                for (int n = start; n < Math.min(start + chunk, rows); n++) {
                    ExcelDataDTO dto = new ExcelDataDTO();
                    dto.setDataCode("D" + n);
                    dto.setName("张三");
                    dto.setIdCard("110101199001011237");
                    dto.setAmount(new BigDecimal("1.00"));
                    buffer.add(dto);
                }
                writer.write(buffer, sheet);
            }
        }

        Integer counted = new XlsxRowCounter().countDataRows(file.toPath());
        assertEquals(rows, counted, "预扫描行数应等于数据行数（不含表头）");
    }

    @Test
    void countDataRows_empty_sheet_returns_zero(@TempDir Path dir) {
        File file = dir.resolve("empty.xlsx").toFile();
        List<ExcelDataDTO> head = new ArrayList<>();
        EasyExcel.write(file, ExcelDataDTO.class).sheet("s").doWrite(head);
        Integer counted = new XlsxRowCounter().countDataRows(file.toPath());
        assertEquals(0, counted);
    }
}
