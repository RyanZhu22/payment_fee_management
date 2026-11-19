package com.omsweb.fees.operations;

import com.omsweb.fees.AppException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import net.sf.jasperreports.engine.JREmptyDataSource;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.util.JRLoader;
import net.sf.jasperreports.export.SimpleExporterInput;
import net.sf.jasperreports.export.SimpleOutputStreamExporterOutput;
import net.sf.jasperreports.pdf.JRPdfExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Renders notice, reminder and receipt PDFs from the JasperReports templates in
 * {@code reports/}. Serialised reports are loaded once at startup and then filled per document.
 */
@Component
class DocumentPdfRenderer {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentPdfRenderer.class);

    private final Map<DocumentKind, JasperReport> templates = new EnumMap<>(DocumentKind.class);

    DocumentPdfRenderer() {
        for (DocumentKind kind : DocumentKind.values()) {
            templates.put(kind, load(kind));
        }
    }

    byte[] render(DocumentKind kind, Map<String, Object> parameters) {
        try (var output = new ByteArrayOutputStream()) {
            JasperPrint print = JasperFillManager.fillReport(templates.get(kind), parameters, new JREmptyDataSource());
            var exporter = new JRPdfExporter();
            exporter.setExporterInput(new SimpleExporterInput(print));
            exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(output));
            exporter.exportReport();
            return output.toByteArray();
        } catch (JRException | IOException error) {
            LOG.error("Could not render a {} PDF", kind, error);
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "生成 PDF 失败，请稍后重试");
        }
    }

    /**
     * Prefers the serialised report produced at build time. Running the expression compiler from a
     * Spring Boot executable jar fails because the nested dependency jars are not on the compiler
     * classpath, so the JRXML fallback is only used when running from an exploded classpath.
     */
    private JasperReport load(DocumentKind kind) {
        String base = "/reports/" + kind.name().toLowerCase(Locale.ROOT);
        try (InputStream compiled = getClass().getResourceAsStream(base + ".jasper")) {
            if (compiled != null) {
                return (JasperReport) JRLoader.loadObject(compiled);
            }
        } catch (IOException | JRException error) {
            throw new IllegalStateException("Could not load compiled report " + base + ".jasper", error);
        }
        try (InputStream template = getClass().getResourceAsStream(base + ".jrxml")) {
            if (template == null) {
                throw new IllegalStateException("Missing report template " + base + ".jrxml");
            }
            return JasperCompileManager.compileReport(template);
        } catch (IOException | JRException error) {
            throw new IllegalStateException("Could not compile report template " + base + ".jrxml", error);
        }
    }
}
