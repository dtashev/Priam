/**
 * Copyright 2013 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.priam.identity;

import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.netflix.priam.config.AmazonConfiguration;
import com.netflix.priam.config.CassandraConfiguration;
import com.netflix.priam.utils.TokenManager;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Class providing functionality for doubling the ring
 */
public class DoubleRing {
    private static final Logger logger = LoggerFactory.getLogger(DoubleRing.class);
    private static File TMP_BACKUP_FILE;

    private final CassandraConfiguration cassandraConfiguration;
    private final AmazonConfiguration amazonConfiguration;
    private final IPriamInstanceRegistry instanceRegistry;
    private final TokenManager tokenManager;

    @Inject
    public DoubleRing(CassandraConfiguration cassandraConfiguration, AmazonConfiguration amazonConfiguration, IPriamInstanceRegistry instanceRegistry, TokenManager tokenManager) {
        this.cassandraConfiguration = cassandraConfiguration;
        this.amazonConfiguration = amazonConfiguration;
        this.instanceRegistry = instanceRegistry;
        this.tokenManager = tokenManager;
    }

    /**
     * Doubling is done by pre-calculating all slots of a double ring and
     * registering them. When new nodes come up, they will get the unused token
     * assigned per token logic.
     */
    public void doubleSlots() {
        List<PriamInstance> instancesInRegion = filteredRemote(instanceRegistry.getAllIds(cassandraConfiguration.getClusterName()));

        // Remove all instances in this region from the registry
        for (PriamInstance priamInstance : instancesInRegion) {
            instanceRegistry.delete(priamInstance);
        }

        int regionOffsetHash = TokenManager.regionOffset(amazonConfiguration.getRegionName());
        int newRingSize = instancesInRegion.size() * 2;
        int numZones = amazonConfiguration.getUsableAvailabilityZones().size();

        for (PriamInstance priamInstance : instancesInRegion) {
            // Move an existing instance by doubling the old slot #.
            int slot = (priamInstance.getId() - regionOffsetHash) * 2;
            instanceRegistry.create(priamInstance.getApp(),
                    regionOffsetHash + slot,
                    priamInstance.getInstanceId(),
                    priamInstance.getHostName(),
                    priamInstance.getHostIP(),
                    priamInstance.getAvailabilityZone(),
                    priamInstance.getVolumes(),
                    priamInstance.getToken());

            // Add a new slot in the same zone, numZones away.  Because slot is even and numZones is odd the
            // new slot # will be odd and won't conflict with slot #s for existing instances.
            int newSlot = (slot + numZones) % newRingSize;
            String token = tokenManager.createToken(newSlot, newRingSize, amazonConfiguration.getRegionName());
            instanceRegistry.create(priamInstance.getApp(),
                    regionOffsetHash + newSlot,
                    "new_slot",
                    amazonConfiguration.getPrivateHostName(),
                    amazonConfiguration.getPrivateIP(),
                    priamInstance.getAvailabilityZone(),
                    null,
                    token);
        }
    }

    // filter other DC's
    private List<PriamInstance> filteredRemote(List<PriamInstance> priamInstances) {
        List<PriamInstance> local = Lists.newArrayList();
        for (PriamInstance priamInstance : priamInstances) {
            if (priamInstance.getRegionName().equals(amazonConfiguration.getRegionName())) {
                local.add(priamInstance);
            }
        }
        return local;
    }

    /**
     * Backup the current state in case of failure
     */
    public void backup() throws IOException {
        // writing to the backup file.
        TMP_BACKUP_FILE = File.createTempFile("Backup-instance-data", ".dat");
        OutputStream out = new FileOutputStream(TMP_BACKUP_FILE);
        ObjectOutputStream stream = new ObjectOutputStream(out);
        try {
            stream.writeObject(filteredRemote(instanceRegistry.getAllIds(cassandraConfiguration.getClusterName())));
            logger.info("Wrote the backup of the instances to: {}", TMP_BACKUP_FILE.getAbsolutePath());
        } finally {
            IOUtils.closeQuietly(stream);
            IOUtils.closeQuietly(out);
        }
    }

    /**
     * Restore tokens if a failure occurs
     *
     * @throws IOException
     * @throws ClassNotFoundException
     */
    public void restore() throws IOException, ClassNotFoundException {
        for (PriamInstance data : filteredRemote(instanceRegistry.getAllIds(cassandraConfiguration.getClusterName()))) {
            instanceRegistry.delete(data);
        }

        // read from the file.
        InputStream in = new FileInputStream(TMP_BACKUP_FILE);
        ObjectInputStream stream = new ObjectInputStream(in);
        try {
            @SuppressWarnings("unchecked")
            List<PriamInstance> allInstances = (List<PriamInstance>) stream.readObject();
            for (PriamInstance data : allInstances) {
                instanceRegistry.create(data.getApp(), data.getId(), data.getInstanceId(), data.getHostName(), data.getHostIP(), data.getAvailabilityZone(), data.getVolumes(), data.getToken());
            }
            logger.info("Successfully restored the Instances from the backup: {}", TMP_BACKUP_FILE.getAbsolutePath());
        } finally {
            IOUtils.closeQuietly(stream);
            IOUtils.closeQuietly(in);
        }
    }

}
