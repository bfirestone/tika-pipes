package org.apache.tika.pipes.cli.http;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.undertow.Undertow;
import io.undertow.util.Headers;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.*;
import org.apache.tika.pipes.fetchers.http.config.HttpFetcherConfig;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.apache.tika.pipes.cli.mapper.ObjectMapperProvider.OBJECT_MAPPER;

@Slf4j
public class HttpFetcherCli {
    public static final String TIKA_SERVER_GRPC_DEFAULT_HOST = "localhost";
    public static final int TIKA_SERVER_GRPC_DEFAULT_PORT = 50051;
    @Parameter(names = {"--fetch-urls"}, description = "File of URLs to fetch", help = true)
    private File urlsToFetchFile;
    @Parameter(names = {"--grpcHost"}, description = "The grpc host", help = true)
    private String host = TIKA_SERVER_GRPC_DEFAULT_HOST;
    @Parameter(names = {"--grpcPort"}, description = "The grpc server port", help = true)
    private Integer port = TIKA_SERVER_GRPC_DEFAULT_PORT;
    @Parameter(names = {"--fetcher-id"}, description = "What fetcher ID should we use? By default will use http-fetcher")
    private String fetcherId = "http-fetcher";
    @Parameter(names = {"-h", "-H", "--help"}, description = "Display help menu")
    private boolean help;

    public static int getRandomAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Could not find an available port", e);
        }
    }
    static int httpServerPort;
    public static void main(String[] args) throws Exception {
        httpServerPort = getRandomAvailablePort();
        Undertow server = Undertow
                .builder()
                .addHttpListener(httpServerPort, InetAddress.getLocalHost().getHostAddress())
                .setHandler(exchange -> {
                                      exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html");
                                      exchange.getResponseSender().send("<html><body>test</body></html>");
                                  }).build();

        try {
            server.start();
            HttpFetcherCli bulkParser = new HttpFetcherCli();
            JCommander commander = JCommander
                    .newBuilder()
                    .addObject(bulkParser)
                    .build();
            commander.parse(args);
            if (bulkParser.help) {
                commander.usage();
                return;
            }
            bulkParser.runFetch();
        } finally {
            server.stop();
        }
    }

    private void runFetch() throws IOException {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(host, port)
                .usePlaintext()
                .directExecutor()
                .build();
        TikaGrpc.TikaBlockingStub blockingStub = TikaGrpc.newBlockingStub(channel);
        TikaGrpc.TikaStub tikaStub = TikaGrpc.newStub(channel);

        HttpFetcherConfig httpFetcherConfig = new HttpFetcherConfig();

        SaveFetcherReply reply = blockingStub.saveFetcher(SaveFetcherRequest
                .newBuilder()
                .setFetcherId(fetcherId)
                .setPluginId("http-fetcher")
                .setFetcherConfigJson(OBJECT_MAPPER.writeValueAsString(httpFetcherConfig))
                .build());
        log.info("Saved fetcher with ID {}", reply.getFetcherId());

        List<FetchAndParseReply> successes = Collections.synchronizedList(new ArrayList<>());
        List<FetchAndParseReply> errors = Collections.synchronizedList(new ArrayList<>());

        CountDownLatch countDownLatch = new CountDownLatch(1);
        StreamObserver<FetchAndParseRequest> requestStreamObserver = tikaStub.fetchAndParseBiDirectionalStreaming(new StreamObserver<>() {
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

        requestStreamObserver.onNext(FetchAndParseRequest
                    .newBuilder()
                    .setFetcherId(fetcherId)
                    .setFetchKey("http://" + InetAddress.getLocalHost().getHostAddress() + ":" + httpServerPort)
                    .setFetchMetadataJson(OBJECT_MAPPER.writeValueAsString(Map.of()))
                    .build());

        log.info("Done submitting URLs to {}", fetcherId);
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
}
