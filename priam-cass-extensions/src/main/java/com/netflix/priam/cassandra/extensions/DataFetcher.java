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
package com.netflix.priam.cassandra.extensions;

import com.google.common.base.Charsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

public class DataFetcher {
    private static final Logger logger = LoggerFactory.getLogger(DataFetcher.class);

    public static String fetchData(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) {
                throw new RuntimeException("Unexpected HTTP response " + conn.getResponseCode() + " for URL: " + url);
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (DataInputStream in = new DataInputStream((FilterInputStream) conn.getContent())) {
                byte[] b = new byte[2048];
                int len;
                while ((len = in.read(b, 0, b.length)) != -1) {
                    bos.write(b, 0, len);
                }
            }
            conn.disconnect();
            String result = new String(bos.toByteArray(), Charsets.UTF_8);
            logger.info("Calling URL API: {} returns: {}", url, result);
            return result;

        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }
}
