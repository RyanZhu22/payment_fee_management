package com.omsweb.fees.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class QrCodeRendererTest {
    private final QrCodeRenderer renderer = new QrCodeRenderer();

    @Test
    void rendersADecodablePngForTheStoredPayload() throws Exception {
        String content = "https://fps.example.test/pay?ref=FPS-2026-000001&fee=FEE-0007&amount=7800.00"
                + "&currency=HKD&expires=2026-12-17";

        byte[] png = renderer.png(content);

        assertThat(png).isNotEmpty();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(360);
        assertThat(image.getHeight()).isEqualTo(360);

        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        var bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, height, pixels)));
        Result result = new MultiFormatReader().decode(bitmap);

        assertThat(result.getText()).isEqualTo(content);
    }
}
