package com.excel.utils;

import cn.hutool.core.util.IdcardUtil;
import cn.hutool.core.util.PhoneUtil;
import cn.hutool.core.util.StrUtil;
import com.excel.dto.ExcelDataDTO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class ValidationUtils {

    public static String validate(ExcelDataDTO dto) {
        List<String> errors = new ArrayList<>();

        if (StrUtil.isBlank(dto.getDataCode())) {
            errors.add("数据编号不能为空");
        }

        if (StrUtil.isBlank(dto.getName())) {
            errors.add("姓名不能为空");
        } else if (dto.getName().trim().length() > 50) {
            errors.add("姓名长度不能超过50个字符");
        }

        if (StrUtil.isNotBlank(dto.getIdCard())) {
            if (!IdcardUtil.isValidCard(dto.getIdCard().trim())) {
                errors.add("身份证号格式不正确");
            }
        }

        if (StrUtil.isNotBlank(dto.getPhone())) {
            if (!PhoneUtil.isMobile(dto.getPhone().trim())) {
                errors.add("手机号格式不正确");
            }
        }

        if (StrUtil.isNotBlank(dto.getAmount())) {
            BigDecimal amount;
            try {
                amount = dto.getAmountAsDecimal();
            } catch (NumberFormatException e) {
                amount = null;
                errors.add("金额格式不正确: " + dto.getAmount());
            }
            if (amount != null && amount.signum() < 0) {
                errors.add("金额不能为负数");
            }
        }

        if (StrUtil.isNotBlank(dto.getAddress()) && dto.getAddress().length() > 200) {
            errors.add("地址长度不能超过200个字符");
        }

        return errors.isEmpty() ? null : String.join("; ", errors);
    }
}
