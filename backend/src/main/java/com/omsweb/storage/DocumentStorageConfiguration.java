package com.omsweb.storage;

import com.omsweb.fees.AppException;
import com.omsweb.fees.operations.DocumentStorage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.util.Assert;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration(proxyBeanMethods = false)
class DocumentStorageConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentStorageConfiguration.class);

    @Bean
    @ConditionalOnProperty(name = "oms.storage.type", havingValue = "local", matchIfMissing = true)
    DocumentStorage localDocumentStorage(@Value("${oms.storage.directory}") Path directory) throws IOException {
        return new LocalStorage(Files.createDirectories(directory.toAbsolutePath()));
    }

    @Bean
    @ConditionalOnProperty(name = "oms.storage.type", havingValue = "s3")
    S3Client documentS3Client(
            @Value("${oms.storage.s3.region}") String region,
            @Value("${oms.storage.s3.endpoint}") String endpoint
    ) {
        var builder = S3Client.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder());
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(name = "oms.storage.type", havingValue = "s3")
    DocumentStorage s3DocumentStorage(S3Client client, @Value("${oms.storage.s3.bucket}") String bucket) {
        Assert.hasText(bucket, "Set OMS_S3_BUCKET when OMS_STORAGE_TYPE is s3");
        return new S3Storage(client, bucket);
    }

    private record LocalStorage(Path directory) implements DocumentStorage {

        @Override
        public void write(String key, byte[] bytes) {
            try {
                Path target = directory.resolve(key).normalize();
                Files.createDirectories(target.getParent());
                Files.write(target, bytes);
            } catch (IOException error) {
                LOG.error("Could not write a generated document", error);
                throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "暂时无法保存文档");
            }
        }

        @Override
        public byte[] read(String key) {
            try {
                return Files.readAllBytes(directory.resolve(key).normalize());
            } catch (IOException error) {
                LOG.error("Could not read a generated document", error);
                throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "暂时无法读取文档");
            }
        }
    }

    private record S3Storage(S3Client client, String bucket) implements DocumentStorage {

        @Override
        public void write(String key, byte[] bytes) {
            try {
                client.putObject(
                        request -> request.bucket(bucket).key(key).contentType("application/pdf"),
                        RequestBody.fromBytes(bytes)
                );
            } catch (SdkException error) {
                LOG.error("Could not write a generated document to object storage", error);
                throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "暂时无法保存文档");
            }
        }

        @Override
        public byte[] read(String key) {
            try {
                return client.getObjectAsBytes(request -> request.bucket(bucket).key(key)).asByteArray();
            } catch (SdkException error) {
                LOG.error("Could not read a generated document from object storage", error);
                throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "暂时无法读取文档");
            }
        }
    }
}
