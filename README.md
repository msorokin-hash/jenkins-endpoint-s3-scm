# Endpoint S3 SCM Plugin

<!-- Badges -->
[![Jenkins Plugin](https://img.shields.io/badge/Jenkins-Plugin-blue?logo=jenkins)](https://plugins.jenkins.io/jenkins-endpoint-s3-scm)
[![GitHub Release](https://img.shields.io/github/v/release/msorokin-hash/jenkins-endpoint-s3-scm?logo=github)](https://github.com/msorokin-hash/jenkins-endpoint-s3-scm/releases)
![Java 17+](https://img.shields.io/badge/Java-17%2B-orange)
![Jenkins 2.361+](https://img.shields.io/badge/Jenkins-2.361%2B-blue?logo=jenkins)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)
![LGPL v3 License](https://img.shields.io/badge/License-LGPL%20v3-blue.svg)

---

## Overview

The **Endpoint S3 SCM Plugin** allows Jenkins to fetch build inputs from  
**ZIP archives stored in S3-compatible object storage**.

Instead of cloning repositories (Git, SVN), the plugin:

1. Searches a configured **S3 prefix**
2. Finds the **latest ZIP archive**
3. Downloads it with retry logic
4. Safely extracts it into the workspace

---

## Features

- ✅ S3-compatible storage (AWS S3, MinIO, Ceph, LocalStack)
- ✅ Latest ZIP auto-detection (by lastModified)
- ✅ Multi-source failover (priority-based)
- ✅ Retry logic with backoff
- ✅ Secure ZIP extraction (Zip Slip protection)
- ✅ Optional top-level directory stripping
- ✅ Workspace cleanup on failure
- ✅ Environment variables export

---

## How It Works

1. Locations are sorted by priority (lower = first)
2. For each location:
    - List objects in prefix
    - Filter `.zip`
    - Select latest
3. Download archive
4. Validate ZIP
5. Extract into workspace
6. Stop on first success

---

## Pipeline Usage

### Single Source

```groovy
checkout([$class: 'EndpointS3SCM',
    prefix: 'myapp/releases/',
    locations: [[
        name: 'primary',
        endpoint: 'http://minio.internal:9000',
        bucket: 'jenkins-pipelines',
        credentialsId: 'minio-access',
        region: 'us-east-1',
        priority: 10
    ]],
    stripTopLevelDir: true
])
```

---

### Multi-Source (Failover)

```groovy
checkout([$class: 'EndpointS3SCM',
    prefix: 'prod/deploy/',
    locations: [
        [
            name: 'primary',
            endpoint: 'https://s3-primary.example.com',
            bucket: 'pipelines-primary',
            credentialsId: 'creds-primary',
            priority: 10
        ],
        [
            name: 'backup',
            endpoint: 'https://s3-backup.example.com',
            bucket: 'pipelines-backup',
            credentialsId: 'creds-backup',
            priority: 20
        ]
    ],
    maxRetries: 5,
    retryDelayMs: 2000
])
```

---

## Parameters

### Root

| Parameter | Description | Required |
|----------|------------|----------|
| prefix | S3 prefix to search ZIP files | ✅ |
| locations | List of sources | ✅ |
| stripTopLevelDir | Remove root folder | ❌ |
| maxRetries | Retry attempts | ❌ |
| retryDelayMs | Delay between retries | ❌ |

---

### Location

| Parameter | Description | Required |
|----------|------------|----------|
| name | Optional name | ❌ |
| endpoint | S3 endpoint URL | ✅ |
| bucket | Bucket name | ✅ |
| credentialsId | Jenkins credentials | ✅ |
| region | AWS region | ❌ |
| priority | Lower = higher priority | ❌ |

---

## Environment Variables

```
ENDPOINT_S3_PREFIX
ENDPOINT_S3_LOCATIONS_COUNT
ENDPOINT_S3_LOCATION_0_NAME
ENDPOINT_S3_LOCATION_0_ENDPOINT
ENDPOINT_S3_LOCATION_0_BUCKET
ENDPOINT_S3_LOCATION_0_REGION
ENDPOINT_S3_LOCATION_0_CREDENTIALS_ID
ENDPOINT_S3_LOCATION_0_PRIORITY
```

---

## Limitations

- ❌ No polling support
- ❌ No changelog
- ❌ ZIP only
- ❌ No IAM roles
- ❌ Max archive size: 1GB

---

## Supported Storage

| Provider | Example |
|--------|--------|
| AWS S3 | https://s3.amazonaws.com |
| MinIO | http://minio:9000 |
| Ceph | http://ceph:8080 |
| LocalStack | http://localhost:4566 |

---

## Troubleshooting

| Error | Cause | Solution |
|------|------|--------|
| No ZIP files found | Empty prefix | Check uploads |
| AccessDenied | Bad credentials | Fix permissions |
| Archive too large | >1GB | Reduce size |
| Jenkinsfile missing | Wrong ZIP structure | Fix archive |

---

## License

LGPL v3