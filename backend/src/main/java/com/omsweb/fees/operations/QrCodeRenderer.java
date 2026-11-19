package com.omsweb.fees.operations;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.omsweb.fees.AppException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Renders the demo FPS payment payload as a PNG QR code. In the original system the QR content is
 * returned by the external FPS interface; this component only draws whatever payload is stored.
 */
@Component
class QrCodeRenderer {
    private static final Logger LOG = LoggerFactory.getLogger(QrCodeRenderer.class);
    private static final int DEFAULT_SIZE = 360;
    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFFFFFFF;

    byte[] png(String content) {
        if (content == null || content.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "FPS 请求缺少二维码内容");
        }
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 2);

            BitMatrix matrix = new QRCodeWriter()
                    .encode(content, BarcodeFormat.QR_CODE, DEFAULT_SIZE, DEFAULT_SIZE, hints);
            BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    image.setRGB(x, y, matrix.get(x, y) ? BLACK : WHITE);
                }
            }
            var output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "PNG", output)) {
                throw new IOException("No PNG image writer available");
            }
            return output.toByteArray();
        } catch (WriterException | IOException error) {
            LOG.error("Could not render an FPS QR code", error);
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "生成二维码失败，请稍后重试");
        }
    }
}
