package com.excel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.excel.entity.ImportRowError;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ImportRowErrorMapper extends BaseMapper<ImportRowError> {

    /**
     * 批量插入错误行（单条 SQL，避免 5 万次 insert）
     */
    @Insert("""
            <script>
            INSERT INTO import_row_error
                (batch_no, row_index, data_code, name, id_card, phone, amount, address, remark, error_msg, create_time)
            VALUES
            <foreach collection="list" item="e" separator=",">
                (#{e.batchNo}, #{e.rowIndex}, #{e.dataCode}, #{e.name}, #{e.idCard}, #{e.phone},
                 #{e.amount}, #{e.address}, #{e.remark}, #{e.errorMsg}, NOW())
            </foreach>
            </script>
            """)
    int batchInsert(@Param("list") List<ImportRowError> list);

    @Delete("DELETE FROM import_row_error WHERE batch_no = #{batchNo}")
    int deleteByBatch(@Param("batchNo") String batchNo);
}
