package com.firemanage;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongFunction;
import java.util.stream.Collectors;

import jakarta.validation.Validator;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static com.firemanage.ApiSupport.bad;
import static com.firemanage.FireDeviceController.clean;

@Component
class FireDeviceExcel {
    private static final String[] INPUT = {
        "单位编号", "设备编号", "设备类别", "品名", "类型", "规格", "使用场所", "存放地点",
        "出厂日期", "最新年检日期", "保质期至"
    };
    private static final String[] REPORT = { "所属单位", "状态", "提示", "新增角色", "创建时间" };
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-M-d")
            .withResolverStyle(ResolverStyle.STRICT);
    private final Validator validator;

    FireDeviceExcel(Validator validator) {
        this.validator = validator;
    }

    List<Imported> read(MultipartFile file, Map<String, Long> unitCodes) {
        if (file.isEmpty() || file.getOriginalFilename() == null
                || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw bad("请选择有效的 .xlsx 设备台账文件");
        }
        try (var stream = file.getInputStream(); var book = new XSSFWorkbook(stream)) {
            if (book.getNumberOfSheets() == 0) throw bad("设备导入表为空");
            Sheet sheet = book.getSheetAt(0);
            if (sheet.getLastRowNum() > 10000) throw bad("每次最多导入 10000 台设备");
            var formatter = new DataFormatter(Locale.ROOT);
            Row header = sheet.getRow(0);
            for (int column = 0; column < INPUT.length; column++) {
                if (!INPUT[column].equals(text(header, column, formatter))) {
                    throw bad("设备导入表头不匹配，请使用系统模板");
                }
            }
            var result = new ArrayList<Imported>();
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null || blank(row, formatter)) continue;
                try {
                    var code = text(row, 0, formatter);
                    var unit = unitCodes.get(code == null ? "" : code.toUpperCase(Locale.ROOT));
                    if (unit == null) throw bad("单位编号不存在或无导入权限");
                    var input = new FireDeviceController.Input(unit, text(row, 1, formatter),
                            category(text(row, 2, formatter)), text(row, 3, formatter), text(row, 4, formatter),
                            text(row, 5, formatter), text(row, 6, formatter), text(row, 7, formatter),
                            date(row, 8, formatter), date(row, 9, formatter), date(row, 10, formatter), null);
                    var issues = validator.validate(input);
                    if (!issues.isEmpty()) throw bad("必填字段缺失或字段超长，请检查设备编号、品名和存放地点");
                    result.add(new Imported(index + 1, input));
                } catch (ResponseStatusException error) {
                    throw bad("第 " + (index + 1) + " 行：" + error.getReason());
                }
            }
            if (result.isEmpty()) throw bad("文件中没有可导入的设备");
            return result;
        } catch (ResponseStatusException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw bad("无法读取 .xlsx 文件，请检查格式");
        }
    }

    void writeTemplate(OutputStream output) throws IOException {
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("设备台账");
            headings(book, sheet, false);
            book.write(output);
        }
    }

    void writeExport(OutputStream output, LongFunction<List<FireDeviceController.Device>> loadBatch,
                     LocalDate today) throws IOException {
        try (var book = new SXSSFWorkbook(100)) {
            book.setCompressTempFiles(true);
            var sheet = book.createSheet("设备台账");
            headings(book, sheet, true);
            long cursor = 0L;
            int rowIndex = 1;
            List<FireDeviceController.Device> devices;
            while (!(devices = loadBatch.apply(cursor)).isEmpty()) {
                for (var device : devices) {
                    var reminders = FireReminder.forDevice(device.state(), device.expiresOn(), device.inspectedOn(), today)
                            .stream().map(notice -> notice.label).collect(Collectors.joining("；"));
                    var values = new String[]{
                            device.unitCode(), device.code(), device.category().label, device.itemName(), device.modelType(),
                            device.specification(), device.useSite(), device.storageLocation(),
                            Objects.toString(device.manufacturedOn(), ""), Objects.toString(device.inspectedOn(), ""),
                            Objects.toString(device.expiresOn(), ""), device.unitName(),
                            switch (device.state()) { case 0 -> "已报废"; case 1 -> "在用"; default -> "库存"; },
                            reminders, device.createdRole(), Objects.toString(device.createdAt(), "")
                    };
                    Row row = sheet.createRow(rowIndex++);
                    for (int column = 0; column < values.length; column++) {
                        row.createCell(column, CellType.STRING).setCellValue(values[column] == null ? "" : values[column]);
                    }
                    cursor = device.id();
                }
            }
            book.write(output);
        }
    }

    private static void headings(Workbook book, Sheet sheet, boolean report) {
        var bold = book.createFont();
        bold.setBold(true);
        var headerStyle = book.createCellStyle();
        headerStyle.setFont(bold);
        var textStyle = book.createCellStyle();
        textStyle.setDataFormat(book.createDataFormat().getFormat("@"));
        Row row = sheet.createRow(0);
        int count = report ? INPUT.length + REPORT.length : INPUT.length;
        for (int i = 0; i < count; i++) {
            Cell cell = row.createCell(i);
            cell.setCellStyle(headerStyle);
            cell.setCellValue(i < INPUT.length ? INPUT[i] : REPORT[i - INPUT.length]);
            sheet.setColumnWidth(i, (i == 6 || i == 7 ? 28 : 19) * 256);
            sheet.setDefaultColumnStyle(i, textStyle);
        }
        sheet.createFreezePane(0, 1);
    }

    private static boolean blank(Row row, DataFormatter formatter) {
        for (int i = 0; i < INPUT.length; i++) {
            Cell cell = row.getCell(i);
            if (cell != null && !formatter.formatCellValue(cell).isBlank()) return false;
        }
        return true;
    }

    private static String text(Row row, int column, DataFormatter formatter) {
        Cell cell = row == null ? null : row.getCell(column);
        if (cell == null) return null;
        if (cell.getCellType() == CellType.FORMULA) throw bad("导入字段请填写实际值");
        return clean(formatter.formatCellValue(cell));
    }

    private static LocalDate date(Row row, int column, DataFormatter formatter) {
        Cell cell = row.getCell(column);
        if (cell != null && cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate();
        }
        String value = text(row, column, formatter);
        if (value == null) return null;
        try {
            return LocalDate.parse(value.replace('/', '-'), DATE);
        } catch (java.time.DateTimeException error) {
            throw bad(INPUT[column] + "请填写 yyyy-MM-dd 格式的日期");
        }
    }

    private static FireDeviceController.Category category(String label) {
        for (var category : FireDeviceController.Category.values()) {
            if (category.label.equals(label) || category.name().equals(label)) return category;
        }
        throw bad("设备类别不在设备字典中");
    }

    record Imported(int number, FireDeviceController.Input input) {}

}
