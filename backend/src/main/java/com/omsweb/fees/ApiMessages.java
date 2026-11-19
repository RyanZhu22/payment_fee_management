package com.omsweb.fees;

import java.util.Locale;
import java.util.Map;
import org.springframework.context.i18n.LocaleContextHolder;

import static java.util.Map.entry;

public final class ApiMessages {
    // Validation and business exceptions carry their original Chinese message.
    private static final Map<String, String> ENGLISH = Map.ofEntries(
        entry("请检查输入内容", "Check the information you entered."),
        entry("请求格式有误，请核对文件、日期和选项", "Check the file, dates, and selected options."),
        entry("文件超过上传大小上限", "The file exceeds the upload size limit."),
        entry("请选择费用类型", "Select a fee type."),
        entry("申请编号最多 64 个字符", "The application number must be at most 64 characters."),
        entry("请输入计划名称", "Enter the scheme name."),
        entry("计划名称最多 120 个字符", "The scheme name must be at most 120 characters."),
        entry("请输入付款人", "Enter the payer name."),
        entry("付款人最多 120 个字符", "The payer name must be at most 120 characters."),
        entry("请输入有效的邮箱地址", "Enter a valid email address."),
        entry("邮箱地址最多 254 个字符", "The email address must be at most 254 characters."),
        entry("请输入费用说明", "Enter a fee description."),
        entry("费用说明最多 240 个字符", "The fee description must be at most 240 characters."),
        entry("请输入费用金额", "Enter the fee amount."),
        entry("费用金额必须大于 0", "The fee amount must be greater than zero."),
        entry("金额最多保留两位小数", "Enter an amount with up to 12 digits before the decimal point and two after."),
        entry("请选择到期日", "Select a due date."),
        entry("原费用编号无效", "The original fee ID is invalid."),
        entry("未找到费用记录", "Fee not found."),
        entry("这笔原费用已有附加费", "This fee already has a surcharge."),
        entry("费用已结算，请核对文件中的重复记录", "This fee is already settled. Check the file for duplicate entries."),
        entry("定期计费演示规则已停用", "The demo rule for periodic fees is disabled."),
        entry("申请费需要申请编号", "Application fees require an application number."),
        entry("只有附加费可关联原费用", "Only a surcharge can reference an original fee."),
        entry("请选择有效的原费用", "Select a valid original fee."),
        entry("演示规则只允许对逾期未缴的定期费用加收附加费", "The demo allows surcharges only on overdue, unpaid periodic fees."),
        entry("请选择有效的报表类型", "Select a valid report type."),
        entry("起始日期必须早于或等于结束日期", "The start date must be on or before the end date."),
        entry("请选择有效的费用", "Select a valid fee."),
        entry("请选择文档类型", "Select a document type."),
        entry("该费用已出具收据，请核对文档列表", "A receipt already exists for this fee. Check the document list."),
        entry("未找到文档记录", "Document not found."),
        entry("只有未结算的费用可以生成缴费通知", "Payment notices are available only for unsettled fees."),
        entry("只有未结算的费用可以生成催缴提醒", "Payment reminders are available only for unsettled fees."),
        entry("费用尚未逾期，暂不需要发送催缴提醒", "Generate a reminder once the fee becomes overdue."),
        entry("只有已结算的费用可以生成收据", "Receipts are available only for settled fees."),
        entry("生成 PDF 失败，请稍后重试", "The PDF could not be generated. Try again later."),
        entry("暂时无法保存文档", "The document could not be saved. Try again later."),
        entry("暂时无法读取文档", "The document could not be read. Try again later."),
        entry("未找到 FPS 请求", "FPS request not found."),
        entry("只有未结算的费用可以生成 FPS 二维码请求", "FPS QR requests are available only for unsettled fees."),
        entry("无法保存 FPS 请求数据", "The FPS request data could not be saved."),
        entry("FPS 请求缺少二维码内容", "This FPS request has no QR code data."),
        entry("生成二维码失败，请稍后重试", "The QR code could not be generated. Try again later."),
        entry("读取结算工作簿失败。", "The settlement workbook could not be read."),
        entry("工作簿没有可导入的结算记录。", "The workbook has no settlement records to import."),
        entry("无法生成结算演示模板。", "The settlement demo template could not be generated."),
        entry("无法打开工作簿。请上传未加密的 .xls 或 .xlsx。", "The workbook could not be opened. Upload an unencrypted .xls or .xlsx file."),
        entry("结算表表头不符，请下载对应类型的演示模板。", "The settlement headings do not match. Download the demo template for this workbook type."),
        entry("请将公式替换为静态值。", "Replace formulas with static values."),
        entry("数据列超出结算表头定义。", "This row contains values beyond the settlement headings."),
        entry("缺少费用 ID 或申请编号。", "The fee ID or application number is missing."),
        entry("未找到对应的申请费。", "No matching application fee was found."),
        entry("同一申请编号对应多笔申请费，请按费用 ID 导入。", "This application number has several application fees. Import using the fee ID."),
        entry("结算已登记。", "Settlement recorded."),
        entry("费用 ID 须为正整数。", "The fee ID must be a positive integer."),
        entry("对账记录缺少参考编号或列数有误。", "The reconciliation reference is missing or the row has the wrong number of columns."),
        entry("未找到对应费用。", "No matching fee was found."),
        entry("对账金额与费用金额不同。", "The reconciliation amount differs from the fee amount."),
        entry("费用 ID、金额或银行入账日格式无效。", "The fee ID, amount, or bank-in date is invalid."),
        entry("已转换，供复核。", "Converted for review."),
        entry("CSV 表头不符，请下载 FPS 对账演示模板。", "The CSV headings do not match. Download the FPS reconciliation demo template."),
        entry("CSV 没有可转换的对账记录。", "The CSV has no reconciliation records to convert."),
        entry("CSV 无法读取，请使用 UTF-8 编码和演示模板格式。", "The CSV could not be read. Use UTF-8 encoding and the demo template format.")
    );

    private ApiMessages() {
    }

    public static boolean isEnglish() {
        var context = LocaleContextHolder.getLocaleContext();
        Locale locale = context == null ? null : context.getLocale();
        return locale != null && Locale.ENGLISH.getLanguage().equals(locale.getLanguage());
    }

    public static String text(String chinese, String english) {
        return isEnglish() ? english : chinese;
    }

    public static String translate(String message) {
        return isEnglish() ? ENGLISH.getOrDefault(message, message) : message;
    }
}
