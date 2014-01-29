package com.flightstats.datahub.app.config;

import com.codahale.metrics.MetricRegistry;
import com.conducivetech.services.common.util.constraint.ConstraintException;
import com.flightstats.datahub.app.config.metrics.PerChannelTimedMethodDispatchAdapter;
import com.flightstats.datahub.cluster.CuratorLock;
import com.flightstats.datahub.cluster.ZooKeeperState;
import com.flightstats.datahub.dao.aws.AwsDataStoreModule;
import com.flightstats.datahub.dao.cassandra.CassandraDataStoreModule;
import com.flightstats.datahub.model.ChannelConfiguration;
import com.flightstats.datahub.replication.ChannelUtils;
import com.flightstats.datahub.replication.Replicator;
import com.flightstats.datahub.rest.RetryClientFilter;
import com.flightstats.datahub.service.DataHubHealthCheck;
import com.flightstats.datahub.service.eventing.ChannelNameExtractor;
import com.flightstats.datahub.service.eventing.JettyWebSocketServlet;
import com.flightstats.datahub.service.eventing.MetricsCustomWebSocketCreator;
import com.flightstats.datahub.service.eventing.SubscriptionRoster;
import com.flightstats.datahub.util.TimeProvider;
import com.flightstats.jerseyguice.Bindings;
import com.flightstats.jerseyguice.JerseyServletModuleBuilder;
import com.flightstats.jerseyguice.metrics.GraphiteConfig;
import com.flightstats.jerseyguice.metrics.GraphiteConfigImpl;
import com.google.common.base.Strings;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.inject.*;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import com.google.inject.servlet.GuiceServletContextListener;
import com.hazelcast.config.ClasspathXmlConfig;
import com.hazelcast.config.Config;
import com.hazelcast.config.FileSystemXmlConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.container.filter.GZIPContentEncodingFilter;
import com.sun.jersey.api.core.ResourceConfig;
import com.sun.jersey.api.json.JSONConfiguration;
import com.sun.jersey.guice.JerseyServletModule;
import org.apache.curator.RetryPolicy;
import org.apache.curator.ensemble.fixed.FixedEnsembleProvider;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.BoundedExponentialBackoffRetry;
import org.apache.zookeeper.data.Stat;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

public class GuiceContextListenerFactory {
    private final static Logger logger = LoggerFactory.getLogger(GuiceContextListenerFactory.class);

    public static final String BACKING_STORE_PROPERTY = "backing.store";
    public static final String CASSANDRA_BACKING_STORE_TAG = "cassandra";
    public static final String AWS_BACKING_STORE_TAG = "aws";
    public static final String HAZELCAST_CONFIG_FILE = "hazelcast.conf.xml";
    private static Properties properties = new Properties();

    public static DataHubGuiceServletContextListener construct(
            @NotNull final Properties properties) throws ConstraintException {
        GuiceContextListenerFactory.properties = properties;
        GraphiteConfig graphiteConfig = new GraphiteConfigImpl(properties);

        Module module = getMaxPaloadSizeModule(properties);

        JerseyServletModule jerseyModule = new JerseyServletModuleBuilder()
                .withJerseyPackage("com.flightstats.datahub")
                .withContainerResponseFilters(GZIPContentEncodingFilter.class)
                .withJerseryProperty(JSONConfiguration.FEATURE_POJO_MAPPING, "true")
                .withJerseryProperty(ResourceConfig.FEATURE_CANONICALIZE_URI_PATH, "true")
                .withContainerRequestFilters(GZIPContentEncodingFilter.class, RemoveSlashFilter.class)
                .withNamedProperties(properties)
                .withGraphiteConfig(graphiteConfig)
                .withObjectMapper(DataHubObjectMapperFactory.construct())
                .withBindings(new DataHubBindings())
                .withHealthCheckClass(DataHubHealthCheck.class)
                .withRegexServe(ChannelNameExtractor.WEBSOCKET_URL_REGEX, JettyWebSocketServlet.class)
                .withModules(Arrays.asList(module))
                .build();

        return new DataHubGuiceServletContextListener(jerseyModule, createDataStoreModule(properties), new DatahubCommonModule());
    }

    private static Module getMaxPaloadSizeModule(final Properties properties) {

        return new AbstractModule() {
            @Override
            protected void configure() {
                bind(Integer.class)
                        .annotatedWith(Names.named("maxPayloadSizeBytes"))
                        .toInstance(getMaxPayloadSize(properties));
                bind(String.class)
                        .annotatedWith(Names.named("migration.source.urls"))
                        .toInstance(properties.getProperty("service.migration.source.urls", ""));
            }
        };
    }

    private static Integer getMaxPayloadSize(Properties properties) {
        String maxPayloadSizeMB = properties.getProperty("service.maxPayloadSizeMB", "10");
        try {
            int mb = Integer.parseInt(maxPayloadSizeMB);
            logger.info("Setting MAX_PAYLOAD_SIZE to " + maxPayloadSizeMB + "MB");
            return 1024 * 1024 * mb;
        } catch (NumberFormatException e) {
            throw new RuntimeException("Unable to parse 'service.maxPayloadSizeMB", e);
        }
    }

    public static class DataHubBindings implements Bindings {

        @Override
        public void bind(Binder binder) {
            binder.bind(MetricRegistry.class).in(Singleton.class);
            binder.bind(SubscriptionRoster.class).in(Singleton.class);
            binder.bind(PerChannelTimedMethodDispatchAdapter.class).asEagerSingleton();
            binder.bind(WebSocketCreator.class).to(MetricsCustomWebSocketCreator.class).in(Singleton.class);
            binder.bind(JettyWebSocketServlet.class).in(Singleton.class);
            binder.bind(TimeProvider.class).in(Singleton.class);
            binder.bind(ZooKeeperState.class).in(Singleton.class);
            binder.bind(Replicator.class).in(Singleton.class);
            binder.bind(ChannelUtils.class).in(Singleton.class);
            binder.bind(CuratorLock.class).in(Singleton.class);
        }
    }

    public static class DataHubGuiceServletContextListener extends GuiceServletContextListener {
        @NotNull
        private final Module[] modules;
        private Injector injector;

        public DataHubGuiceServletContextListener(@NotNull Module... modules) {
            this.modules = modules;
        }

        @Override
        public synchronized Injector getInjector() {
            if (injector == null) {
                injector = Guice.createInjector(modules);
            }
            return injector;
        }
    }

    private static Module createDataStoreModule(Properties properties) {
        String backingStoreName = properties.getProperty(BACKING_STORE_PROPERTY, AWS_BACKING_STORE_TAG);
        logger.info("using data store " + backingStoreName);
        switch (backingStoreName) {
            case CASSANDRA_BACKING_STORE_TAG:
                return new CassandraDataStoreModule(properties);
            case AWS_BACKING_STORE_TAG:
                return new AwsDataStoreModule(properties);
            default:
                throw new IllegalStateException(String.format("Unknown backing store specified: %s", backingStoreName));
        }
    }

    public static class DatahubCommonModule extends AbstractModule {
        @Singleton
        @Provides
        public static HazelcastInstance buildHazelcast(@Named(HAZELCAST_CONFIG_FILE) String hazelcastConfigFile) throws FileNotFoundException {
            Config config;
            if (Strings.isNullOrEmpty(hazelcastConfigFile)) {
                config = new ClasspathXmlConfig("hazelcast.conf.xml");
            } else {
                config = new FileSystemXmlConfig(hazelcastConfigFile);
            }
            return Hazelcast.newHazelcastInstance(config);
        }

        @Named("ChannelConfigurationMap")
        @Singleton
        @Provides
        public static ConcurrentMap<String, ChannelConfiguration> buildChannelConfigurationMap() throws FileNotFoundException {
            Cache<String, ChannelConfiguration> cache = CacheBuilder.newBuilder()
                    .expireAfterAccess(15L, TimeUnit.MINUTES)
                    .build();
            return cache.asMap();
        }

        @Override
        protected void configure() {
        }

        @Singleton
        @Provides
        public static CuratorFramework buildCurator(@Named("zookeeper.connection") String zkConnection,
                                                    RetryPolicy retryPolicy, ZooKeeperState zooKeeperState) {
            logger.info("connecting to zookeeper(s) at " + zkConnection);
            FixedEnsembleProvider ensembleProvider = new FixedEnsembleProvider(zkConnection);
            CuratorFramework curatorFramework = CuratorFrameworkFactory.builder().namespace("deihub")
                    .ensembleProvider(ensembleProvider)
                    .retryPolicy(retryPolicy).build();
            curatorFramework.getConnectionStateListenable().addListener(zooKeeperState.getStateListener());
            curatorFramework.start();

            try {
                Stat stat = curatorFramework.checkExists().forPath("/startup");
            } catch (Exception e) {
                logger.warn("unable to access zookeeper");
                throw new RuntimeException("unable to access zookeeper");
            }
            return curatorFramework;
        }

        @Singleton
        @Provides
        public static RetryPolicy buildRetryPolicy(){
            Integer baseSleepTimeMs = Integer.valueOf(properties.getProperty("zookeeper.baseSleepTimeMs", "10"));
            Integer maxSleepTimeMs = Integer.valueOf(properties.getProperty("zookeeper.maxSleepTimeMs", "10000"));
            Integer maxRetries = Integer.valueOf(properties.getProperty("zookeeper.maxRetries", "20"));

            return new BoundedExponentialBackoffRetry(baseSleepTimeMs, maxSleepTimeMs, maxRetries);
        }

        @Singleton
        @Provides
        public static Client buildJerseyClient() {
            return create(true);
        }

        @Named("NoRedirects")
        @Singleton
        @Provides
        public static Client buildJerseyClientNoRedirects() {
            return create(false);
        }

        private static Client create(boolean followRedirects) {
            //todo - gfm - 1/21/14 - pull these out into properties
            int connectTimeoutMillis = (int) TimeUnit.SECONDS.toMillis(30);
            int readTimeoutMillis = (int) TimeUnit.SECONDS.toMillis(60);

            Client client = Client.create();
            client.setConnectTimeout(connectTimeoutMillis);
            client.setReadTimeout(readTimeoutMillis);
            client.addFilter(new RetryClientFilter());
            client.setFollowRedirects(followRedirects);
            return client;
        }
    }
}
