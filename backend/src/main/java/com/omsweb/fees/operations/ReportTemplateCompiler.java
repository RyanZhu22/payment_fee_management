package com.omsweb.fees.operations;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.util.JRSaver;

/**
 * Build-time helper that serialises the JRXML templates so the packaged application never has
 * to run the report expression compiler against a nested-jar classpath.
 */
public final class ReportTemplateCompiler {
    private static final List<String> TEMPLATES = List.of("notice", "reminder", "receipt");

    private ReportTemplateCompiler() {
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length == 0 ? "target/classes/reports" : args[0]);
        Files.createDirectories(output);
        for (String name : TEMPLATES) {
            String resource = "/reports/" + name + ".jrxml";
            try (InputStream template = ReportTemplateCompiler.class.getResourceAsStream(resource)) {
                if (template == null) {
                    throw new IllegalStateException("Missing report template " + resource);
                }
                JasperReport report = JasperCompileManager.compileReport(template);
                JRSaver.saveObject(report, new File(output.toFile(), name + ".jasper"));
            }
            System.out.println("Compiled report template " + name);
        }
    }
}
