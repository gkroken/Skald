/*
 * Skald
 *
 * Copyright (C) 2026 Gard Kroken
 *
 * Built on ShinyProxy, Copyright (C) 2016-2026 Open Analytics NV.
 *
 * ===========================================================================
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License as published by
 * The Apache Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Apache License for more details.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/>
 */
package eu.openanalytics.shinyproxy.publisher.storage;

import org.testcontainers.containers.GenericContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * A started MinIO with one bucket and the real {@link S3ObjectStore} over it, for tests
 * outside this package that need a log store (the build driver's).
 */
public final class TestObjectStore implements AutoCloseable {

    public static final String ACCESS_KEY = "skald";
    public static final String SECRET_KEY = "skaldskald";

    public final String bucket;
    public final ObjectStore store;
    private final GenericContainer<?> minio;
    private final String external;
    private final S3Client client;

    public TestObjectStore(String bucket) {
        this(bucket, null);
    }

    /**
     * A store at {@code endpoint}, started by someone else with these credentials, or a new
     * MinIO container when it is null. The T7 pass probe's restart rows need the first: the
     * store must outlive the JVM that is killed, so the probe runs it, not Testcontainers.
     * The bucket may already exist (the second JVM finds the first one's).
     */
    public TestObjectStore(String bucket, String endpoint) {
        this.bucket = bucket;
        external = endpoint;
        minio = endpoint == null ? MinioTestContainer.create(ACCESS_KEY, SECRET_KEY) : null;
        if (minio != null) {
            minio.start();
        }
        client = S3Client.builder()
                .endpointOverride(java.net.URI.create(endpoint()))
                .region(Region.of("us-east-1"))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .httpClient(UrlConnectionHttpClient.create())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        createBucket(bucket);
        store = new S3ObjectStore(client);
    }

    /** The store's S3 endpoint, for a test that configures the application against it. */
    public String endpoint() {
        return external != null ? external : "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
    }

    /** Another bucket in the same store (the application keeps bundles and logs apart). */
    public void createBucket(String name) {
        try {
            client.createBucket(CreateBucketRequest.builder().bucket(name).build());
        } catch (software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException mine) {
            if (external == null) {
                throw mine;
            }
        }
    }

    @Override
    public void close() {
        client.close();
        if (minio != null) {
            minio.stop();
        }
    }
}
