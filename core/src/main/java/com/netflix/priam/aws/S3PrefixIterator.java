package com.netflix.priam.aws;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.netflix.priam.backup.AbstractBackupPath;
import com.netflix.priam.config.AmazonConfiguration;
import com.netflix.priam.config.BackupConfiguration;
import com.netflix.priam.config.CassandraConfiguration;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

/**
 * Class to iterate over prefixes (S3 Common prefixes) up to
 * the token element in the path. The abstract path generated by this class
 * is partial (does not have all data).
 */
public class S3PrefixIterator implements Iterator<AbstractBackupPath> {
    private static final Logger logger = LoggerFactory.getLogger(S3PrefixIterator.class);
    private final CassandraConfiguration cassandraConfiguration;
    private final AmazonConfiguration amazonConfiguration;
    private final BackupConfiguration backupConfiguration;
    private final AmazonS3 s3Client;
    private final Provider<AbstractBackupPath> pathProvider;
    private Iterator<AbstractBackupPath> iterator;

    private String bucket = "";
    private String clusterPath = "";
    private final SimpleDateFormat datefmt = new SimpleDateFormat("yyyyMMdd");
    private ObjectListing objectListing;
    private final Date date;

    @Inject
    public S3PrefixIterator(CassandraConfiguration cassandraConfiguration, AmazonConfiguration amazonConfiguration, BackupConfiguration backupConfiguration, Provider<AbstractBackupPath> pathProvider, AmazonS3 s3Client, Date date) {
        this.cassandraConfiguration = cassandraConfiguration;
        this.amazonConfiguration = amazonConfiguration;
        this.backupConfiguration = backupConfiguration;
        this.pathProvider = pathProvider;
        this.s3Client = s3Client;
        this.date = date;
        String path;
        if (StringUtils.isNotBlank(backupConfiguration.getRestorePrefix())) {
            path = backupConfiguration.getRestorePrefix();
        } else {
            path = backupConfiguration.getS3BucketName();
        }

        String[] paths = path.split(String.valueOf(S3BackupPath.PATH_SEP));
        bucket = paths[0];
        this.clusterPath = remotePrefix(path);
        objectListing = null;
        iterator = createIterator();
    }

    private void initListing() {
        ListObjectsRequest listReq = new ListObjectsRequest();
        // Get list of tokens
        listReq.setBucketName(bucket);
        listReq.setPrefix(clusterPath);
        listReq.setDelimiter(String.valueOf(AbstractBackupPath.PATH_SEP));
        logger.info("Using cluster prefix for searching tokens: {}", clusterPath);
        objectListing = s3Client.listObjects(listReq);

    }

    private Iterator<AbstractBackupPath> createIterator() {
        if (objectListing == null) {
            initListing();
        }
        List<AbstractBackupPath> temp = Lists.newArrayList();
        for (String summary : objectListing.getCommonPrefixes()) {
            if (pathExistsForDate(summary, datefmt.format(date))) {
                AbstractBackupPath path = pathProvider.get();
                path.parsePartialPrefix(summary);
                temp.add(path);
            }
        }
        return temp.iterator();
    }

    @Override
    public boolean hasNext() {
        if (iterator.hasNext()) {
            return true;
        } else {
            while (objectListing.isTruncated() && !iterator.hasNext()) {
                objectListing = s3Client.listNextBatchOfObjects(objectListing);
                iterator = createIterator();
            }
        }
        return iterator.hasNext();
    }

    @Override
    public AbstractBackupPath next() {
        return iterator.next();
    }

    @Override
    public void remove() {
    }

    /**
     * Get remote prefix up to the token
     */
    private String remotePrefix(String location) {
        StringBuilder buff = new StringBuilder();
        String[] elements = location.split(String.valueOf(S3BackupPath.PATH_SEP));
        if (elements.length <= 1) {
            buff.append(backupConfiguration.getS3BaseDir()).append(S3BackupPath.PATH_SEP);
            buff.append(amazonConfiguration.getRegionName()).append(S3BackupPath.PATH_SEP);
            buff.append(cassandraConfiguration.getClusterName()).append(S3BackupPath.PATH_SEP);
        } else {
            assert elements.length >= 4 : "Too few elements in path " + location;
            buff.append(elements[1]).append(S3BackupPath.PATH_SEP);
            buff.append(elements[2]).append(S3BackupPath.PATH_SEP);
            buff.append(elements[3]).append(S3BackupPath.PATH_SEP);
        }
        return buff.toString();
    }

    /**
     * Check to see if the path exists for the date
     */
    private boolean pathExistsForDate(String tprefix, String datestr) {
        // Get list of tokens
        ListObjectsRequest listReq = new ListObjectsRequest();
        listReq.setBucketName(bucket);
        listReq.setPrefix(tprefix + datestr);
        ObjectListing listing = s3Client.listObjects(listReq);
        return listing.getObjectSummaries().size() > 0;
    }

}
