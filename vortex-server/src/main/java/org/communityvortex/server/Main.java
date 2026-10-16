package org.communityvortex.server;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import org.communityvortex.mcp.analysis.LocalAnalyzer;
import org.communityvortex.mcp.sonar.HttpSonarServer;
import org.slf4j.LoggerFactory;

/** Runs the analysis service until the process is stopped. */
public final class Main {

  private Main() {
  }

  public static void main(String[] args) throws Exception {
    var log = LoggerFactory.getLogger(Main.class);
    ServerConfig config;
    try {
      config = ServerConfig.fromEnvironment(System.getenv(), Path.of(System.getProperty("user.home")));
    } catch (IllegalArgumentException e) {
      log.error("Invalid configuration: {}", e.getMessage());
      System.exit(2);
      return;
    }
    var analyzer = new LocalAnalyzer(config.cacheDir(), config.nodePath());
    var service = new A3sService(token -> new HttpSonarServer(config.sonarUrl(), token), analyzer, config.cacheDir(), config.maxFiles());
    var http = new VortexHttpServer(new InetSocketAddress(config.bind(), config.port()), service, config.threads());
    var stopped = new CountDownLatch(1);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      http.close();
      analyzer.close();
      stopped.countDown();
    }));
    http.start();
    log.info("Answering SonarQube CLI analysis requests for SonarQube at {}", config.sonarUrl().getHost());
    stopped.await();
  }
}
