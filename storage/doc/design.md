# Overview

This is a design document for Seedvault storage backup.
It is heavily inspired by borgbackup, but simplified and adapted to the Android context.

The aim is to efficiently backup media files from Android's `MediaStore`
and other files from external storage.
Apps and their data are explicitly out of scope
as this is handled already by Seedvault via the Android backup system.
Techniques introduced here might be applied to app backups in the future.

## Terminology

A **backup snapshot** (or short backup) represents a collection of files at one point in time.
Making a backup creates such a snapshot and writes it to **backup storage**
which is an abstract location to save files to (e.g. flash drive or cloud storage).
Technically the backup snapshot is a file containing metadata
about the backup such as the included files.
A **backup run** is the process of backing up files i.e. making a backup.

Large files are split into **chunks** (smaller pieces) by a **chunker**.
Small files are combined to **zip chunks**.

File information is cached locally in the **files cache** to speed up operations.
There is also the **chunks cache** to cache information about available chunks.

# Operations

## Making a backup

A backup run is usually triggered automatically when

* the device is charging and connected to an un-metered network in case network storage is used
* a storage medium is plugged in (and the user confirmed the run) in case removable storage is used

Files to be backed up are scanned based on the user's preference
using Android's `MediaProvider` and `ExternalStorageProvider`.
Tests on real world devices have shown ~200ms scan times for `MediaProvider`
and `~10sec` for *all* of `ExternalStorageProvider`
(which is unlikely to happen, because the entire storage volume cannot be selected on Android 11).

All files included in backups will be scanned with every backup run.
If a file is found in the cache, it is checked
if its content-modification-indicating
(size, lastModified and generation for media files)
have not been modified
and all its chunks are still present in the backup storage.
For the latter check, we initially retrieve a list of all chunks available on backup storage.

For present unchanged files, an entry will be added to the backup snapshot
and the lastSeen timestamp in the files cache updated.
If a file is not found in the cache, an entry will be added for it.
New and modified files will be put through a chunker
which splits up larger files into smaller chunks.
Very small files are combined into larger zip chunks for transfer efficiency.

A chunk is hashed (with a key / MACed),
then (compressed and) encrypted (with authentication) and written to backup storage,
if it is not already present.
New chunks get added to the chunks cache.
Only after the backup has completed and the backup snapshot was written,
the reference counters of the included chunks will be incremented.

When all chunks of a file have either been written or were present already,
the file metadata is added to the backup snapshot with its list of chunk IDs and other metadata.

When all files have been processed, the backup snapshot is finalized
and written (encrypted) to storage.

If the backup fails, a new run is attempted at the next opportunity creating a new backup snapshot.
Chunks uploaded during the failed run should still be available in backup storage
and in the cache with reference count `0`, providing a seamless auto-resume.

After a *successful* backup run, chunks that still have reference count `0`
can be deleted from storage and cache without risking to delete chunks that will be needed later.

## Removing old backups

Ideally, the user can decide how many backups should be kept based on available storage capacity.
These could be a number in the yearly/monthly/weekly/daily categories.
However, initially, we might simply auto-prune backups older than a month,
if there have been at least 3 backups within that month (or some similar scheme).

After a successful backup run is a good time to prune old backups.
To determine which backups to delete, the backup snapshots need to be downloaded and inspected.
Their file name can be derived from their `timeStart` timestamp to help with that task.
If a backup is selected for deletion, the reference counter of all included chunks is decremented.
Note that a backup snapshot can reference a single chunk several times.
The reference counter however refers to the number of snapshots references it,
not the number of files.
The backup snapshot file and chunks with reference count of `0` are then deleted from storage.

## Restoring from backup

When the user wishes to restore a backup, they select the backup snapshot that should be used.
The selection can be done based on time and name.
We go through the list of files in the snapshot,
download, authenticate, decrypt (and decompress) each chunk of the file
and re-assemble the file this way.
Once we have the original chunk,
we could re-calculate the chunk ID to prevent an attacker from swapping chunks.
However, we instead include the chunk ID
in the associated data of the authenticated encryption (AEAD) which should have the same effect.
The re-assembled file will be placed into the same directory under the same name
with its attributes (e.g. lastModified) restored as much as possible on Android.

Restoring to storage that is already in use is not supported.
However, if a file already exists with the that name and path,
we could check if the file is identical to the one we want to restore
(by relying on file metadata or re-computing chunk IDs)
and move to the next if it is indeed identical.
If it is not identical, we rely on Android's Storage Access Framework
to automatically give it a `(1)` suffix when writing it to disk or add one manually.
Normally, restores are expected to happen to a clean file system anyway.

However, if a restore fails, the above behavior (not implemented in first iteration)
should give us a seamless auto-resume experience.
The user can re-try the restore and it will quickly skip already restored files
and continue to download the ones that are still missing.

After all files have been written to a directory,
we might want to attempt to restore its metadata (and flags?) as well.
However, restoring directory metadata is not implemented in first iteration.


# Cryptography

The goal here is to be as simple as possible while still being secure
meaning that we want to primarily conceal the content of the backed up files.
Certain trade-offs have to be made though,
so that for now we do not attempt to hide file sizes.
E.g. an attacker with access to the backup storage might be able to infer
that the Snowden files are part of our backup.
We do however encrypt file names and paths.

## Master Key

Seedvault already uses [BIP39](https://github.com/bitcoin/bips/blob/master/bip-0039.mediawiki)
to give users a mnemonic recovery code and for generating deterministic keys.
The derived key has 512 bits
and Seedvault uses the first 256 bits as an AES key to encrypt app data (out of scope here).
Unfortunately, this key's usage is currently limited by Android to encryption and decryption.
Therefore, the second 256 bits will be imported into Android's keystore for use with HMAC-SHA256,
so that this key can act as a master key we can deterministically derive additional keys from
by using HKDF ([RFC5869](https://tools.ietf.org/html/rfc5869)).
These second 256 bits must not be used for any other purpose in the future.
We use them for a master key to avoid users having to handle yet another secret.

For deriving keys, we are only using the HKDF's second 'expand' step,
because the Android Keystore does not give us access
to the key's byte representation (required for first 'extract' step) after importing it.
This should be fine as the input key material is already a cryptographically strong key
(see section 3.3 of RFC 5869 above).

## Choice of primitives

AES-GCM and SHA256 have been chosen,
because [both are hardware accelerated](https://en.wikichip.org/wiki/arm/armv8#ARMv8_Extensions_and_Processor_Features)
on 64-bit ARMv8 CPUs that are used in modern phones.
Our own tests against Java implementations of Blake2s, Blake3 and ChaCha20-Poly1305
have confirmed that these indeed offer worse performance by a few factors.
C implementations via JNI have not been evaluated though
due to difficulties of building those as part of AOSP.

## Chunk ID calculation

We use a keyed hash instead of a normal hash for calculating the chunk ID
to not leak the file content via the public hash.
Using HMAC-SHA256 directly with the master key in Android's key store
resulted in terrible throughput of around 4 MB/sec,
presumably because file data needs to enter the secure element to get hashed there.
Java implementations of Blake2s and Blake3 performed better,
but by far the best performance gave HMAC-SHA256
with a key we can hold the byte representation for in memory.

Therefore, we derive a dedicated key for chunk ID calculation from the master key
and keep it in memory for as long as we need it.
If an attacker is able to read our memory,
they have access to the entire device anyway
and there's no point anymore in protecting content indicators such as chunk hashes.

To derive the chunk ID calculation key, we use HKDF's expand step
with the UTF-8 byte representation of "Chunk ID calculation" as info input.

## Stream Encryption

When a stream is written to backup storage,
it starts with a header consisting of a single byte indicating the backup format version
followed by the encrypted payload.

Each chunk and backup snapshot written to backup storage will be encrypted with a fresh key
to prevent issues with nonce/IV re-use of a single key.
Similar to the chunk ID calculation key above, we derive a stream key from the master key
by using HKDF's expand step with the UTF-8 byte representation of "stream key" as info input.
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

When writing files/chunks to backup storage,
the authenticated associated data (AAD) will contain the backup version as the first byte
(to prevent downgrade attacks)
followed by a second type byte depending on the type of file written:

* chunks: `0x00` as type byte and then the byte representation of the chunk ID
* backup snapshots: `0x01` as type byte and then the backup snapshot timestamp as int64 bytes

The chunk ID and the backup snapshot timestamp get added
to prevent an attacker from renaming and swapping files/chunks.

## Key derivation overview

The original entropy comes from a BIP39 seed (12 words = 128 bit size)
obtained from Java's `SecureRandom`.
A PBKDF SHA512 based derivation defined in BIP39 turns this into a 512 bit seed key.

The derived seed key (512 bit size) gets split into two parts:
1. app data encryption key - 256 bit - first half of seed key
    * used to encrypt app data retrieved from AOSP
    * locked in Android's key store for this usage
    * device needs to be unlocked for decryption
    * usage deprecated, should not be used for other operations
2. main key - 256 bit - second half of seed key used to derive application specific keys:
    1. HKDF with info "stream key"
         * this stream key is then used to derive a new key for each stream
         * the tink library will create various new subkeys based on salt/nonce/counter
    2. HKDF with info "Chunk ID calculation"
         * used to generate deterministic hashes (HMAC-SHA256) over the chunk contents
           without leaking information about the file contents to third parties

# Data structures

## Local caches

The local cache is implemented as a sqlite-based Room database
which had shown promising performance in early tests.

Most information in the cache is considered public knowledge
also available to an attacker with access to the local filesystem
(with root access or file management permission).
Still, the cache data can only be accessed by the owning backup application
and can not be accessed by other apps unless the attacker obtains root access
or is otherwise able to break Android's security model.
In that later case, the attacker will be able to access all files anyway
making access to the cache worthless.

### Files cache

This cache is needed to quickly look up if a file has changed and if we have all of its chunks.

Contents:

* URI (stripped by scheme and authority?) (`String` with index for fast lookups)
* file size (`Long`)
* last modified in milliseconds (`Long`)
* generation modified (MediaStore only) (`Long`)
* list of chunk IDs representing the file's contents
* zip index in case this file is inside a single zip chunk (`Integer`)
* last seen in epoch milliseconds (`Long`)

If the file's size, last modified timestamp (and generation) is still the same,
it is considered to not have changed.
In that case, we check that all file content chunks are (still) present in storage.

If the file has not changed and all chunks are present,
the file is not read/chunked/hashed again.
Only file metadata is added to the backup snapshot.

If a file's URI should ever change, it will be considered as a new file,
so read/chunked/hashed again, but if it hasn't otherwise changed,
its chunks will not be written to storage again
(except for small files that get added to a new zip chunk).

As the cache grows over time, we need a way to evict files eventually
(not implemented in first iteration).
This can happen by checking the last seen timestamp
and delete all files we haven't seen for some time (maybe a month).

The files cache is local only and will not be included in the backup.
After restoring from backup the cache needs to get repopulated on the next backup run.
This will happen automatically, because before each backup run we check cache consistency
and repopulate the cache if we find it inconsistent with what we have in backup storage.
The URIs of the restored files will most likely differ from the backed up ones.
When the `MediaStore` version changes,
the chunk IDs of all files will need to get recalculated as well
(not implemented in first iteration),
because we can't be sure about their new state.

### Chunks cache

This is used to determine whether we already have a chunk,
to count references to it and also for statistics.

It is implemented as a table in the same database as the files cache.

* chunk ID (hex representation of the chunk's MAC)
* reference count
* size
* backup version byte (currently 0)

If the reference count of a chunk reaches `0`,
we can delete it from storage (after a successful backup run)
as it isn't used by a backup snapshot anymore.

References are only stored in this local chunks cache.
If the cache is lost (or not available after restoring),
it can be repopulated by inspecting all backup snapshots
and setting the reference count to the number of backup snapshots a chunk is referenced from.

When making a backup run and hit the files cache,
we check that all chunks are still available on storage.

The backup version number of a chunk is stored, so we can know without downloading the chunk
with what backup version it was written.
This might be useful when increasing the backup version and changing the chunk format in the future.

## Remote Files

All types of files written to backup storage have the following format:

    ┏━━━━━━━━━┳━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
    ┃         ┃ tink payload (with 40 bytes header)                          ┃
    ┃ version ┃ ┏━━━━━━━━━━━━━━━┳━━━━━━┳━━━━━━━━━━━━━━┳━━━━━━━━━━━━━━━━━━━━┓ ┃
    ┃  byte   ┃ ┃ header length ┃ salt ┃ nonce prefix ┃ encrypted segments ┃ ┃
    ┃         ┃ ┗━━━━━━━━━━━━━━━┻━━━━━━┻━━━━━━━━━━━━━━┻━━━━━━━━━━━━━━━━━━━━┛ ┃
    ┗━━━━━━━━━┻━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛

### Backup Snapshot

The backup snapshot contains metadata about a single backup
and is written to the storage after a successful backup run.

* version - the backup version
* name - a name of the backup
* media files - a list of all `MediaStore` files in this backup
  * media type (enum: images, video, audio or downloads)
  * name (string)
  * relative path (string)
  * last modified timestamp (long)
  * owner package name (string)
  * is favorite (boolean)
  * file size (long)
  * storage volume (string)
  * ordered list of chunk IDs (to re-assemble the file)
  * zip index (int)
* document files - a list of all document files from external storage in this backup
  * name (string)
  * relative path (string)
  * last modified timestamp (long)
  * file size (long)
  * storage volume (string)
  * ordered list of chunk IDs (to re-assemble the file)
  * zip index (int)
* total size - sum of the size of all files, for stats
* timeStart - when the backup run was started
* timeEnd - when the backup run was finished

All backup snapshots are stored in the root folder.
The filename is the timeStart timestamp.

### Chunks

The encrypted payload of chunks is just the chunk data itself.
We suggest that file-system based storage plugins store chunks in one of 256 sub-folders
representing the first byte of the chunk ID encoded as a hex string.
The file name is the chunk ID encoded as a (lower-case) hex string.
This is similar to how git stores its repository objects
and to avoid having to store all chunks in a single directory which might not scale.

### Zip chunks

Transferring many very small files causes a substantial overhead
when transferring them to the storage medium.
It would be nice to avoid that.
Michael Rogers proposed the following idea to address this.

A chunk can either be part of a large file, all of a medium-sized file,
or a (deterministic) zip containing multiple small files.
When creating a backup, we sort the files in the small category by last modification
and pack as many files into each chunk as we can.
Each small file will be stored in the zip chunk under some artificial name
that is unique within the scope of the zip chunk like a counter.
The path to unique name mapping will be stored in the backup snapshot (zip index).
If a small file is inside a zip chunk,
that chunk ID will be listed as the only chunk of the file in the backup snapshot
and likewise for any other files inside that chunk.

When creating the next backup, if none of the small files have changed,
we just increase the ref count on the existing chunk.
If some of them have changed, they will be added to a new zip chunk
together with other new/changed small files.
Hanging on to the old file inside the still referenced zip chunk longer than necessary
should be ok as these files are small.

When fetching a chunk for restore, we know in advance whether it is a zip chunk,
because the file we need it for contains the zip index,
so we will not confuse it with a medium-sized zip file.
Then we unzip the zip chunk and extract the file by its zip index.

# Out-of-Scope

The following features would be nice to have,
but are considered out-of-scope of the current design for time and budget reasons.

* compression (we initially assume that most files are already sufficiently compressed)
* using a rolling hash to produce chunks in order to increase likelihood of obtaining same chunks
  even if file contents change slightly or shift
* external secret-less corruption checks that would use checksums over encrypted data
* concealing file sizes (though zip chunks helps a bit here)
* implementing different storage plugins

# Known issues

## Changes to files can not be detected reliably

Changes can be detected using file size and lastModified timestamps.
These have only a precision of seconds,
so we can't detect a changes happening within a second of a first change.
Also other apps can reset the lastModified timestamp
preventing us from registering a change if the file size doesn't change.
On Android 11, media files have a generation counter that gets incremented when files changes
to help with this issue.
However, files on external storage still don't have anything similar
and usually also don't trigger `ContentObserver` notifications.

## Android's Storage Access Framework can be unreliable

Since Seedvault already uses Android's Storage Access Framework (SAF) to store app backups,
we re-use this storage that the user has already chosen.
So we can avoid making the user choose two storage location
and to avoid having to implement another storage backend in the first iteration.
However, the SAF can be backed by different storage providers which are not equally reliable.
Also, the API is very limited, doesn't allow for atomic operations
and doesn't give feedback if file writes completed successfully as they happen asynchronously.
The best solution will be to not (only) rely on this storage abstraction API,
but at least offer different storage plugins that can operate more reliably.

# Acknowledgements

The following individuals have reviewed this document and provided helpful feedback.

* Demi M. Obenour
* Chirayu Desai
* Kevin Niehage
* Michael Rogers
* Thomas Waldmann
* Tom Hacohen

As they have reviewed different parts and different versions at different times,
this acknowledgement should not be mistaken for their endorsement of the current design
or the final implementation.
