/*
 * Copyright (c) 2024 Snowflake Computing Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.polaris.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.dropwizard.auth.AuthDynamicFeature;
import io.dropwizard.auth.AuthFilter;
import io.dropwizard.auth.oauth.OAuthCredentialAuthFilter;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.semconv.ServiceAttributes;
import io.polaris.core.PolarisConfigurationStore;
import io.polaris.core.auth.AuthenticatedPolarisPrincipal;
import io.polaris.core.auth.PolarisAuthorizer;
import io.polaris.core.context.CallContext;
import io.polaris.core.context.RealmContext;
import io.polaris.core.monitor.PolarisMetricRegistry;
import io.polaris.core.persistence.MetaStoreManagerFactory;
import io.polaris.service.admin.PolarisServiceImpl;
import io.polaris.service.admin.api.PolarisCatalogsApi;
import io.polaris.service.admin.api.PolarisPrincipalRolesApi;
import io.polaris.service.admin.api.PolarisPrincipalsApi;
import io.polaris.service.auth.DiscoverableAuthenticator;
import io.polaris.service.catalog.IcebergCatalogAdapter;
import io.polaris.service.catalog.api.IcebergRestCatalogApi;
import io.polaris.service.catalog.api.IcebergRestConfigurationApi;
import io.polaris.service.catalog.api.IcebergRestOAuth2Api;
import io.polaris.service.config.ConfigurationStoreAware;
import io.polaris.service.config.HasEntityManagerFactory;
import io.polaris.service.config.OAuth2ApiService;
import io.polaris.service.config.PolarisApplicationConfig;
import io.polaris.service.config.RealmEntityManagerFactory;
import io.polaris.service.config.Serializers;
import io.polaris.service.config.TaskHandlerConfiguration;
import io.polaris.service.context.CallContextCatalogFactory;
import io.polaris.service.context.CallContextResolver;
import io.polaris.service.context.PolarisCallContextCatalogFactory;
import io.polaris.service.context.RealmContextResolver;
import io.polaris.service.persistence.InMemoryPolarisMetaStoreManagerFactory;
import io.polaris.service.storage.PolarisStorageIntegrationProviderImpl;
import io.polaris.service.task.ManifestFileCleanupTaskHandler;
import io.polaris.service.task.TableCleanupTaskHandler;
import io.polaris.service.task.TaskExecutorImpl;
import io.polaris.service.task.TaskFileIOSupplier;
import io.polaris.service.tracing.OpenTelemetryAware;
import io.polaris.service.tracing.TracingFilter;
import io.prometheus.metrics.exporter.servlet.jakarta.PrometheusMetricsServlet;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.iceberg.rest.RESTSerializers;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.StsClientBuilder;

public class PolarisApplication extends Application<PolarisApplicationConfig> {
  private static final Logger LOGGER = LoggerFactory.getLogger(PolarisApplication.class);

  public static void main(final String[] args) throws Exception {
    new PolarisApplication().run(args);
    printAsciiArt();
  }

  private static void printAsciiArt() throws IOException {
    URL url = PolarisApplication.class.getResource("banner.txt");
    try (InputStream in =
        requireNonNull(url, "banner.txt not found on classpath")
            .openConnection()
            .getInputStream()) {
      System.out.println(new String(in.readAllBytes(), UTF_8));
    }
  }

  @Override
  public void initialize(Bootstrap<PolarisApplicationConfig> bootstrap) {
    // Enable variable substitution with environment variables
    EnvironmentVariableSubstitutor substitutor = new EnvironmentVariableSubstitutor(false);
    SubstitutingSourceProvider provider =
        new SubstitutingSourceProvider(bootstrap.getConfigurationSourceProvider(), substitutor);
    bootstrap.setConfigurationSourceProvider(provider);

    bootstrap.addCommand(new BootstrapRealmsCommand());
  }

  @Override
  public void run(PolarisApplicationConfig configuration, Environment environment) {
    MetaStoreManagerFactory metaStoreManagerFactory = configuration.getMetaStoreManagerFactory();

    metaStoreManagerFactory.setStorageIntegrationProvider(
        new PolarisStorageIntegrationProviderImpl(
            () -> {
              StsClientBuilder stsClientBuilder = StsClient.builder();
              AwsCredentialsProvider awsCredentialsProvider = configuration.credentialsProvider();
              if (awsCredentialsProvider != null) {
                stsClientBuilder.credentialsProvider(awsCredentialsProvider);
              }
              return stsClientBuilder.build();
            }));

    PolarisMetricRegistry polarisMetricRegistry =
        new PolarisMetricRegistry(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));
    metaStoreManagerFactory.setMetricRegistry(polarisMetricRegistry);

    OpenTelemetry openTelemetry = setupTracing();
    if (metaStoreManagerFactory instanceof OpenTelemetryAware otAware) {
      otAware.setOpenTelemetry(openTelemetry);
    }
    PolarisConfigurationStore configurationStore = configuration.getConfigurationStore();
    if (metaStoreManagerFactory instanceof ConfigurationStoreAware) {
      ((ConfigurationStoreAware) metaStoreManagerFactory).setConfigurationStore(configurationStore);
    }
    RealmEntityManagerFactory entityManagerFactory =
        new RealmEntityManagerFactory(metaStoreManagerFactory);
    CallContextResolver callContextResolver = configuration.getCallContextResolver();
    callContextResolver.setEntityManagerFactory(entityManagerFactory);
    if (callContextResolver instanceof ConfigurationStoreAware csa) {
      csa.setConfigurationStore(configurationStore);
    }

    RealmContextResolver realmContextResolver = configuration.getRealmContextResolver();
    realmContextResolver.setEntityManagerFactory(entityManagerFactory);
    environment
        .servlets()
        .addFilter(
            "realmContext", new ContextResolverFilter(realmContextResolver, callContextResolver))
        .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*");

    TaskHandlerConfiguration taskConfig = configuration.getTaskHandler();
    TaskExecutorImpl taskExecutor =
        new TaskExecutorImpl(taskConfig.executorService(), metaStoreManagerFactory);
    TaskFileIOSupplier fileIOSupplier = new TaskFileIOSupplier(metaStoreManagerFactory);
    taskExecutor.addTaskHandler(
        new TableCleanupTaskHandler(taskExecutor, metaStoreManagerFactory, fileIOSupplier));
    taskExecutor.addTaskHandler(
        new ManifestFileCleanupTaskHandler(
            fileIOSupplier, Executors.newVirtualThreadPerTaskExecutor()));

    LOGGER.info(
        "Initializing PolarisCallContextCatalogFactory for metaStoreManagerType {}",
        metaStoreManagerFactory);
    CallContextCatalogFactory catalogFactory =
        new PolarisCallContextCatalogFactory(entityManagerFactory, taskExecutor);

    PolarisAuthorizer authorizer = new PolarisAuthorizer(configurationStore);
    IcebergCatalogAdapter catalogAdapter =
        new IcebergCatalogAdapter(catalogFactory, entityManagerFactory, authorizer);
    environment.jersey().register(new IcebergRestCatalogApi(catalogAdapter));
    environment.jersey().register(new IcebergRestConfigurationApi(catalogAdapter));

    FilterRegistration.Dynamic corsRegistration =
        environment.servlets().addFilter("CORS", CrossOriginFilter.class);
    corsRegistration.setInitParameter(
        CrossOriginFilter.ALLOWED_ORIGINS_PARAM,
        String.join(",", configuration.getCorsConfiguration().getAllowedOrigins()));
    corsRegistration.setInitParameter(
        CrossOriginFilter.ALLOWED_TIMING_ORIGINS_PARAM,
        String.join(",", configuration.getCorsConfiguration().getAllowedTimingOrigins()));
    corsRegistration.setInitParameter(
        CrossOriginFilter.ALLOWED_METHODS_PARAM,
        String.join(",", configuration.getCorsConfiguration().getAllowedMethods()));
    corsRegistration.setInitParameter(
        CrossOriginFilter.ALLOWED_HEADERS_PARAM,
        String.join(",", configuration.getCorsConfiguration().getAllowedHeaders()));
    corsRegistration.setInitParameter(
        CrossOriginFilter.ALLOW_CREDENTIALS_PARAM,
        String.join(",", configuration.getCorsConfiguration().getAllowCredentials()));
    corsRegistration.setInitParameter(
        CrossOriginFilter.PREFLIGHT_MAX_AGE_PARAM,
        Objects.toString(configuration.getCorsConfiguration().getPreflightMaxAge()));
    corsRegistration.setInitParameter(
        CrossOriginFilter.ALLOW_CREDENTIALS_PARAM,
        configuration.getCorsConfiguration().getAllowCredentials());
    corsRegistration.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*");
    environment
        .servlets()
        .addFilter("tracing", new TracingFilter(openTelemetry))
        .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*");
    DiscoverableAuthenticator<String, AuthenticatedPolarisPrincipal> authenticator =
        configuration.getPolarisAuthenticator();
    authenticator.setEntityManagerFactory(entityManagerFactory);
    AuthFilter<String, AuthenticatedPolarisPrincipal> oauthCredentialAuthFilter =
        new OAuthCredentialAuthFilter.Builder<AuthenticatedPolarisPrincipal>()
            .setAuthenticator(authenticator)
            .setPrefix("Bearer")
            .buildAuthFilter();
    environment.jersey().register(new AuthDynamicFeature(oauthCredentialAuthFilter));
    environment.healthChecks().register("polaris", new PolarisHealthCheck());
    OAuth2ApiService oauth2Service = configuration.getOauth2Service();
    if (oauth2Service instanceof HasEntityManagerFactory emfAware) {
      emfAware.setEntityManagerFactory(entityManagerFactory);
    }
    environment.jersey().register(new IcebergRestOAuth2Api(oauth2Service));
    environment.jersey().register(new IcebergExceptionMapper());
    PolarisServiceImpl polarisService = new PolarisServiceImpl(entityManagerFactory, authorizer);
    environment.jersey().register(new PolarisCatalogsApi(polarisService));
    environment.jersey().register(new PolarisPrincipalsApi(polarisService));
    environment.jersey().register(new PolarisPrincipalRolesApi(polarisService));
    ObjectMapper objectMapper = environment.getObjectMapper();
    objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    objectMapper.setPropertyNamingStrategy(new PropertyNamingStrategies.KebabCaseStrategy());
    RESTSerializers.registerAll(objectMapper);
    Serializers.registerSerializers(objectMapper);
    environment.jersey().register(new IcebergJsonProcessingExceptionMapper());
    environment.jersey().register(new IcebergJerseyViolationExceptionMapper());
    environment.jersey().register(new TimedApplicationEventListener(polarisMetricRegistry));

    polarisMetricRegistry.init(
        IcebergRestCatalogApi.class,
        IcebergRestConfigurationApi.class,
        IcebergRestOAuth2Api.class,
        PolarisCatalogsApi.class,
        PolarisPrincipalsApi.class,
        PolarisPrincipalRolesApi.class);

    environment
        .admin()
        .addServlet(
            "metrics",
            new PrometheusMetricsServlet(
                ((PrometheusMeterRegistry) polarisMetricRegistry.getMeterRegistry())
                    .getPrometheusRegistry()))
        .addMapping("/metrics");

    // For in-memory metastore we need to bootstrap Service and Service principal at startup (for
    // default realm)
    // We can not utilize dropwizard Bootstrap command as command and server will be running two
    // different processes
    // and in-memory state will be lost b/w invocation of bootstrap command and running a server
    if (metaStoreManagerFactory instanceof InMemoryPolarisMetaStoreManagerFactory) {
      metaStoreManagerFactory.getOrCreateMetaStoreManager(configuration::getDefaultRealm);
    }
  }

  private static OpenTelemetry setupTracing() {
    Resource resource =
        Resource.getDefault().toBuilder()
            .put(ServiceAttributes.SERVICE_NAME, "polaris")
            .put(ServiceAttributes.SERVICE_VERSION, "0.1.0")
            .build();
    SdkTracerProvider sdkTracerProvider =
        SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create()))
            .setResource(resource)
            .build();
    return OpenTelemetrySdk.builder()
        .setTracerProvider(sdkTracerProvider)
        .setPropagators(
            ContextPropagators.create(
                TextMapPropagator.composite(
                    W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance())))
        .build();
  }

  /** Resolves and sets ThreadLocal CallContext/RealmContext based on the request contents. */
  private static class ContextResolverFilter implements Filter {
    private final RealmContextResolver realmContextResolver;
    private final CallContextResolver callContextResolver;

    public ContextResolverFilter(
        RealmContextResolver realmContextResolver, CallContextResolver callContextResolver) {
      this.realmContextResolver = realmContextResolver;
      this.callContextResolver = callContextResolver;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
      HttpServletRequest httpRequest = (HttpServletRequest) request;
      Stream<String> headerNames = Collections.list(httpRequest.getHeaderNames()).stream();
      Map<String, String> headers =
          headerNames.collect(Collectors.toMap(Function.identity(), httpRequest::getHeader));
      RealmContext currentRealmContext =
          realmContextResolver.resolveRealmContext(
              httpRequest.getRequestURL().toString(),
              httpRequest.getMethod(),
              httpRequest.getRequestURI().substring(1),
              request.getParameterMap().entrySet().stream()
                  .collect(
                      Collectors.toMap(Map.Entry::getKey, (e) -> ((String[]) e.getValue())[0])),
              headers);
      CallContext currentCallContext =
          callContextResolver.resolveCallContext(
              currentRealmContext,
              httpRequest.getMethod(),
              httpRequest.getRequestURI().substring(1),
              request.getParameterMap().entrySet().stream()
                  .collect(
                      Collectors.toMap(Map.Entry::getKey, (e) -> ((String[]) e.getValue())[0])),
              headers);
      CallContext.setCurrentContext(currentCallContext);
      try (MDC.MDCCloseable ignored1 =
              MDC.putCloseable("realm", currentRealmContext.getRealmIdentifier());
          MDC.MDCCloseable ignored2 =
              MDC.putCloseable("request_id", httpRequest.getHeader("request_id"))) {
        chain.doFilter(request, response);
      } finally {
        Object contextCatalog =
            currentCallContext
                .contextVariables()
                .get(CallContext.REQUEST_PATH_CATALOG_INSTANCE_KEY);
        if (contextCatalog instanceof Closeable closeableCatalog) {
          closeableCatalog.close();
        }
        currentCallContext.close();
      }
    }
  }
}
