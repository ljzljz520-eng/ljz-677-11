package com.excel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.excel.entity.ImportErrorRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ImportErrorRowMapper extends BaseMapper<ImportErrorRow> {

    /**
     * 批量插入失败行，单条 SQL 减少往返
     */
    @Insert("<script>" +
            "INSERT INTO import_error_row " +
            "(batch_no, row_index, data_code, name, id_card, phone, amount, address, remark, error_msg, create_time) VALUES " +
            "<foreach collection='list' item='it' separator=','>" +
            "(#{it.batchNo}, #{it.rowIndex}, #{it.dataCode}, #{it.name}, #{it.idCard}, " +
            "#{it.phone}, #{it.amount}, #{it.address}, #{it.remark}, #{it.errorMsg}, NOW())" +
            "</foreach>" +
            "</script>")
    int batchInsert(@Param("list") List<ImportErrorRow> list);

    /**
     * 物理删除某批次失败行（任务重跑时清理）
     */
    @Delete("DELETE FROM import_error_row WHERE batch_no = #{batchNo}")
    int physicalDeleteByBatch(@Param("batchNo") String batchNo);
}
