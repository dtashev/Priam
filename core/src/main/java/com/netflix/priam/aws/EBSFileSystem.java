package com.netflix.priam.aws;

import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.*;
import com.google.common.base.Throwables;
import com.google.common.io.CharStreams;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.netflix.priam.ICredential;
import com.netflix.priam.backup.AbstractBackupPath;
import com.netflix.priam.backup.BackupRestoreException;
import com.netflix.priam.backup.IBackupFileSystem;
import com.netflix.priam.config.AmazonConfiguration;
import com.netflix.priam.config.BackupConfiguration;
import com.netflix.priam.config.CassandraConfiguration;
import com.netflix.priam.identity.InstanceIdentity;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Implementation of IBackupFileSystem for EBS
 */
@Singleton
public class EBSFileSystem implements IBackupFileSystem, EBSFileSystemMBean {
    private static final Logger logger = LoggerFactory.getLogger(EBSFileSystem.class);
    private static final long UPLOAD_TIMEOUT = (2 * 60 * 60 * 1000L);
    private static final long AWS_API_POLL_INTERVAL = (30 * 1000L);

    private final Provider<AbstractBackupPath> pathProvider;
    private final BackupConfiguration backupConfiguration;
    private final CassandraConfiguration cassandraConfiguration;
    private final AmazonConfiguration amazonConfiguration;
    private final InstanceIdentity instanceIdentity;
    private final ICredential cred;

    private AtomicLong bytesDownloaded = new AtomicLong();
    private AtomicLong bytesUploaded = new AtomicLong();
    private AtomicInteger uploadCount = new AtomicInteger();
    private AtomicInteger downloadCount = new AtomicInteger();
    private AmazonEC2Client ec2client;

    @Inject
    public EBSFileSystem(Provider<AbstractBackupPath> pathProvider, final BackupConfiguration backupConfiguration, CassandraConfiguration cassandraConfiguration, AmazonConfiguration amazonConfiguration, InstanceIdentity instanceIdentity, ICredential cred)
        throws BackupRestoreException {
        this.pathProvider = pathProvider;
        this.cassandraConfiguration = cassandraConfiguration;
        this.backupConfiguration = backupConfiguration;
        this.amazonConfiguration = amazonConfiguration;
        this.instanceIdentity = instanceIdentity;
        this.cred = cred;
        this.ec2client = new AmazonEC2Client(cred.getCredentials());

        // mount and attach EBS
        ebsMountAndAttach();

        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        String mbeanName = MBEAN_NAME;
        try {
            mbs.registerMBean(this, new ObjectName(mbeanName));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<Volume> getEbsVolumes() {
        DescribeVolumesRequest volumesRequest = new DescribeVolumesRequest()
                .withFilters(
                        new Filter()
                                .withName("tag:Name")
                                .withValues(cassandraConfiguration.getClusterName() + "-" + instanceIdentity.getInstance().getToken())
                );

        DescribeVolumesResult volumesResult = ec2client.describeVolumes(volumesRequest);

        return volumesResult.getVolumes();
    }

    public boolean isEbsAttached() {

        List<Volume> volumeList = getEbsVolumes();
        boolean isAttached = false;

        logger.info("{}", volumeList);

        // go ahead and return false if there are no results
        if (volumeList.isEmpty()){
            logger.info("No volumes match our criteria for this instance.");
            isAttached = false;
        } else {
            logger.info("Found volumes for this instance.");
            // go through all of the volumes with this tag name
            for (Volume vol : volumeList) {
                logger.info("Inspecting volume: {}", vol);
                // look at the attachments -- are any of them attached to the current instance?
                for (VolumeAttachment attachment : vol.getAttachments()) {
                    logger.info("Looking at attachment: {}", attachment);
                    // if yes, it's attached to us
                    if (attachment.getInstanceId().equals(instanceIdentity.getInstance().getInstanceId())) {
                        logger.info("Found that the volume is attached to our instance.");
                        isAttached = true;
                        break;
                    }
                }
            }
        }

        return isAttached;
    }

    private void mountVolume(Volume volume) {

        logger.info("Attempting to mount volume: {}", volume);

        try {
            // if there's more than 1 attachment for this volume, then it's...attached to multiple instances?
            // this shouldn't happen because, currently, Amazon doesn't support that for EBS
            if (volume.getAttachments().size() > 1 ){
                logger.error("Failed to mount EBS volume {} because there were too many attachments for this volume. ", volume.getVolumeId());
            }

            // @TODO: make this platform-agnostic somehow
            // @TODO: bundle this with the jar!
            Process mountVolumeCmd = Runtime.getRuntime().exec("/opt/bazaarvoice/bin/mountEbsVolume.sh " + volume.getAttachments().get(0).getDevice() + " backup");

            logger.info("{}", CharStreams.toString(new InputStreamReader(mountVolumeCmd.getInputStream())));

        } catch (Exception e){ // runtime or IO
            logger.info("Failed to mount EBS volume. ", e.getMessage());
            logger.info(e.getMessage(), e);
            Throwables.propagate(e);
        }
    }

    // ideally, this list should always be of size 1
    // otherwise, what do we do with the other volumes??
    // leave it open in case for some reason we want to use multiple EBS volumes
    // but for now, just process the 1st volume only
    private void mountVolume(List<Volume> volumes) {
        for (Volume volume : volumes) {
            mountVolume(volume);
            // stop after mounting the first volume because we don't support multiple ebs volumes
            break;
        }
    }

    // attach volume
    private void attachVolume(Volume volume) {
        if (volume.getState().contentEquals("available")) {
            logger.info("Found volume to re-attach: {}", volume);

            // define a sane default
            String devicePrefix = "/dev/xvd";
            String nextDeviceName = devicePrefix + "f";

            // @TODO: make this platform-agnostic?
            try {
                // grab the list of mounted devices
                Process getDeviceName = Runtime.getRuntime().exec("/bin/mount | grep '^" + devicePrefix + "' | awk '{print $1}'");
                // split the output into a String array
                String[] deviceNames = getDeviceName.getOutputStream().toString().split("\n");
                // sort the array
                Arrays.sort(deviceNames);
                // now just grab the last one in the array
                String lastDeviceName = deviceNames[deviceNames.length - 1];
                // make sure it starts with /dev/sd*, otherwise we have no EBS mounts present
                if (!lastDeviceName.startsWith(devicePrefix)) {
                    logger.debug("No devices beginning with " + devicePrefix + "* exist, so we should start with some sane default.");
                } else {
                    // now figure out the last letter of /dev/xvd*
                    char lastDeviceNameLastChar = lastDeviceName.substring(-1).charAt(0);
                    int lastCharNumericValue = Character.getNumericValue(lastDeviceNameLastChar);
                    // if we don't have a value greater than "f" we should ignore it
                    if (lastCharNumericValue < Character.getNumericValue('f') ){
                        logger.debug("Something is mounted at " + devicePrefix + "[a-e] but this won't map properly, so let's just go ahead and use 'f'.");
                    } else {
                        // our next device name in the sequence would be the next letter in the alphabet
                        // for Amazon request, we should mount to /dev/sd*, even though locally we want /dev/xvd*
                        nextDeviceName = devicePrefix + Character.forDigit(lastCharNumericValue + 1, 16);
                    }
                }

            } catch (Exception e){
                logger.error("Error figuring out device name", e);
                Throwables.propagate(e);
            }

            logger.info("Attaching volume {} on {}", volume, nextDeviceName);

            AttachVolumeRequest attachVolumeRequest = new AttachVolumeRequest()
                    .withVolumeId(volume.getVolumeId())
                    .withInstanceId(instanceIdentity.getInstance().getInstanceId())
                    .withDevice(nextDeviceName);
            AttachVolumeResult volumeAttachment = ec2client.attachVolume(attachVolumeRequest);

            String attachmentStatus = volumeAttachment.getAttachment().getState();

            while ("attaching".equals(attachmentStatus)) {
                try {

                    List<Volume> attachmentVolumes = ec2client.describeVolumes().withVolumes(volume).getVolumes();
                    // ensure our List is not null, not empty, and that getAttachments().get(0) doesn't throw an Index Out of Bounds exception
                    if (null != attachmentVolumes && attachmentVolumes.size() > 0
                            && null != attachmentVolumes.get(0).getAttachments() && attachmentVolumes.get(0).getAttachments().size() > 0) {
                        // seem weird? it is. You can't have more than 1 attachment, but Amazon provides a List instead of an Attachment object for some reason
                        attachmentStatus = attachmentVolumes.get(0).getAttachments().get(0).getState();
                    }

                    logger.info("Attachment status: " + attachmentStatus);

                    if ("error".equals(attachmentStatus)){
                        logger.error("Error attaching EBS volume.");
                        break;
                    }

                    logger.info("Waiting for attachment...");
                    Thread.sleep(AWS_API_POLL_INTERVAL);

                } catch (InterruptedException e) {
                    logger.info("Failed to attach volume {}.", volume);
                    Throwables.propagate(e);
                }
            }

            if ("attached".equals(attachmentStatus)) {
                logger.info("Attached successfully on {}", volume.getAttachments().get(0).getDevice());
            }
        }
    }

    public void ebsMountAndAttach() {

        logger.info("Someone called ebsMountAndAttach!");
        List<Volume> ebsVolumes = getEbsVolumes();

        // if an EBS volume is already mounted, we don't need to do anything here
        if (isEbsAttached()) {
            logger.info("EBS is already attached, so we just need to mount it.");
            mountVolume(ebsVolumes);
            return;
        }

        // locate any EBS volumes that should be attached
        // if we find an available volume matching our criteria, attach those volumes and mount them
        if (ebsVolumes.size() > 0) {

            for (Volume vol : ebsVolumes) {

                attachVolume(vol);
                mountVolume(vol);

            }

        } else { // never found an available volume to attach/reattach, so let's create one and then attach it

            logger.info("Never found a volume to attach, so creating one...");

            // figure out what disk size we need
            // @TODO: don't hard code this :(
            long raidSize = new File("/mnt/ephemeral").getTotalSpace();

            // convert from bytes to gigabytes
            long volSize = raidSize / 1024 / 1024 / 1024;

            logger.info("Create volume with size (GB): " + volSize);

            CreateVolumeRequest createVolumeRequest = new CreateVolumeRequest()
                    .withSize((int) volSize) // casting long to integer like this *should* be safe since we'll never have a drive with a number of gigabytes bigger than Int MaxValue
                    .withAvailabilityZone(amazonConfiguration.getAvailabilityZone());
            CreateVolumeResult createVolumeResult = ec2client.createVolume(createVolumeRequest);

            String createState = "";

            logger.info("Creating volume... {}", createVolumeResult.getVolume());

            while ("creating".equals(createState)) {
                try {

                    // this is because Amazon provides a List of volumes, even if the list is empty
                    List<Volume> createdVolumes = ec2client.describeVolumes().withVolumes(createVolumeResult.getVolume()).getVolumes();
                    if (null != createdVolumes && createdVolumes.size() > 0) {
                        createState = createdVolumes.get(0).getState();
                    }

//                    createState = ec2client.describeVolumes()
//                            .withVolumes(createVolumeResult.getVolume())
//                            .getVolumes().get(0).getState();

                    logger.info("State: " + createVolumeResult.getVolume().getState());
                    logger.info("Real state: " + getEbsVolumes().get(0).getState());
                    Thread.sleep(AWS_API_POLL_INTERVAL);
                } catch (InterruptedException e){
                    logger.info("Failed to create volume: " + e.getMessage());
                    Throwables.propagate(e);
                }
            }

            if ("created".equals(getEbsVolumes().get(0).getState())) {
                logger.info("Successfully created EBS volume {}", getEbsVolumes().get(0));
            }

            // tag the volume
            CreateTagsRequest createTagsRequest = new CreateTagsRequest()
                    .withTags(new Tag()
                            .withKey("Name")
                            .withValue(cassandraConfiguration.getClusterName() + "-" + instanceIdentity.getInstance().getToken()))
                    .withResources(createVolumeResult.getVolume().getVolumeId());

            ec2client.createTags(createTagsRequest);

            // attach the volume
            attachVolume(createVolumeResult.getVolume());

        }
    }

    // "download" means copy from EBS volume to ephemeral disk
    @Override
    public void download(AbstractBackupPath path, OutputStream outputStream) throws BackupRestoreException {
        try {
            //logger.info("Downloading " + path.getRemotePath());
            downloadCount.incrementAndGet();

            // copy from ebs to ephemeral
            File ebsPath = new File(path.getRemotePath());
            File ephemeralPath = new File(getPrefix());
            FileUtils.copyDirectory(ebsPath, ephemeralPath);

            bytesDownloaded.addAndGet(FileUtils.sizeOfDirectory(ebsPath));
        } catch (Exception e) {
            throw new BackupRestoreException(e.getMessage(), e);
        }
    }

    // "upload" means copy from ephemeral disk to EBS volume
    @Override
    public void upload(AbstractBackupPath path, InputStream in) throws BackupRestoreException {

        try {
            uploadCount.incrementAndGet();

            // copy from ephemeral to EBS volume
            File ebsPath = new File(path.getRemotePath());
            File ephemeralPath = new File(getPrefix());
            FileUtils.copyDirectory(ephemeralPath, ebsPath);

            bytesUploaded.addAndGet(FileUtils.sizeOfDirectory(ephemeralPath));

        } catch (Exception e) {
            throw new BackupRestoreException(e.getMessage(), e);
        }

    }

    @Override
    public int getActivecount() {
        return 0;
    }

    @Override
    public Iterator<AbstractBackupPath> list(String path, Date start, Date till) {
        return new EBSFileIterator(pathProvider, path, start, till);
    }

    @Override
    public Iterator<AbstractBackupPath> listPrefixes(Date date) {
        return new EBSPrefixIterator(cassandraConfiguration, amazonConfiguration, backupConfiguration, pathProvider, date);
    }

    /**
     * @TODO: We should cleanup our own EBS snapshots
     */
    @Override
    public void cleanup() {
        // noop
    }

    /**
     * Get prefix which will be used to locate files on EBS volume
     */
    public String getPrefix() {
        String prefix = "";
        if (StringUtils.isNotBlank(backupConfiguration.getRestorePrefix())) {
            prefix = backupConfiguration.getRestorePrefix();
        }

        String[] paths = prefix.split(String.valueOf(EBSBackupPath.PATH_SEP));
        return paths[0];
    }

    @Override
    public int downloadCount() {
        return downloadCount.get();
    }

    @Override
    public int uploadCount() {
        return uploadCount.get();
    }

    @Override
    public long bytesUploaded() {
        return bytesUploaded.get();
    }

    @Override
    public long bytesDownloaded() {
        return bytesDownloaded.get();
    }

}
