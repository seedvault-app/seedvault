# Seedvault App Backup Repository Documentation

This document is a technical description of how Seedvault backs up apps
installed on an Android device.

It is inspired by the design of the [Restic backup program](https://restic.net/).
Portions of this document are blatant copies of
[its documentation](https://restic.readthedocs.io/en/latest/100_references.html).
However, due to the different nature of Android backups,
the design [was heavily simplified](#differences-to-restic).

Note that the backup of files is described [in a different document](../storage/doc/design.md).

## Terminology

This section introduces terminology used in this document.

*Repository*: All data produced during a backup is sent to and stored in a repository
in a structured form.

*Chunk*: Larger files are cut into re-usable chunks that are the unit of de-duplication.

*Blob*: A blob is a chunk stored encrypted and compressed in the repository.

*Snapshot*: A snapshot stands for the state of a collection of apps
that have been backed up at some point in time.
The state here means the app data as delivered by the system (may be incomplete or absent)
and the apps themselves as device specific APK files as installed at time of backup.

*Storage ID*: A storage ID is the SHA-256 hash of the content stored in the repository.
This ID is required in order to load the file from the repository.

## Repository Layout

The name of all files in the repository starts with the lower case hexadecimal representation
of the storage ID, which is the SHA-256 hash of the file's contents.
This allows for easy verification of files for accidental modifications,
like disk read errors, by simply running the program `sha256sum` on the file
and comparing its output to the file name.

All files in a repository are only written once and never modified afterwards.

```console
.SeedVaultAndroidBackup
└── f35860ee961789fb5f92f467455acf165120a319e9dc27044282982111546f26
    ├── 00
    │   └── 001b527ebb5eb57f4934bafeb998cb08595ed7ced603d9d25bd3c50b338f939d
    ├── 01
    │   └── 01e61554a023c9c1053e026c8a70498fb4732c3ecaaad1bd44003185b493529b
    ├── ...
    ├── fe
    │   └── fe94fd20382e76d0215a743f3f27879d9555947250504de5b0d45321f1f66c7a
    ├── ff
    │   └── ff2e9f9c75d211602c5a68f0471aa549e2499a3fa9496255b26678f4aad75a98
    ├── 22a5af1bdc6e616f8a29579458c49627e01b32210d09adb288d1ecda7c5711ec.snapshot
    ├── 3ec79977ef0cf5de7b08cd12b874cd0f62bbaf7f07f3497a5b1bbcc8cb39b1ce.snapshot
```

Historically, all data that Seedvault saves to backup storage
is below a `.SeedVaultAndroidBackup` directory.
It can contain one or more repositories as users may use the same backup storage
for several devices or user accounts.
As having to choose and remember a specific folder is considered bad UX
for the regular Android user,
Seedvault creates a repository for the user.

The folder name is the ID of the repository.
It is the result of applying HMAC-SHA256 with the "app backup repoId key"
(see [cryptography](#cryptography)) to the
[`ANDROID_ID`](https://developer.android.com/reference/android/provider/Settings.Secure#ANDROID_ID)
which is a 64-bit number (expressed as a hexadecimal string) provided by the operating system.
It is unique to each combination of app-signing key, user, and device.
This design should guarantee that only one single app ever backs up to or prunes the repository.
Also, if the user generates a new recovery key and thus all keys change,
Seedvault will not attempt to back up into the repository tied to the old key.

For restore, we iterate through all repositories and only offer snapshots for restore
that can be decrypted with the key provided by the user.
Hence, a restore may run on a second device while the first device is doing a backup.

Note: A repository used for file backup is stored in a folder of the format `[ANDROID_ID].sv`
and thus completely separate.

## Data Format

All files stored in the repository start with a version byte
followed by an encrypted and authenticated payload (see also [Cryptography](#cryptography)).

The version (currently `0x02`) is used to be able to modify aspects of the design in the future
and to provide backwards compatibility.

Blobs include the raw bytes of the compressed chunks
and snapshots their compressed protobuf encoding.

```console
┏━━━━━━━━━┳━━━━━━━━━━━━━━━━━━━━━━━━┓
┃ Version ┃ encrypted tink payload ┃
┃   0x02  ┃ (with 40 bytes header) ┃
┗━━━━━━━━━┻━━━━━━━━━━━━━━━━━━━━━━━━┛
```

## Snapshots

Encoded [in protobuf format](../app/src/main/proto/snapshot.proto).
Example printed as JSON:

```json
{
  "token": 171993168400,
  "name": "Google Pixel 7a",
  "androidId": "bbcc5909347d0b83",
  "sdkInt": 34,
  "androidIncremental": "eng.user.20240618.130805",
  "d2d": true,
  "apps": {
    "org.example.app": {
      "time": 171993268400,
      "state": "apkAndData",
      "type": "full",
      "name": "Example app",
      "system": false,
      "launchableSystemApp": false,
      "chunkIds": [
        "001b527ebb5eb57f4934bafeb998cb08595ed7ced603d9d25bd3c50b338f939d",
        "ff2e9f9c75d211602c5a68f0471aa549e2499a3fa9496255b26678f4aad75a98"
      ],
      "apk": {
        "versionCode": 2342,
        "installer": "org.fdroid.basic",
        "signatures": [
          "abd81091c552574506bbb143e3bd856504382666dd495811a539188957522ab2"
        ],
        "splits": [
          {
            "name": "base",
            "size": 1337,
            "chunkIds": [
              "4f34352be7c1f4d1597d27f5824b42bad2222cf029cc888e9d7318d5e8bc2ad5",
              "16759b52e8ef8c3b080f0a8cdb8ac21474cb41070f272a02e28fd1163762336b"
            ]
          }
        ]
      }
    }
  },
  "iconChunkIds": [
    "c11c3cf1c326375b177e768de908e6e423c3d8866715c8ec7159156b6ea82497"
  ],
  "blobs": {
    "001b527ebb5eb57f4934bafeb998cb08595ed7ced603d9d25bd3c50b338f939d": {
      "id": "259bb78b72b7b7f7ac1a9613c3c4936b06342471b39b1c41a328acaa2d3ccca5",
      "length": 645312,
      "uncompressedLength": 695312
    },
    "ff2e9f9c75d211602c5a68f0471aa549e2499a3fa9496255b26678f4aad75a98": {
      "id": "5e6a8789571183a97e84f74fe13c4e95b6c5645e2d53fe5cc7aa122897c246b4",
      "length": 9455314,
      "uncompressedLength": 9855314
    },
    "4f34352be7c1f4d1597d27f5824b42bad2222cf029cc888e9d7318d5e8bc2ad5": {
      "id": "a79d2b9e7afa051782cff6424ede1337c8ce05c34d9c2e565e2ed47e7ae80ea4",
      "length": 8430346,
      "uncompressedLength": 9430346
    },
    "16759b52e8ef8c3b080f0a8cdb8ac21474cb41070f272a02e28fd1163762336b": {
      "id": "31aa8e88c8b73df565fd8bbcadfe7e2e79303eb5c0223253353d3003273e3140",
      "length": 26549052,
      "uncompressedLength": 28549052
    },
    "c11c3cf1c326375b177e768de908e6e423c3d8866715c8ec7159156b6ea82497": {
      "id": "5421895dbead0ba83f0993f17b5f72f6c6618c0e9ea849c6c53ac240d1802d0b",
      "length": 523496,
      "uncompressedLength": 645314
    }
  }
}
```

The `chunkIds` and `iconChunkIds` fields contain a list with plain text SHA-256 hashes
which can be found in the main `blobs` dictionary.
This contains a mapping from plain text SHA-256 hashes to storage IDs and size information.

At the beginning of most operations, we download all available snapshots
to get information about all blobs that should be available in the repository.
We may additionally retrieve a list of all blobs directly from the repository
to ensure they are actually (still) present.

Snapshot files start with the SHA-256 hash of the content of the file
and use a `.snapshot` extension.

## Cryptography

This section is based on and thus very similar to encryption of
[files backup](../storage/doc/design.md).

### Main Key

Seedvault already uses [BIP39](https://github.com/bitcoin/bips/blob/master/bip-0039.mediawiki)
to give users a mnemonic recovery code and for generating deterministic keys.
The derived key has 512 bits
and Seedvault used to use the first 256 bits as an AES key to encrypt app data.
Unfortunately, this key's usage is limited by Android's keystore to encryption and decryption.
Therefore, the second 256 bits are be imported into Android's keystore for use with `HMAC-SHA256`,
so that this key can act as a main key we can deterministically derive additional keys from
by using HKDF ([RFC5869](https://tools.ietf.org/html/rfc5869)).
These second 256 bits must not be used for any other purpose in the future.
We use them for a main key to avoid users having to handle yet another secret.

For deriving keys, we are only using the HKDF's second 'expand' step,
because the Android Keystore does not give us access
to the key's byte representation (required for first 'extract' step) after importing it.
This should be fine as the input key material is already a cryptographically strong key
(see section 3.3 of RFC 5869 above).

### Key derivation overview

The original entropy comes from a BIP39 seed (12 words = 128 bit size)
obtained from Java's `SecureRandom`.
A PBKDF SHA512 based derivation defined in BIP39 turns this into a 512 bit seed key.

The derived seed key (512 bit size) gets split into two parts:
1. legacy app data encryption key (unused) - 256 bit - first half of seed key
2. main key - 256 bit - second half of seed key used to derive application specific keys:
   1. App Backup: HKDF with info "app backup repoId key"
   2. App Backup: HKDF with info "app backup gear table key"
   3. App Backup: HKDF with info "app backup stream key"
   4. File Backup: HKDF with info "stream key"
   5. File Backup: HKDF with info "Chunk ID calculation"

### Stream Encryption

When a stream is written to backup storage,
it starts with a header consisting of a single byte indicating the backup format version
(currently `0x02`) followed by the encrypted payload.

All data written to backup storage will be encrypted with a fresh key
to prevent issues with nonce/IV re-use of a single key.

We derive a stream key from the main key
by using HKDF's expand step with the UTF-8 byte representation of "app backup stream key"
as info input.
This stream key is then used to derive a new key for each stream.

Instead of encrypting, authenticating and segmenting a cleartext stream ourselves,
we have chosen to employ the [tink library](https://github.com/tink-crypto/tink-java) for that task.
Since it does not allow us to work with imported or derived keys,
we are only using its [AesGcmHkdfStreaming](https://developers.google.com/tink/streaming-aead/aes_gcm_hkdf_streaming)
to delegate encryption and decryption of byte streams.
This follows the OAE2 definition as proposed in the paper
"Online Authenticated-Encryption and its Nonce-Reuse Misuse-Resistance"
([PDF](https://eprint.iacr.org/2015/189.pdf)).

It adds its own 40 byte header consisting of header length (1 byte), salt and nonce prefix.
Then it adds one or more segments, each up to 1 MB in size.
All segments are encrypted with a fresh key that is derived by using HKDF
on our stream key with another internal random salt (32 bytes) and associated data as info
([documentation](https://github.com/google/tink/blob/v1.5.0/docs/WIRE-FORMAT.md#streaming-encryption)).

All types of files written to backup storage have the following format:

```console
    ┏━━━━━━━━━┳━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
    ┃ version ┃ tink payload (with 40 bytes header)                          ┃
    ┃  byte   ┃ ┏━━━━━━━━━━━━━━━┳━━━━━━┳━━━━━━━━━━━━━━┳━━━━━━━━━━━━━━━━━━━━┓ ┃
    ┃         ┃ ┃ header length ┃ salt ┃ nonce prefix ┃ encrypted segments ┃ ┃
    ┃ (0x02)  ┃ ┗━━━━━━━━━━━━━━━┻━━━━━━┻━━━━━━━━━━━━━━┻━━━━━━━━━━━━━━━━━━━━┛ ┃
    ┗━━━━━━━━━┻━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
```

When writing app data or apps to backup storage,
the authenticated associated data (AAD) contains the backup version as the only byte
(to prevent downgrade attacks) as it would otherwise not be authenticated.
Other data is not included as renaming and swapping out files is made impossible
by their file name being the content hash.

## Content-defined chunking

We use FastCDC ([PDF](https://www.usenix.org/system/files/conference/atc16/atc16-paper-xia.pdf))
to split ZIP files and TAR streams we receive from the system into re-usable chunks.
We aim for an average chunk size of 3 MiB,
because in our tests it presented a good trade-off
between deduplication ratio and number of small chunks.
Data smaller than 1.5 MiB will not be chunked further and be left as a single chunk.

TODO: Can we do something to prevent watermarking attacks against single chunk files,
      like padding somewhat them after compression, but before encryption?

The FastCDC algorithm uses a gear table containing 256 random integers with 31 bits.
When this table changes, the resulting chunks will be different.
Hence, every repository always uses the same gear table.
However, to make watermarking attacks harder,
we use the "app backup gear table key" that gets derived from our main key
to deterministically compute a gear table using AES CTR to cipher 32 null bytes with a null IV.

TODO: Is it safe to compute the gear table like this? We do derive a dedicated key for this
      that we don't use for anything else.
      Still, the IV as well as the encrypted bytes are known to the attacker.
      Also, it may be be possible to reverse the gear table with chosen plaintext attacks.

## Operations

### Backup

* download all snapshots to get a mapping of all plaintext hashes (chunk IDs) to storage IDs
* optionally: get a list of all blobs and their storage IDs to confirm they (still) exist
* if APK backup wasn't disabled, for each app:
  * get version code and check if we already have an APK with that version code in a snapshot
  * if APK was backed up already, re-use list of plaintext hashes for current snapshot
  * if APK was not yet backed up, put it (and its splits) through the chunker
  * chunks already in the repository are not uploaded again, only their hash recorded
  * new chunks get compressed, encrypted and hashed to determine their storage ID, then uploaded 
* for each app (the system allows us to back up):
  * app data we receive as a tar stream from the system gets chunked
    * chunks already in the repository are not uploaded again, only their hash recorded
    * new chunks get compressed, encrypted and hashed to determine their storage ID, then uploaded
  * remember ordered list of chunk IDs for the app (and its APKs)
* add newly packed chunks to local index
* upload consolidated index, remove old index
* add all apps, their chunk IDs (and related metadata) to new snapshot and upload that
* at the end prune old snapshots based on retention rules

### Resume interrupted backup

* the mapping from chunk ID to storage ID is only stored in snapshots
* if backup gets interrupted (e.g. power or network outage), no snapshot gets written
* due to encryption, storage IDs are not deterministic,  
  i.e. the same chunk stored two times will have two different storage IDs
* therefore, we keep a local cache for the mapping of chunk ID to storage ID
* before starting a backup, we retrieve all uploaded storage IDs 
  and remove all IDs from our local cache that don't exist
* when processing new chunks, we check if the chunk ID exists in our local cache 
  and re-use the existing blob from the mapped storage ID
* all mappings will be added to snapshot that gets uploaded at the end of backup

### Restore

* download all snapshots and let the user choose one for restore
* for all apps that were selected for restore:
  * reinstall app from APKs assembled from fetched chunks (see below for details)
  * look up chunk IDs for app from snapshot
  * download blobs for the chunk IDs as specified in snapshot
  * give decrypted and decompressed chunk data back to system

Repository re-use:

* if restore happened on a new device, offer user to tie repository to new device
* the same main key is being used on new device due to entry of BIP39 recovery code
* the repository ID depends on the main key and the `ANDROID_ID`
  which is different on the new device
* to tie repository to new device, the repository folder will be renamed to the repository ID
  specific to the new device
* this will prevent the old device from doing further writes into the repository
  and ensure the device will be exclusively writing to the repository

### Pruning

* download all snapshots
* assemble list of blobs still referenced by existing snapshots
* delete all blobs that are not referenced anymore

### Repository data verification

There are two types of checks that can be performed:
* Structural consistency and integrity, e.g. snapshots and blobs
  * download all snapshots and a list of all blobs and their size
  * check that all blobs referenced in snapshots exist on storage and have expected size
* Integrity of the actual data that you backed up
  * do the structural checking as above
  * offer full checking and random sampling as options as full check will be slow
  * also download all/some blobs check their hash, decrypt and check chunk ID

## Read and Write Ordering

New snapshots only get added after all blobs they reference have been uploaded.

Blobs only get deleted after all snapshots that reference them have been deleted first.

## Differences to restic

Even though the design was initially inspired by restic,
the specific nature of Android app backups allowed us to make many simplifications
that result in the following differences:

* no config file needed:
  * repository ID is in the folder name
  * version is in first byte of all files
  * chunker differentiator is based on key
* no keys files
  * we re-use the existing BIP39 recovery code whose derived key is stored in the device key store
* blobs are not being combined into pack files, but saved directly
  * tests with real-world Android backups have shown 11% of files smaller than 10 KiB
    and 39% of files smaller than 500 KiB
  * a typical backup has around 500 files, so the number of small files seemed manageable enough
    to not justify the increased complexity of pack files and indexes
* no indexes
  * since we have no pack files, we don't need an index for blobs in pack files
  * a mapping of chunk ID to storage ID of blobs is stored in the snapshot
* no tree blobs
  * Android app backups are flat and not in a tree structure, just a list of apps and a list of APKs
    (one APK can have several APK splits)
  * metadata needed for each app is directly stored in the snapshot which still has manageable size
* no locks
  * we only allow a single app to use a repository by binding it to the app/user/device
  * restore while another app is backing up is possible, but this doesn't require locks,
    if we follow the proper read/write ordering
* different encryption and MACs
  * we were already employing AES GCM via Google's tink library
    for legacy app backup and files backup, so we stuck with it
* snapshot metadata is Android specific and snapshots are not stored in a dedicated folder,
  but have a `.snapshot` extension. So their name isn't the content hash, but starts with it.

## Acknowledgements

The following individuals have reviewed this document and provided helpful feedback.

* Thomas Waldmann
* Alexander Weiss

As they have reviewed different parts and different versions at different times,
this acknowledgement should not be mistaken for their endorsement of the current design
nor the final implementation.
