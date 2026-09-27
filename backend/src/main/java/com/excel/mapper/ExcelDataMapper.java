package com.excel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.excel.entity.ExcelData;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExcelDataMapper extends BaseMapper<ExcelData> {

    @Select("SELECT * FROM excel_data WHERE batch_no = #{batchNo} AND report_status = #{status} AND deleted = 0")
    List<ExcelData> selectByBatchAndStatus(@Param("batchNo") String batchNo, @Param("status") Integer status);

    @Select("SELECT COUNT(*) FROM excel_data WHERE batch_no = #{batchNo} AND deleted = 0")
    Integer countByBatch(@Param("batchNo") String batchNo);

    /**
     * 批量插入有效数据（重写JDBC批处理，一条多值SQL，兼顾性能与可移植性）
     */
    @org.apache.ibatis.annotations.Insert("<script>" +
            "INSERT INTO excel_data " +
            "(data_code, name, id_card, phone, amount, address, remark, batch_no, report_status, create_time, update_time) VALUES " +
            "<foreach collection='list' item='it' separator=','>" +
            "(#{it.dataCode}, #{it.name}, #{it.idCard}, #{it.phone}, #{it.amount}, #{it.address}, " +
            "#{it.remark}, #{it.batchNo}, 0, NOW(), NOW())" +
            "</foreach>" +
            "</script>")
    int batchInsert(@Param("list") List<ExcelData> list);

    /**
     * 物理删除某批次已写入的数据（任务中断恢复时清理半成品）
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM excel_data WHERE batch_no = #{batchNo}")
    int physicalDeleteByBatch(@Param("batchNo") String batchNo);
}
