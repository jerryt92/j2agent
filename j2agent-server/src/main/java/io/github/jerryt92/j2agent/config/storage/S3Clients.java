package io.github.jerryt92.j2agent.config.storage;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

record S3Clients(S3Client s3Client, S3Presigner presigner) {
}
