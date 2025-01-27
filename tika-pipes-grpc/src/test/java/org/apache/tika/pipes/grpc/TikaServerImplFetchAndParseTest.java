package org.apache.tika.pipes.grpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.*;
import org.apache.tika.pipes.TikaPipesIntegrationTestBase;
import org.apache.tika.pipes.fetchers.filesystem.FileSystemFetcherConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Slf4j
class TikaServerImplFetchAndParseTest extends TikaPipesIntegrationTestBase {
    ObjectMapper objectMapper = new ObjectMapper();
    String pluginId = "filesystem-fetcher";
    String fetcherId = "filesystem-fetcher-example1";
    File testFilesDir = new File("corpa-files");
    String testFilesDirPath;
    @BeforeEach
    void init() throws Exception {
        testFilesDirPath = testFilesDir.getCanonicalPath();
        log.info("Using test files from {}", testFilesDirPath);
    }

    @Test
    void fetchersCrud() throws Exception {
        ManagedChannel channel =
                ManagedChannelBuilder.forAddress(InetAddress.getLocalHost().getHostAddress(),
                                port) // Ensure the port is correct
                        .usePlaintext().build();
        TikaGrpc.TikaBlockingStub tikaBlockingStub = TikaGrpc.newBlockingStub(channel);
        TikaGrpc.TikaStub tikaStub = TikaGrpc.newStub(channel);

        saveFetcher(tikaBlockingStub, fetcherId, pluginId);

        List<FetchAndParseReply> successes = Collections.synchronizedList(new ArrayList<>());
        List<FetchAndParseReply> errors = Collections.synchronizedList(new ArrayList<>());

        CountDownLatch countDownLatch = new CountDownLatch(1);
        StreamObserver<FetchAndParseRequest>
                requestStreamObserver = tikaStub.fetchAndParseBiDirectionalStreaming(new StreamObserver<>() {
            @Override
            public void onNext(FetchAndParseReply fetchAndParseReply) {
                log.debug("Reply from fetch-and-parse - key={}, metadata={}", fetchAndParseReply.getFetchKey(), fetchAndParseReply.getMetadataList());
                if ("FetchException"
                        .equals(fetchAndParseReply.getStatus())) {
                    errors.add(fetchAndParseReply);
                } else {
                    successes.add(fetchAndParseReply);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                log.error("Received an error", throwable);
                countDownLatch.countDown();
            }

            @Override
            public void onCompleted() {
                log.info("Finished streaming fetch and parse replies");
                countDownLatch.countDown();
            }
        });

        Files.walkFileTree(testFilesDir.toPath(), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    requestStreamObserver.onNext(FetchAndParseRequest
                            .newBuilder()
                            .setFetcherId(fetcherId)
                            .setFetchKey(file
                                    .toAbsolutePath()
                                    .toString())
                            .setMetadataJson(objectMapper.writeValueAsString(Map.of()))
                            .build());
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                log.info("Directory: {}", dir.toString());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                log.error("Failed to access file: {}", file.toString(), exc);
                return FileVisitResult.CONTINUE;
            }
        });

        log.info("Done submitting fetch keys to {}", fetcherId);
        requestStreamObserver.onCompleted();

        try {
            if (!countDownLatch.await(3, TimeUnit.MINUTES)) {
                log.error("Timed out waiting for parse to complete");
            }
        } catch (InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
        }

        log.info("Fetched: success={}", successes);
    }

    private void saveFetcher(TikaGrpc.TikaBlockingStub tikaBlockingStub, String fetcherId,
                                       String pluginId) throws JsonProcessingException {
        FileSystemFetcherConfig fileSystemFetcherConfig = new FileSystemFetcherConfig();
        fileSystemFetcherConfig.setExtractFileSystemMetadata(true);
        fileSystemFetcherConfig.setBasePath(testFilesDir.getAbsolutePath());

        SaveFetcherReply saveFetcherReply = tikaBlockingStub.saveFetcher(
                SaveFetcherRequest.newBuilder().setFetcherId(fetcherId).setPluginId(pluginId)
                        .setFetcherConfigJson(
                                objectMapper.writeValueAsString(fileSystemFetcherConfig)).build());
        assertEquals(fetcherId, saveFetcherReply.getFetcherId());
        GetFetcherReply getFetcherReply = tikaBlockingStub.getFetcher(
                GetFetcherRequest.newBuilder().setFetcherId(fetcherId).build());
        assertEquals(fetcherId, getFetcherReply.getFetcherId());
    }
}
