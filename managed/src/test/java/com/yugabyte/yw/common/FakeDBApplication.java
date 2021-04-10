// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.common;

import com.google.common.collect.Maps;
import com.yugabyte.yw.cloud.CloudAPI;
import com.yugabyte.yw.commissioner.*;
import com.yugabyte.yw.common.kms.EncryptionAtRestManager;
import com.yugabyte.yw.common.services.YBClientService;
import com.yugabyte.yw.metrics.MetricQueryHelper;
import com.yugabyte.yw.scheduler.Scheduler;
import org.pac4j.play.CallbackController;
import org.pac4j.play.store.PlayCacheSessionStore;
import org.pac4j.play.store.PlaySessionStore;
import play.Application;
import play.inject.guice.GuiceApplicationBuilder;
import play.mvc.Http;
import play.mvc.Result;
import play.test.Helpers;
import play.test.WithApplication;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BiFunction;

import static org.mockito.Mockito.mock;
import static play.inject.Bindings.bind;
import static play.test.Helpers.route;

public class FakeDBApplication extends WithApplication {
  public Commissioner mockCommissioner = mock(Commissioner.class);
  public CallHome mockCallHome = mock(CallHome.class);
  public ApiHelper mockApiHelper = mock(ApiHelper.class);
  public KubernetesManager mockKubernetesManager = mock(KubernetesManager.class);
  public HealthChecker mockHealthChecker = mock(HealthChecker.class);
  public EncryptionAtRestManager mockEARManager = mock(EncryptionAtRestManager.class);
  public SetUniverseKey mockSetUniverseKey = mock(SetUniverseKey.class);
  public CallbackController mockCallbackController = mock(CallbackController.class);
  public PlayCacheSessionStore mockSessionStore = mock(PlayCacheSessionStore.class);
  public AccessManager mockAccessManager = mock(AccessManager.class);
  public TemplateManager mockTemplateManager = mock(TemplateManager.class);
  public MetricQueryHelper mockMetricQueryHelper = mock(MetricQueryHelper.class);
  public CloudQueryHelper mockCloudQueryHelper = mock(CloudQueryHelper.class);
  public CloudAPI.Factory mockCloudAPIFactory = mock(CloudAPI.Factory.class);
  public ReleaseManager mockReleaseManager = mock(ReleaseManager.class);
  public YBClientService mockService = mock(YBClientService.class);
  public DnsManager mockDnsManager = mock(DnsManager.class);
  public NetworkManager mockNetworkManager = mock(NetworkManager.class);
  public YamlWrapper mockYamlWrapper = mock(YamlWrapper.class);
  public QueryAlerts mockQueryAlerts = mock(QueryAlerts.class);
  public Executors mockExecutors = mock(Executors.class);
  public ShellProcessHandler mockShellProcessHandler = mock(ShellProcessHandler.class);
  public TableManager mockTableManager = mock(TableManager.class);

  @Override
  protected Application provideApplication() {
    Map<String, Object> additionalConfiguration = new HashMap<>();
    return provideApplication(additionalConfiguration);
  }

  public Application provideApplication(Map<String, Object> additionalConfiguration) {

    return new GuiceApplicationBuilder()
      .configure(additionalConfiguration)
      .configure(Maps.newHashMap(Helpers.inMemoryDatabase()))
      .overrides(bind(ApiHelper.class).toInstance(mockApiHelper))
      .overrides(bind(Commissioner.class).toInstance(mockCommissioner))
      .overrides(bind(CallHome.class).toInstance(mockCallHome))
      .overrides(bind(HealthChecker.class).toInstance(mockHealthChecker))
      .overrides(bind(Executors.class).toInstance(mockExecutors))
      .overrides(bind(EncryptionAtRestManager.class).toInstance(mockEARManager))
      .overrides(bind(SetUniverseKey.class).toInstance(mockSetUniverseKey))
      .overrides(bind(KubernetesManager.class).toInstance(mockKubernetesManager))
      .overrides(bind(CallbackController.class).toInstance(mockCallbackController))
      .overrides(bind(PlaySessionStore.class).toInstance(mockSessionStore))
      .overrides(bind(AccessManager.class).toInstance(mockAccessManager))
      .overrides(bind(TemplateManager.class).toInstance(mockTemplateManager))
      .overrides(bind(MetricQueryHelper.class).toInstance(mockMetricQueryHelper))
      .overrides(bind(CloudQueryHelper.class).toInstance(mockCloudQueryHelper))
      .overrides(bind(ReleaseManager.class).toInstance(mockReleaseManager))
      .overrides(bind(YBClientService.class).toInstance(mockService))
      .overrides(bind(NetworkManager.class).toInstance(mockNetworkManager))
      .overrides(bind(DnsManager.class).toInstance(mockDnsManager))
      .overrides(bind(YamlWrapper.class).toInstance(mockYamlWrapper))
      .overrides(bind(QueryAlerts.class).toInstance(mockQueryAlerts))
      .overrides(bind(CloudAPI.Factory.class).toInstance(mockCloudAPIFactory))
      .overrides(bind(Scheduler.class).toInstance(mock(Scheduler.class)))
      .overrides(bind(ShellProcessHandler.class).toInstance(mockShellProcessHandler))
      .overrides(bind(TableManager.class).toInstance(mockTableManager))
      .build();
  }

  public Application getApp() {
    return app;
  }

  /**
   * If you want to quickly fix existing test that returns YWError json when exception
   * gets thrown then use this function. Alternatively change the test to expect that
   * YWException get thrown
   */
  public Result routeWithYWErrHandler(Http.RequestBuilder requestBuilder)
    throws InterruptedException, ExecutionException, TimeoutException {
    YWErrorHandler ywErrorHandler = getApp().injector().instanceOf(YWErrorHandler.class);
    CompletableFuture<Result> future =
      CompletableFuture.supplyAsync(() -> route(app, requestBuilder));
    BiFunction<Result, Throwable, CompletionStage<Result>> f =
      (result, throwable) -> {
        if (throwable == null)
          return CompletableFuture.supplyAsync(() -> result);
        return ywErrorHandler.onServerError(null, throwable);
      };

    return future.handleAsync(f).thenCompose(x -> x).get(20000, TimeUnit.MILLISECONDS);
  }
}
