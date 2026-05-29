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

1. Resolves the **Directory / Prefix** field (expanding Jenkins environment variables)
2. Downloads the target ZIP archive with retry logic
3. Verifies the archive checksum (optional, enabled by default)
4. Safely extracts it into the workspace (or a target subdirectory)
5. Attaches checkout metadata to the build as environment variables

The **Directory / Prefix** field supports two operating modes, selected automatically:

| Mode | Example value | Behaviour |
|---|---|---|
| **Prefix mode** | `releases/backend` | Lists objects under the prefix, picks the latest ZIP by `lastModified` |
| **Exact ZIP mode** | `releases/backend/app-1.0.zip` | Downloads the specific object directly without listing |

Jenkins environment variables are expanded before the mode is determined:

```
releases/${BRANCH_NAME}/      → prefix mode  (picks latest ZIP under the branch prefix)
releases/${BRANCH_NAME}.zip   → exact mode   (downloads that specific file)
```

S3 source locations are resolved using a **cascading fallback** strategy:

1. Sources from the nearest ancestor folder's **Endpoint S3 SCM Settings** are tried first
2. If every folder source fails, sources from the **global fallback** (Manage Jenkins → System) are tried automatically

This means folder-level and global-level sources can coexist and complement each other.
Each job specifies only the directory prefix or exact key to use.

---

## Features

- ✅ S3-compatible storage (AWS S3, MinIO, Ceph, LocalStack)
- ✅ **Prefix mode** — latest ZIP auto-detection by `lastModified`
- ✅ **Exact ZIP mode** — download a specific archive by full S3 key
- ✅ **Jenkins environment variable expansion** — `releases/${BRANCH_NAME}.zip` resolved at checkout time
- ✅ **Polling / change detection** — Jenkins can poll for new uploads without a full checkout; works in both prefix and exact modes
- ✅ **Target directory** — extract ZIP into a workspace subdirectory instead of the workspace root
- ✅ **Post-checkout metadata** — artifact key, bucket, source, size and timestamp exported as build env vars and shown on the build page
- ✅ **Folder-scoped configuration** — different teams use different buckets
- ✅ Nearest-ancestor inheritance — nested folders override parents
- ✅ **Cascading fallback** — folder sources tried first; global sources tried automatically if all folder sources fail
- ✅ **Global Shared Library support** — libraries loaded in a folder job's context fall through to the global endpoint automatically
- ✅ Multi-source failover (priority-based)
- ✅ Retry logic with configurable delay
- ✅ SHA-256 / SHA-512 checksum verification
- ✅ Secure ZIP extraction (Zip Slip and Zip Bomb protection)
- ✅ Optional top-level directory stripping
- ✅ Workspace cleanup on failure

---

## Configuration

Sources are resolved using a **cascading fallback**: folder sources are tried first; global sources are tried automatically if all folder sources fail.
Both levels can be configured simultaneously — they complement rather than exclude each other.

---

### Folder-level settings (recommended)

S3 source locations and checkout behaviour are configured **per folder**:

1. Open a Jenkins folder → **Configure**
2. Find the **Endpoint S3 SCM Settings** section
3. Add one or more S3 locations and adjust checkout options

Jobs inside the folder automatically inherit these settings.
A nested folder can define its own settings — for folder-to-folder inheritance the nearest folder wins (no merging across folders).
Global sources are still tried as a cascading fallback even when a folder property is present.

```
Root Folder  (S3: endpoint-A, bucket-team-a)
 ├── Job A   → tries endpoint-A/bucket-team-a first, then global fallback
 ├── Job B   → tries endpoint-A/bucket-team-a first, then global fallback
 └── Team B Folder  (S3: endpoint-B, bucket-team-b)
      ├── Job C  → tries endpoint-B/bucket-team-b first, then global fallback
      └── Job D  → tries endpoint-B/bucket-team-b first, then global fallback
```

---

### Global fallback settings

Global sources are appended to the source list **after** folder sources and serve as a
cascading fallback for two scenarios:

- **Root-level jobs** — jobs with no parent folder that has **Endpoint S3 SCM Settings**
- **Global Shared Libraries** — a library loaded inside a folder job's context uses
  the folder sources first; if the library archive is not found there (because it lives in a
  different bucket), the plugin automatically falls through to the global sources

To configure:

1. Go to **Manage Jenkins → System**
2. Find the **Endpoint S3 SCM — Global Settings** section
3. Add one or more S3 locations and adjust checkout options

**Typical Shared Library setup:**

```
Folder job (prefix = myapp/releases/)          Global settings (bucket = shared-libs)
  S3 source: endpoint-A / bucket-jobs             S3 source: endpoint-A / bucket-shared
```

```
Pipeline checkout  (prefix=myapp/releases/):
  → tries endpoint-A/bucket-jobs  → myapp/releases/app.zip found  ✅

Shared Library  (prefix=shared/):
  → tries endpoint-A/bucket-jobs  → no shared/*.zip              ↓ fallback
  → tries endpoint-A/bucket-shared → shared/s3lib.zip found      ✅
```

If neither folder nor global sources are configured, the build aborts with a clear error message.

---

#### S3 Location fields

| Field | Description | Required |
|---|---|---|
| Name | Human-readable label (shown in logs and errors) | ❌ |
| Endpoint | Full S3-compatible endpoint URL | ✅ |
| Bucket | Bucket name | ✅ |
| Credentials | Jenkins `Username/Password` credentials (access key / secret key) | ✅ |
| Region | AWS region (leave blank for `us-east-1`) | ❌ |
| Priority | Lower number = tried first during failover | ❌ (`100`) |

Sources are tried in ascending priority order. The first successful download wins.

#### Checkout Settings

| Setting | Description | Default |
|---|---|---|
| Strip top-level directory | Remove the common root folder from the archive | `true` |
| Max retries | Download retry attempts per source | `3` |
| Retry delay (ms) | Base delay between retries | `1000` |
| Skip checksum verification | Disable `.sha512` / `.sha256` integrity check | `false` |

---

### Job / Pipeline settings

Each job specifies the **Directory / Prefix** field and, optionally, a **Target directory**.
The plugin supports two modes selected automatically based on the value after environment
variable expansion:

#### Prefix mode

The value does **not** end with `.zip`. The plugin lists all objects under the prefix
and downloads the one with the latest `lastModified` timestamp.

```
releases/backend
myteam/artifacts/
```

#### Exact ZIP mode

The value ends with `.zip`. The plugin resolves the object metadata with a single HEAD
request and downloads that specific file — no listing is performed.

```
releases/backend/app-1.0.zip
shared/mylib-2.3.0.zip
```

#### Jenkins environment variable expansion

Both modes support `${VAR}` references. Variables are expanded at checkout time using the
build's environment. If a variable is not set the placeholder is left as-is.

```
releases/${BRANCH_NAME}/           → prefix mode  (latest ZIP under the branch prefix)
releases/${BRANCH_NAME}.zip        → exact mode   (specific branch artifact)
builds/${ENVIRONMENT}/backend.zip  → exact mode   (environment-specific artifact)
shared/${LIB_VERSION}.zip          → exact mode   (pinned library version)
```

The expanded value is logged in the build output:

```
[EndpointS3SCM] Expanded prefix: 'releases/${BRANCH_NAME}.zip' → 'releases/main.zip'
[EndpointS3SCM] Exact ZIP mode: key='releases/main.zip'
```

#### Target directory

When **Target directory** is set, the archive is extracted into a subdirectory of the
workspace instead of the workspace root. Useful when multiple `checkout` steps run in the
same workspace or when the extracted content should be isolated.

```
Target directory: backend
  → extracts into <workspace>/backend/
```

Leave empty to extract directly into the workspace root (the default).

In a Pipeline:

```groovy
checkout([$class: 'EndpointS3SCM',
    prefix: 'releases/backend/',
    targetDirectory: 'backend'
])
```

---

## How It Works

1. The plugin walks up the job's parent folder chain to find the **nearest** folder
   that has **Endpoint S3 SCM Settings** configured.
2. The plugin builds a **merged source list**:
   - First: all sources from the nearest folder property (sorted by priority)
   - Then: all sources from **Manage Jenkins → System** global settings (sorted by priority)
3. Checkout settings (`maxRetries`, `retryDelayMs`, `stripTopLevelDir`, `skipChecksumVerification`)
   are taken from the folder property if it has any sources; otherwise from global settings.
4. If the merged list is empty, the build aborts with a clear error message.
5. The **Directory / Prefix** value is expanded: Jenkins environment variable references
   (`${VAR}`) are resolved using the current build's environment.
6. The operating mode is determined from the expanded value:
   - Ends with `.zip` → **Exact ZIP mode**
   - Otherwise → **Prefix mode**
7. For each source (folder sources first, then global sources):
   1. **Prefix mode**: list objects in the bucket under the prefix, filter `.zip` files,
      select the one with the latest `lastModified` timestamp
      **Exact ZIP mode**: issue a HEAD request to confirm the object exists and read its size
   2. Abort this source if the archive exceeds 1 GB
   3. Download with retry logic
   4. Verify checksum against the companion `.sha512` or `.sha256` file in S3 (if enabled)
   5. Validate ZIP structure
   6. Extract into the workspace (or `targetDirectory` subdirectory if set)
8. Stop on first success; on source failure, try the next source in the merged list
9. After a successful checkout, attach an **`S3CheckoutAction`** to the build:
   - Exports post-checkout metadata as environment variables available to subsequent steps
   - Displays an artifact summary on the build page (key, bucket, size, timestamp, mode)
   - Stores the revision state used by Jenkins for **polling**
10. If all sources fail, clean up the workspace and throw `AbortException`

---

## Polling

The plugin supports Jenkins' built-in **SCM polling** (`Poll SCM` build trigger).

When polled, the plugin checks whether the S3 object has changed since the last
successful build — **without downloading the archive**:

| Mode | Poll mechanism |
|---|---|
| **Prefix mode** | Lists objects under the prefix; compares the latest ZIP key and `lastModified` timestamp against the stored revision |
| **Exact ZIP mode** | Issues a single HEAD request; compares the `lastModified` timestamp |

A new build is triggered only when a change is detected (`SIGNIFICANT`).
If the source is temporarily unreachable the poll result is `NO_CHANGES` (conservative —
avoids spurious builds on transient errors).

Enable polling in the job configuration:

```
Build Triggers → Poll SCM → Schedule: H/5 * * * *
```

Or in a Pipeline `Jenkinsfile`:

```groovy
triggers {
    pollSCM('H/5 * * * *')
}
```

The revision state (`bucket`, `key`, `lastModified`) is stored in `build.xml` as part of
the build record. Polling always compares against the last successful build.

---

## Checksum Verification

When enabled (the default), the plugin looks for a companion file next to the archive:

```
releases/app-1.0.zip          ← archive
releases/app-1.0.zip.sha512   ← preferred
releases/app-1.0.zip.sha256   ← fallback
```

SHA-512 is preferred over SHA-256. At least one companion file must exist; if neither
is found, the build is aborted.

The companion file content is parsed in three formats:

| Format | Example |
|---|---|
| Raw hex | `a3f2...` |
| GNU (`sha256sum`) | `a3f2...  app-1.0.zip` |
| BSD (`shasum -a 256`) | `SHA256 (app-1.0.zip) = a3f2...` |

Checksum verification can be disabled in the **Checkout Settings** section of either the
folder configuration or the global fallback.

---

## Pipeline Usage

### Prefix mode — latest ZIP

```groovy
pipeline {
    agent any
    stages {
        stage('Checkout') {
            steps {
                checkout([$class: 'EndpointS3SCM',
                    prefix: 'myapp/releases/'
                ])
            }
        }
    }
}
```

### Exact ZIP mode — specific artifact

```groovy
node {
    checkout([$class: 'EndpointS3SCM',
        prefix: 'prod/deploy/backend-2.4.1.zip'
    ])
}
```

### Environment variable expansion

```groovy
pipeline {
    agent any
    stages {
        stage('Checkout') {
            steps {
                // Picks the latest ZIP under the branch-specific prefix
                checkout([$class: 'EndpointS3SCM',
                    prefix: 'releases/${BRANCH_NAME}/'
                ])
            }
        }
        stage('Checkout pinned version') {
            steps {
                // Downloads a specific artifact for the current environment
                checkout([$class: 'EndpointS3SCM',
                    prefix: 'builds/${ENVIRONMENT}/backend.zip'
                ])
            }
        }
    }
}
```

### Target directory — isolate into a subdirectory

```groovy
pipeline {
    agent any
    stages {
        stage('Checkout') {
            steps {
                // Extracts into <workspace>/backend/ instead of the workspace root
                checkout([$class: 'EndpointS3SCM',
                    prefix: 'releases/backend/',
                    targetDirectory: 'backend'
                ])
            }
        }
    }
}
```

### Using post-checkout metadata

After a successful `checkout` step the plugin exports artifact metadata that subsequent
stages can reference:

```groovy
pipeline {
    agent any
    stages {
        stage('Checkout') {
            steps {
                checkout([$class: 'EndpointS3SCM',
                    prefix: 'releases/${BRANCH_NAME}/'
                ])
            }
        }
        stage('Deploy') {
            steps {
                // ENDPOINT_S3_KEY, ENDPOINT_S3_BUCKET, etc. are now available
                echo "Downloaded: ${env.ENDPOINT_S3_KEY}"
                echo "From bucket: ${env.ENDPOINT_S3_BUCKET} (source: ${env.ENDPOINT_S3_SOURCE})"
                echo "Size: ${env.ENDPOINT_S3_SIZE} bytes, mode: ${env.ENDPOINT_S3_MODE}"
                sh "./deploy.sh ${env.ENDPOINT_S3_KEY}"
            }
        }
    }
}
```

### Polling trigger

```groovy
pipeline {
    agent any
    triggers {
        pollSCM('H/5 * * * *')   // check for new uploads every ~5 minutes
    }
    stages {
        stage('Checkout') {
            steps {
                checkout([$class: 'EndpointS3SCM',
                    prefix: 'releases/backend/'
                ])
            }
        }
    }
}
```

The `prefix` value is the only required job-level parameter.
All other settings (sources, retries, checksum, strip directory)
come from the nearest ancestor folder configuration, or from the global fallback
if no folder configuration is found.

---

## Environment Variables

### Pre-checkout (configuration metadata)

Exported at the start of checkout, before any S3 interaction:

```
ENDPOINT_S3_PREFIX                    # job-level prefix template (unexpanded; ${VAR} references preserved)
ENDPOINT_S3_LOCATIONS_COUNT           # number of sources in the resolved configuration
ENDPOINT_S3_LOCATION_0_NAME
ENDPOINT_S3_LOCATION_0_ENDPOINT
ENDPOINT_S3_LOCATION_0_BUCKET
ENDPOINT_S3_LOCATION_0_REGION
ENDPOINT_S3_LOCATION_0_PRIORITY
# …repeated for each source (0, 1, 2, …)
```

The location list reflects the **merged** source list actually used for this job:
folder-level sources first, followed by global fallback sources.

### Post-checkout (artifact metadata)

Exported after a successful download via `S3CheckoutAction`. Available to all subsequent
pipeline stages and build steps:

| Variable | Content | Example |
|---|---|---|
| `ENDPOINT_S3_KEY` | Actual S3 object key downloaded (expanded) | `releases/main/app-2.1.0.zip` |
| `ENDPOINT_S3_BUCKET` | Bucket of the source that succeeded | `my-artifacts` |
| `ENDPOINT_S3_SOURCE` | Name of the S3 location that succeeded | `primary` |
| `ENDPOINT_S3_LAST_MODIFIED` | ISO-8601 last-modified timestamp | `2025-06-01T12:00:00Z` |
| `ENDPOINT_S3_SIZE` | Archive size in bytes | `10485760` |
| `ENDPOINT_S3_MODE` | Download mode | `LATEST` or `EXACT` |

`ENDPOINT_S3_LAST_MODIFIED` is omitted when the timestamp is not available (e.g. certain
S3-compatible backends that do not return `Last-Modified` on HEAD responses).

The same metadata is displayed on the build page under the **S3 Artifact** sidebar entry.

---

## Troubleshooting

| Error | Cause | Solution |
|---|---|---|
| `Directory/prefix is required` | Job has no prefix set | Set prefix in job/pipeline config |
| `At least one S3 source is required...` | No folder in the hierarchy has settings and global settings are empty | Add **Endpoint S3 SCM Settings** to a parent folder or configure the global fallback in **Manage Jenkins → System** |
| `No ZIP files found in prefix` | Prefix mode: no `.zip` objects under the prefix | Check uploads and the prefix value |
| `No ZIP file found at key` | Exact mode: the specified key does not exist in the bucket | Check the full object key and bucket; verify the `${VAR}` variable is set correctly |
| `Archive too large` | Archive exceeds 1 GB | Reduce archive size |
| `No checksum file found` | No `.sha512` or `.sha256` companion file | Upload companion file, or disable verification in settings |
| `Checksum verification FAILED` | Archive content does not match the hash | Re-upload archive and/or companion file |
| `Cannot find credentials` | Credentials ID not found in Jenkins | Check credentials in Manage Jenkins → Credentials |
| `AccessDenied` | Bad access key or bucket permissions | Fix bucket policy or credentials |
| `All S3 sources failed` | Every source in the merged list (folder + global) returned an error | Check each source's endpoint, bucket, credentials, and whether the ZIP file exists at the expected key/prefix |
| Polling never triggers a build | `lastModified` unchanged even after re-upload | Some storage backends preserve the original `lastModified` on overwrite; upload to a new key or use a versioned naming scheme |

---

## Limitations

- ❌ ZIP archives only
- ❌ No IAM role / instance profile authentication
- ❌ Maximum archive size: 1 GB

---

## Supported Storage

| Provider | Example endpoint |
|---|---|
| AWS S3 | `https://s3.amazonaws.com` |
| MinIO | `http://minio:9000` |
| Ceph | `http://ceph:8080` |
| LocalStack | `http://localhost:4566` |

---

## License

LGPL v3
