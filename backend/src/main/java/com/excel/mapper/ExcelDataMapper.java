package com.excel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.excel.entity.ExcelData;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExcelDataMapper extends BaseMapper<ExcelData> {

    /**
     * 批量插入（单条多值 SQL，每批 1000 行，避免逐行 insert 的网络往返）
     */
    @Insert("""
            <script>
            INSERT INTO excel_data
                (data_code, name, id_card, phone, amount, address, remark, batch_no,
                 report_status, create_time, update_time)
            VALUES
            <foreach collection="list" item="d" separator=",">
                (#{d.dataCode}, #{d.name}, #{d.idCard}, #{d.phone}, #{d.amount}, #{d.address},
                 #{d.remark}, #{d.batchNo}, 0, NOW(), NOW())
            </foreach>
            </script>
            """)
    int batchInsert(@Param("list") List<ExcelData> list);

    @Select("SELECT * FROM excel_data WHERE batch_no = #{batchNo} AND report_status = #{status} AND deleted = 0")
    List<ExcelData> selectByBatchAndStatus(@Param("batchNo") String batchNo, @Param("status") Integer status);

    @Select("SELECT COUNT(*) FROM excel_data WHERE batch_no = #{batchNo} AND deleted = 0")
    Integer countByBatch(@Param("batchNo") String batchNo);
}
