package com.netflix.priam.dropwizard.managers;

import com.bazaarvoice.badger.api.BadgerRegistration;
import com.bazaarvoice.badger.api.BadgerRegistrationBuilder;
import com.google.common.base.Optional;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.netflix.priam.config.MonitoringConfiguration;
import com.yammer.dropwizard.config.HttpConfiguration;
import com.yammer.dropwizard.lifecycle.Managed;
import org.apache.curator.framework.CuratorFramework;

import java.util.concurrent.TimeUnit;

import static java.lang.String.format;

@Singleton
public class ServiceMonitorManager implements Managed {

    private final MonitoringConfiguration monitoringConfiguration;
    private final CuratorFramework curator;
    private final HttpConfiguration httpConfiguration;
    private BadgerRegistration badgerRegistration;

    @Inject
    public ServiceMonitorManager(MonitoringConfiguration monitoringConfiguration,
                                 Optional<CuratorFramework> curator,
                                 HttpConfiguration httpConfiguration) {
        this.monitoringConfiguration = monitoringConfiguration;
        this.curator = curator.orNull();
        this.httpConfiguration = httpConfiguration;
    }

    @Override
    public void start() throws Exception {
        if (curator == null) {
            return;  // Disabled
        }

        if (monitoringConfiguration.getDefaultBadgerRegistrationState()) {
            register();
        } else {
            deregister();
        }
    }

    @Override
    public void stop() throws Exception {
        deregister();
    }

    public synchronized boolean isRegistered() {
        return badgerRegistration != null;
    }

    public synchronized void register() {
        if (curator == null || badgerRegistration != null) {
            return;
        }

        // If ZooKeeper is configured, start Badger external monitoring
        String badgerServiceName = format(monitoringConfiguration.getBadgerServiceName());
        badgerRegistration = new BadgerRegistrationBuilder(curator, badgerServiceName)
                .withVerificationPath(httpConfiguration.getPort(), "/v1/cassadmin/pingthrift")
                .withVersion(this.getClass().getPackage().getImplementationVersion())
                .withAwsTags()
                .register();
    }

    public synchronized void deregister() {
        if (badgerRegistration != null) {
            badgerRegistration.unregister(10, TimeUnit.SECONDS);
            badgerRegistration = null;
        }
    }
}
