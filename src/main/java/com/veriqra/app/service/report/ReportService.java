package com.veriqra.app.service.report;

import com.veriqra.app.config.AppProperties;
import com.veriqra.app.domain.dto.AttendanceDtos.LowAttendanceView;
import com.veriqra.app.domain.dto.AttendanceDtos.SubjectAttendanceView;
import com.veriqra.app.repository.AttendanceRepository;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Attendance reporting and the export formats.
 */
@Service
public class ReportService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter
            .ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.systemDefault());

    private final AttendanceRepository attendance;
    private final AppProperties properties;

    public ReportService(AttendanceRepository attendance, AppProperties properties) {
        this.attendance = attendance;
        this.properties = properties;
    }

    /** Per subject totals for a student. */
    public List<SubjectAttendanceView> subjectAttendanceForStudent(long studentId) {
        return attendance.subjectAttendanceForStudent(studentId, 100).stream()
                .map(view -> new SubjectAttendanceView(view.subjectCode(), view.subjectName(),
                        view.sessions(), view.attended(), view.percentage()))
                .toList();
    }

    /** Per subject totals for a teacher. */
    public List<SubjectAttendanceView> subjectAttendanceForFaculty(long facultyId) {
        return attendance.subjectAttendanceForFaculty(facultyId, 100).stream()
                .map(view -> new SubjectAttendanceView(view.subjectCode(), view.subjectName(),
                        view.sessions(), view.attended(), view.percentage()))
                .toList();
    }

    /** Students below the institutional threshold. */
    public List<LowAttendanceView> lowAttendanceForFaculty(long facultyId) {
        return attendance.lowAttendanceForFaculty(facultyId, properties.getLowAttendanceThreshold()).stream()
                .map(view -> new LowAttendanceView(view.studentId(), view.studentName(), view.studentNumber(),
                        view.subjectCode(), view.subjectName(), view.sessions(), view.attended(), view.percentage()))
                .toList();
    }

    /** The attendance history of a student, most recent first. */
    public List<Map<String, Object>> historyForStudent(long studentId) {
        return attendance.historyForStudent(studentId, 500).stream()
                .map(record -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("recordedAt", record.recordedAt());
                    row.put("status", record.status().name());
                    row.put("method", record.method().name());
                    row.put("matchScore", record.matchScore());
                    row.put("sessionId", record.sessionId());
                    return row;
                })
                .toList();
    }

    /** The most recent records of a student. */
    public List<Map<String, Object>> recentHistory(long studentId, int limit) {
        return attendance.historyForStudent(studentId, limit).stream()
                .map(record -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("recordedAt", record.recordedAt());
                    row.put("status", record.status().name());
                    row.put("method", record.method().name());
                    row.put("matchScore", record.matchScore());
                    return row;
                })
                .toList();
    }

    /** The attendance history of every student of a teacher, most recent first. */
    public List<Map<String, Object>> historyForFaculty(long facultyId) {
        return attendance.historyForFaculty(facultyId, 1000).stream()
                .map(record -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("recordedAt", record.recordedAt());
                    row.put("status", record.status().name());
                    row.put("method", record.method().name());
                    row.put("matchScore", record.matchScore());
                    row.put("studentId", record.studentId());
                    row.put("sessionId", record.sessionId());
                    return row;
                })
                .toList();
    }

    /** Renders rows as CSV with the given headings. */
    public byte[] toCsv(List<String> headings, List<List<String>> rows) throws IOException {
        StringWriter buffer = new StringWriter();
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader(headings.toArray(new String[0]))
                .setSkipHeaderRecord(true)
                .build();
        try (CSVPrinter printer = new CSVPrinter(buffer, format)) {
            for (List<String> row : rows) {
                printer.printRecord(row);
            }
        }
        return buffer.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Renders rows as a paginated PDF table. */
    public byte[] toPdf(String title, List<String> headings, List<List<String>> rows) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            float margin = 40f;
            float fontSize = 9f;
            float leading = 13f;
            float rowHeight = leading + 4f;
            float pageWidth = PDRectangle.A4.getWidth();
            float pageHeight = PDRectangle.A4.getHeight();
            float usableWidth = pageWidth - 2 * margin;

            int rowsPerPage = (int) ((pageHeight - margin * 2 - 60f) / rowHeight);

            int index = 0;
            int pageNumber = 0;
            while (index < rows.size() || pageNumber == 0) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                pageNumber++;
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    float y = pageHeight - margin;
                    if (pageNumber == 1) {
                        stream.beginText();
                        stream.setFont(bold, 13f);
                        stream.newLineAtOffset(margin, y);
                        stream.showText(sanitize(title));
                        stream.endText();
                        y -= 22f;
                        stream.beginText();
                        stream.setFont(regular, 8.5f);
                        stream.newLineAtOffset(margin, y);
                        stream.showText(sanitize("Generated " + STAMP.format(java.time.Instant.now())
                                + "  |  VeriQra"));
                        stream.endText();
                        y -= 18f;
                    }

                    stream.beginText();
                    stream.setFont(bold, fontSize);
                    stream.newLineAtOffset(margin, y);
                    float column = margin;
                    float columnWidth = usableWidth / Math.max(1, headings.size());
                    for (String heading : headings) {
                        stream.showText(sanitize(heading));
                        column += columnWidth;
                    }
                    stream.endText();
                    y -= rowHeight;

                    int rowsOnPage = 0;
                    while (index < rows.size() && rowsOnPage < rowsPerPage) {
                        stream.beginText();
                        stream.setFont(regular, fontSize);
                        stream.newLineAtOffset(margin, y);
                        column = margin;
                        for (String cell : rows.get(index)) {
                            stream.showText(sanitize(truncate(cell, (int) (columnWidth / 4.6))));
                            column += columnWidth;
                        }
                        stream.endText();
                        y -= rowHeight;
                        index++;
                        rowsOnPage++;
                    }

                    stream.beginText();
                    stream.setFont(regular, 8f);
                    stream.newLineAtOffset(margin, margin);
                    stream.showText("Page " + pageNumber);
                    stream.endText();
                }
                if (index >= rows.size()) {
                    break;
                }
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            document.save(buffer);
            return buffer.toByteArray();
        }
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, Math.max(0, limit - 1)) + "…";
    }

    /** Removes characters the standard PDF fonts cannot encode. */
    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[^\\x20-\\x7E]", "?");
    }
}
