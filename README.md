# Endpoint S3 SCM Plugin

<!-- Badges -->
[![Jenkins Plugin](https://img.shields.io/badge/Jenkins-Plugin-blue?logo=jenkins)](https://plugins.jenkins.io/jenkins-endpoint-s3-scm)
[![GitHub Release](https://img.shields.io/github/v/release/msorokin-hash/jenkins-endpoint-s3-scm?logo=github)](https://github.com/msorokin-hash/jenkins-endpoint-s3-scm/releases)
![Java 17+](https://img.shields.io/badge/Java-17%2B-orange)
![Jenkins 2.361+](https://img.shields.io/badge/Jenkins-2.361%2B-blue?logo=jenkins)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)
![LGPL v3 License](https://img.shields.io/badge/License-LGPL%20v3-blue.svg)
[![Issues](https://img.shields.io/github/issues/msorokin-hash/jenkins-endpoint-s3-scm)](https://github.com/msorokin-hash/jenkins-endpoint-s3-scm/issues)

---

## Overview

The **Endpoint S3 SCM Plugin** allows Jenkins jobs to fetch source files or build artifacts from **ZIP archives stored
in S3-compatible object storage**.  
Instead of cloning repositories such as Git or Subversion, this plugin downloads a ZIP file from an S3 endpoint (AWS S3,
MinIO, Ceph, or any S3-compatible service), validates the archive, and safely extracts its contents into the Jenkins
workspace.

This SCM is ideal for environments where:

- Code or build inputs are delivered as archive bundles
- Network restrictions prevent using traditional SCM systems
- Builds depend on pre-packaged artifacts stored in S3
- On-premises S3-compatible systems (MinIO, Ceph RGW, etc.) are used

The plugin integrates with any Jenkins job type supporting custom SCM definitions.

---

## Features

- **S3-Compatible Storage Support**  
  Works with AWS S3, MinIO, Ceph, and custom S3 endpoints.

- **ZIP Archive Checkout**  
  Downloads `.zip` archives and extracts them directly into the workspace.

- **Credential-Based Authentication**  
  Uses Jenkins Credentials Store:
    - Username → Access Key
    - Password → Secret Key

- **Object Metadata Validation**  
  Uses a `HEAD` request to verify the object exists and check its size before downloading.

- **Secure ZIP Extraction**  
  Protects against invalid ZIP structures and path traversal vulnerabilities.

- **Optional Top-Level Directory Stripping**  
  Removes the first directory level inside the archive if needed.

- **Configurable Retry Logic**  
  Customizable retry count and delay for unstable networks.

- **Workspace Cleanup on Failure**  
  Prevents partial file extractions from remaining after errors.

- **Environment Variable Injection**  
  Makes S3 settings available to build steps:
    - `ENDPOINT_S3_ENDPOINT`
    - `ENDPOINT_S3_BUCKET`
    - `ENDPOINT_S3_KEY`
    - `ENDPOINT_S3_REGION`
    - `ENDPOINT_S3_CREDENTIALS_ID`

---

## Limitations

- **Polling Not Supported**  
  The plugin cannot detect changes in S3 buckets; builds must be triggered manually or by an external event.

- **ZIP-Only Support**  
  Other archive formats (e.g., TAR, TGZ) are not supported.

- **Maximum Object Size: 1 GB**  
  Larger archives are rejected for performance and safety reasons.

- **Access Key / Secret Key Only**  
  IAM roles, STS AssumeRole, and other AWS authentication methods are not supported.

- **No Revision Tracking**  
  S3 does not provide commit or version metadata; the plugin always performs a full checkout.

- **No Incremental Deltas**  
  The entire ZIP archive is downloaded for every build.

- **SCM API Restrictions**  
  No branches, tags, or commit browsing features are available.

---

## Usage

### Freestyle Jobs

In Freestyle jobs, select **Source Code Management → Endpoint S3 SCM**, then provide:

- **Endpoint URL** – S3-compatible endpoint
- **Bucket** – name of the S3 bucket
- **Key** – path to the ZIP archive
- **Credentials** – Jenkins Username/Password credentials (username = access key, password = secret key)
- **Region** – optional AWS region
- **Strip Top-Level Directory** – remove the root directory inside the ZIP

After saving, the plugin will download and extract the archive during the `Checkout` phase.

---

## Pipeline Example (Declarative)

Below is a minimal working Pipeline configuration:

```groovy
pipeline {
    agent any

    stages {
        stage('Checkout from S3') {
            steps {
                checkout([$class          : 'EndpointS3SCM',
                          endpoint        : 'https://minio.example.com',
                          bucket          : 'my-artifacts',
                          key             : 'builds/project.zip',
                          credentialsId   : 'minio-creds',
                          region          : 'us-east-1',
                          maxRetries      : 3,
                          retryDelayMs    : 1000,
                          stripTopLevelDir: true
                ])
            }
        }

        stage('Exec script') {
            steps {
                sh 'ls -la'
                sh 'find . -type f | sort'
            }
        }
    }
}
```

---

## Contributing

Pull requests are welcome!  
Please include tests where appropriate and follow standard Jenkins plugin development conventions.

---

## License

This project is licensed under the **GNU Lesser General Public License v3 (LGPL-3.0)**.  
See the **LICENSE** file for the full license text.