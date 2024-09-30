//Generated by the protocol buffer compiler. DO NOT EDIT!
// source: snapshot.proto

package com.stevesoltys.seedvault.proto;

@kotlin.jvm.JvmName("-initializesnapshot")
public inline fun snapshot(block: com.stevesoltys.seedvault.proto.SnapshotKt.Dsl.() -> kotlin.Unit): com.stevesoltys.seedvault.proto.Snapshot =
  com.stevesoltys.seedvault.proto.SnapshotKt.Dsl._create(com.stevesoltys.seedvault.proto.Snapshot.newBuilder()).apply { block() }._build()
public object SnapshotKt {
  @kotlin.OptIn(com.google.protobuf.kotlin.OnlyForUseByGeneratedProtoCode::class)
  @com.google.protobuf.kotlin.ProtoDslMarker
  public class Dsl private constructor(
    private val _builder: com.stevesoltys.seedvault.proto.Snapshot.Builder
  ) {
    public companion object {
      @kotlin.jvm.JvmSynthetic
      @kotlin.PublishedApi
      internal fun _create(builder: com.stevesoltys.seedvault.proto.Snapshot.Builder): Dsl = Dsl(builder)
    }

    @kotlin.jvm.JvmSynthetic
    @kotlin.PublishedApi
    internal fun _build(): com.stevesoltys.seedvault.proto.Snapshot = _builder.build()

    /**
     * <code>uint32 version = 1;</code>
     */
    public var version: kotlin.Int
      @JvmName("getVersion")
      get() = _builder.getVersion()
      @JvmName("setVersion")
      set(value) {
        _builder.setVersion(value)
      }
    /**
     * <code>uint32 version = 1;</code>
     */
    public fun clearVersion() {
      _builder.clearVersion()
    }

    /**
     * <code>uint64 token = 2;</code>
     */
    public var token: kotlin.Long
      @JvmName("getToken")
      get() = _builder.getToken()
      @JvmName("setToken")
      set(value) {
        _builder.setToken(value)
      }
    /**
     * <code>uint64 token = 2;</code>
     */
    public fun clearToken() {
      _builder.clearToken()
    }

    /**
     * <code>string name = 3;</code>
     */
    public var name: kotlin.String
      @JvmName("getName")
      get() = _builder.getName()
      @JvmName("setName")
      set(value) {
        _builder.setName(value)
      }
    /**
     * <code>string name = 3;</code>
     */
    public fun clearName() {
      _builder.clearName()
    }

    /**
     * <code>string user = 4;</code>
     */
    public var user: kotlin.String
      @JvmName("getUser")
      get() = _builder.getUser()
      @JvmName("setUser")
      set(value) {
        _builder.setUser(value)
      }
    /**
     * <code>string user = 4;</code>
     */
    public fun clearUser() {
      _builder.clearUser()
    }

    /**
     * <code>string androidId = 5;</code>
     */
    public var androidId: kotlin.String
      @JvmName("getAndroidId")
      get() = _builder.getAndroidId()
      @JvmName("setAndroidId")
      set(value) {
        _builder.setAndroidId(value)
      }
    /**
     * <code>string androidId = 5;</code>
     */
    public fun clearAndroidId() {
      _builder.clearAndroidId()
    }

    /**
     * <code>uint32 sdkInt = 6;</code>
     */
    public var sdkInt: kotlin.Int
      @JvmName("getSdkInt")
      get() = _builder.getSdkInt()
      @JvmName("setSdkInt")
      set(value) {
        _builder.setSdkInt(value)
      }
    /**
     * <code>uint32 sdkInt = 6;</code>
     */
    public fun clearSdkInt() {
      _builder.clearSdkInt()
    }

    /**
     * <code>string androidIncremental = 7;</code>
     */
    public var androidIncremental: kotlin.String
      @JvmName("getAndroidIncremental")
      get() = _builder.getAndroidIncremental()
      @JvmName("setAndroidIncremental")
      set(value) {
        _builder.setAndroidIncremental(value)
      }
    /**
     * <code>string androidIncremental = 7;</code>
     */
    public fun clearAndroidIncremental() {
      _builder.clearAndroidIncremental()
    }

    /**
     * <code>bool d2d = 8;</code>
     */
    public var d2D: kotlin.Boolean
      @JvmName("getD2D")
      get() = _builder.getD2D()
      @JvmName("setD2D")
      set(value) {
        _builder.setD2D(value)
      }
    /**
     * <code>bool d2d = 8;</code>
     */
    public fun clearD2D() {
      _builder.clearD2D()
    }

    /**
     * An uninstantiable, behaviorless type to represent the field in
     * generics.
     */
    @kotlin.OptIn(com.google.protobuf.kotlin.OnlyForUseByGeneratedProtoCode::class)
    public class AppsProxy private constructor() : com.google.protobuf.kotlin.DslProxy()
    /**
     * <code>map&lt;string, .com.stevesoltys.seedvault.proto.Snapshot.App&gt; apps = 9;</code>
     */
     public val apps: com.google.protobuf.kotlin.DslMap<kotlin.String, com.stevesoltys.seedvault.proto.Snapshot.App, AppsProxy>
      @kotlin.jvm.JvmSynthetic
      @JvmName("getAppsMap")
      get() = com.google.protobuf.kotlin.DslMap(
        _builder.getAppsMap()
      )
    /**
     * <code>map&lt;string, .com.stevesoltys.seedvault.proto.Snapshot.App&gt; apps = 9;</code>
     */
    @JvmName("putApps")
    public fun com.google.protobuf.kotlin.DslMap<kotlin.String, com.stevesoltys.seedvault.proto.Snapshot.App, AppsProxy>
      .put(key: kotlin.String, value: com.stevesoltys.seedvault.proto.Snapshot.App) {
         _builder.putApps(key, value)
       }
    /**
     * <code>map&lt;string, .com.stevesoltys.seedvault.proto.Snapshot.App&gt; apps = 9;</code>
     */
    @kotlin.jvm.JvmSynthetic
    @JvmName("setApps")
    @Suppress("NOTHING_TO_INLINE")
    public inline operator fun com.google.protobuf.kotlin.DslMap<kotlin.String, com.stevesoltys.seedvault.proto.Snapshot.App, AppsProxy>
      .set(key: kotlin.String, value: com.stevesoltys.seedvault.proto.Snapshot.App) {
         put(key, value)
       }
    /**
     * <code>map&lt;string, .com.stevesoltys.seedvault.proto.Snapshot.App&gt; apps = 9;</code>
     */
    @kotlin.jvm.JvmSynthetic
    @JvmName("removeApps")
    public fun com.google.protobuf.kotlin.DslMap<kotlin.String, com.stevesoltys.seedvault.proto.Snapshot.App, AppsProxy>
      .remove(key: kotlin.String) {
         _builder.removeApps(key)
       }
    /**
     * <code>map&lt;string, .com.stevesoltys.seedvault.proto.Snapshot.App&gt; apps = 9;</code>
     */
    @kotlin.jvm.JvmSynthetic
    @JvmName("putAllApps")
    public fun com.google.protobuf.kotlin.DslMap<kotlin.String, com.stevesoltys.seedvault.proto.Snapshot.App, AppsProxy>
      .putAll(map: kotlin.collections.Map<kotlin.String, com.stevesoltys.seedvault.proto.Snapshot.App>) {
         _builder.putAllApps(map)
       }
    /**
     * <code>map&lt;string, .com.stevesoltys.seedvault.proto.Snapshot.App&gt; apps = 9;</code>
     */
    @kotlin.jvm.JvmSynthetic
    @JvmName("clearApps")
    public fun com.google.protobuf.kotlin.DslMap<kotlin.String, com.stevesoltys.seedvault.proto.Snapshot.App, AppsProxy>
      .clear() {
         _builder.clearApps()
       }

    /**
     * An uninstantiable, behaviorless type to represent the field in
     * generics.
     */
    @kotlin.OptIn(com.google.protobuf.kotlin.OnlyForUseByGeneratedProtoCode::class)
    public class IconChunkIdsProxy private constructor() : com.google.protobuf.kotlin.DslProxy()
    /**
     * <code>repeated bytes iconChunkIds = 10;</code>
     */
     public val iconChunkIds: com.google.protobuf.kotlin.DslList<com.google.protobuf.ByteString, IconChunkIdsProxy>
      @kotlin.jvm.JvmSynthetic
      get() = com.google.protobuf.kotlin.DslList(
        _builder.getIconChunkIdsList()
      )
    /**
     * <code>repeated bytes iconChunkIds = 10;</code>
     * @param value The iconChunkIds to add.
     */
    @kotlin.jvm.JvmSynthetic
    @kotlin.jvm.JvmName("addIconChunkIds")
    public fun com.google.protobuf.kotlin.DslList<com.google.protobuf.ByteString, IconChunkIdsProxy>.add(value: com.google.protobuf.ByteString) {
      _builder.addIconChunkIds(value)
    }/**
     * <code>repeated bytes iconChunkIds = 10;</code>
     * @param value The iconChunkIds to add.
     */
    @kotlin.jvm.JvmSynthetic
    @kotlin.jvm.JvmName("plusAssignIconChunkIds")
    @Suppress("NOTHING_TO_INLINE")
    public inline operator fun com.google.protobuf.kotlin.DslList<com.google.protobuf.ByteString, IconChunkIdsProxy>.plusAssign(value: com.google.protobuf.ByteString) {
      add(value)
    }/**
     * <code>repeated bytes iconChunkIds = 10;</code>
     * @param values The iconChunkIds to add.
     */
    @kotlin.jvm.JvmSynthetic
    @kotlin.jvm.JvmName("addAllIconChunkIds")
    public fun com.google.protobuf.kotlin.DslList<com.google.protobuf.ByteString, IconChunkIdsProxy>.addAll(values: kotlin.collections.Iterable<com.google.protobuf.ByteString>) {
      _builder.addAllIconChunkIds(values)
    }/**
     * <code>repeated bytes iconChunkIds = 10;</code>
     * @param values The iconChunkIds to add.
     */
    @kotlin.jvm.JvmSynthetic
    @kotlin.jvm.JvmName("plusAssignAllIconChunkIds")
    @Suppress("NOTHING_TO_INLINE")
    public inline operator fun com.google.protobuf.kotlin.DslList<com.google.protobuf.ByteString, IconChunkIdsProxy>.plusAssign(values: kotlin.collections.Iterable<com.google.protobuf.ByteString>) {
      addAll(values)
    }/**
     * <code>repeated bytes iconChunkIds = 10;</code>
     * @param index The index to set the value at.
     * @param value The iconChunkIds to set.
     */
    @kotlin.jvm.JvmSynthetic
    @kotlin.jvm.JvmName("setIconChunkIds")
    public operator fun com.google.protobuf.kotlin.DslList<com.google.protobuf.ByteString, IconChunkIdsProxy>.set(index: kotlin.Int, value: com.google.protobuf.ByteString) {
      _builder.setIconChunkIds(index, value)
    }/**
     * <code>repeated bytes iconChunkIds = 10;</code>
     */
    @kotlin.jvm.JvmSynthetic
    @kotlin.jvm.JvmName("clearIconChunkIds")
    public fun com.google.protobuf.kotlin.DslList<com.google.protobuf.ByteString, IconChunkIdsProxy>.clear() {
      _builder.clearIconChunkIds()
    }
    /**
     * An uninstantiable, behaviorless type to represent the field in
     * generics.
     */
    @kotlin.OptIn(com.google.protobuf.kotlin.OnlyForUseByGeneratedProtoCode::class)
    public class BlobsProxy private constructor() : com.google.protobuf.kotlin.DslProxy()
    /**
     * <code>map&lt;string, .com.stevesoltys.seedvault.proto.Snapshot.Blob&gt; blobs = 11;</code>
     */
     public val blobs: com.google.protobuf.kotlin.DslMap<kotlin.String, com.stevesoltys.seedvault.proto.Snapshot.Blob, BlobsProxy>
      @kotlin.jvm.JvmSynthetic
      @JvmName("getBlobsMap")
      get() = com.google.protobuf.kotlin.DslMap(
        _builder.getBlobsMap()
      )
    /**
     * <code>map&lt;string, .com.stevesoltys.seedvault.proto.Snapshot.Blob&gt; blobs = 11;</code>
     */
    @JvmName("putBlobs")
    public fun com.google.protobuf.kotlin.DslMap<kotlin.String, com.stevesoltys.seedvault.proto.Snapshot.Blob, BlobsProxy>
      .put(key: kotlin.String, value: com.stevesoltys.seedvault.proto.Snapshot.Blob) {
         _builder.putBlobs(key, value)
       }
    /**
     * <code>map&lt;string, .com.stevesoltys.seedvault.proto.Snapshot.Blob&gt; blobs = 11;</code>
     */
    @kotlin.jvm.JvmSynthetic
    @JvmName("setBlobs")
    @Suppress("NOTHING_TO_INLINE")
    public inline operator fun com.google.protobuf.kotlin.DslMap<kotlin.String, com.stevesoltys.seedvault.proto.Snapshot.Blob, BlobsProxy>
      .set(key: kotlin.String, value: com.stevesoltys.seedvault.proto.Snapshot.Blob) {
         put(key, value)
       }
    /**
     * <code>map&lt;string, .com.stevesoltys.seedvault.proto.Snapshot.Blob&gt; blobs = 11;</code>
     */
    @kotlin.jvm.JvmSynthetic
    @JvmName("removeBlobs")
    public fun com.google.protobuf.kotlin.DslMap<kotlin.String, com.stevesoltys.seedvault.proto.Snapshot.Blob, BlobsProxy>
      .remove(key: kotlin.String) {
         _builder.removeBlobs(key)
       }
    /**
     * <code>map&lt;string, .com.stevesoltys.seedvault.proto.Snapshot.Blob&gt; blobs = 11;</code>
     */
    @kotlin.jvm.JvmSynthetic
    @JvmName("putAllBlobs")
    public fun com.google.protobuf.kotlin.DslMap<kotlin.String, com.stevesoltys.seedvault.proto.Snapshot.Blob, BlobsProxy>
      .putAll(map: kotlin.collections.Map<kotlin.String, com.stevesoltys.seedvault.proto.Snapshot.Blob>) {
         _builder.putAllBlobs(map)
       }
    /**
     * <code>map&lt;string, .com.stevesoltys.seedvault.proto.Snapshot.Blob&gt; blobs = 11;</code>
     */
    @kotlin.jvm.JvmSynthetic
    @JvmName("clearBlobs")
    public fun com.google.protobuf.kotlin.DslMap<kotlin.String, com.stevesoltys.seedvault.proto.Snapshot.Blob, BlobsProxy>
      .clear() {
         _builder.clearBlobs()
       }
  }
  @kotlin.jvm.JvmName("-initializeapp")
  public inline fun app(block: com.stevesoltys.seedvault.proto.SnapshotKt.AppKt.Dsl.() -> kotlin.Unit): com.stevesoltys.seedvault.proto.Snapshot.App =
    com.stevesoltys.seedvault.proto.SnapshotKt.AppKt.Dsl._create(com.stevesoltys.seedvault.proto.Snapshot.App.newBuilder()).apply { block() }._build()
  public object AppKt {
    @kotlin.OptIn(com.google.protobuf.kotlin.OnlyForUseByGeneratedProtoCode::class)
    @com.google.protobuf.kotlin.ProtoDslMarker
    public class Dsl private constructor(
      private val _builder: com.stevesoltys.seedvault.proto.Snapshot.App.Builder
    ) {
      public companion object {
        @kotlin.jvm.JvmSynthetic
        @kotlin.PublishedApi
        internal fun _create(builder: com.stevesoltys.seedvault.proto.Snapshot.App.Builder): Dsl = Dsl(builder)
      }

      @kotlin.jvm.JvmSynthetic
      @kotlin.PublishedApi
      internal fun _build(): com.stevesoltys.seedvault.proto.Snapshot.App = _builder.build()

      /**
       * <code>uint64 time = 1;</code>
       */
      public var time: kotlin.Long
        @JvmName("getTime")
        get() = _builder.getTime()
        @JvmName("setTime")
        set(value) {
          _builder.setTime(value)
        }
      /**
       * <code>uint64 time = 1;</code>
       */
      public fun clearTime() {
        _builder.clearTime()
      }

      /**
       * <code>.com.stevesoltys.seedvault.proto.Snapshot.BackupType type = 2;</code>
       */
      public var type: com.stevesoltys.seedvault.proto.Snapshot.BackupType
        @JvmName("getType")
        get() = _builder.getType()
        @JvmName("setType")
        set(value) {
          _builder.setType(value)
        }
      /**
       * <code>.com.stevesoltys.seedvault.proto.Snapshot.BackupType type = 2;</code>
       */
      public fun clearType() {
        _builder.clearType()
      }

      /**
       * <code>string name = 3;</code>
       */
      public var name: kotlin.String
        @JvmName("getName")
        get() = _builder.getName()
        @JvmName("setName")
        set(value) {
          _builder.setName(value)
        }
      /**
       * <code>string name = 3;</code>
       */
      public fun clearName() {
        _builder.clearName()
      }

      /**
       * <code>bool system = 4;</code>
       */
      public var system: kotlin.Boolean
        @JvmName("getSystem")
        get() = _builder.getSystem()
        @JvmName("setSystem")
        set(value) {
          _builder.setSystem(value)
        }
      /**
       * <code>bool system = 4;</code>
       */
      public fun clearSystem() {
        _builder.clearSystem()
      }

      /**
       * <code>bool launchableSystemApp = 5;</code>
       */
      public var launchableSystemApp: kotlin.Boolean
        @JvmName("getLaunchableSystemApp")
        get() = _builder.getLaunchableSystemApp()
        @JvmName("setLaunchableSystemApp")
        set(value) {
          _builder.setLaunchableSystemApp(value)
        }
      /**
       * <code>bool launchableSystemApp = 5;</code>
       */
      public fun clearLaunchableSystemApp() {
        _builder.clearLaunchableSystemApp()
      }

      /**
       * An uninstantiable, behaviorless type to represent the field in
       * generics.
       */
      @kotlin.OptIn(com.google.protobuf.kotlin.OnlyForUseByGeneratedProtoCode::class)
      public class ChunkIdsProxy private constructor() : com.google.protobuf.kotlin.DslProxy()
      /**
       * <code>repeated bytes chunkIds = 6;</code>
       */
       public val chunkIds: com.google.protobuf.kotlin.DslList<com.google.protobuf.ByteString, ChunkIdsProxy>
        @kotlin.jvm.JvmSynthetic
        get() = com.google.protobuf.kotlin.DslList(
          _builder.getChunkIdsList()
        )
      /**
       * <code>repeated bytes chunkIds = 6;</code>
       * @param value The chunkIds to add.
       */
      @kotlin.jvm.JvmSynthetic
      @kotlin.jvm.JvmName("addChunkIds")
      public fun com.google.protobuf.kotlin.DslList<com.google.protobuf.ByteString, ChunkIdsProxy>.add(value: com.google.protobuf.ByteString) {
        _builder.addChunkIds(value)
      }/**
       * <code>repeated bytes chunkIds = 6;</code>
       * @param value The chunkIds to add.
       */
      @kotlin.jvm.JvmSynthetic
      @kotlin.jvm.JvmName("plusAssignChunkIds")
      @Suppress("NOTHING_TO_INLINE")
      public inline operator fun com.google.protobuf.kotlin.DslList<com.google.protobuf.ByteString, ChunkIdsProxy>.plusAssign(value: com.google.protobuf.ByteString) {
        add(value)
      }/**
       * <code>repeated bytes chunkIds = 6;</code>
       * @param values The chunkIds to add.
       */
      @kotlin.jvm.JvmSynthetic
      @kotlin.jvm.JvmName("addAllChunkIds")
      public fun com.google.protobuf.kotlin.DslList<com.google.protobuf.ByteString, ChunkIdsProxy>.addAll(values: kotlin.collections.Iterable<com.google.protobuf.ByteString>) {
        _builder.addAllChunkIds(values)
      }/**
       * <code>repeated bytes chunkIds = 6;</code>
       * @param values The chunkIds to add.
       */
      @kotlin.jvm.JvmSynthetic
      @kotlin.jvm.JvmName("plusAssignAllChunkIds")
      @Suppress("NOTHING_TO_INLINE")
      public inline operator fun com.google.protobuf.kotlin.DslList<com.google.protobuf.ByteString, ChunkIdsProxy>.plusAssign(values: kotlin.collections.Iterable<com.google.protobuf.ByteString>) {
        addAll(values)
      }/**
       * <code>repeated bytes chunkIds = 6;</code>
       * @param index The index to set the value at.
       * @param value The chunkIds to set.
       */
      @kotlin.jvm.JvmSynthetic
      @kotlin.jvm.JvmName("setChunkIds")
      public operator fun com.google.protobuf.kotlin.DslList<com.google.protobuf.ByteString, ChunkIdsProxy>.set(index: kotlin.Int, value: com.google.protobuf.ByteString) {
        _builder.setChunkIds(index, value)
      }/**
       * <code>repeated bytes chunkIds = 6;</code>
       */
      @kotlin.jvm.JvmSynthetic
      @kotlin.jvm.JvmName("clearChunkIds")
      public fun com.google.protobuf.kotlin.DslList<com.google.protobuf.ByteString, ChunkIdsProxy>.clear() {
        _builder.clearChunkIds()
      }
      /**
       * <code>.com.stevesoltys.seedvault.proto.Snapshot.Apk apk = 7;</code>
       */
      public var apk: com.stevesoltys.seedvault.proto.Snapshot.Apk
        @JvmName("getApk")
        get() = _builder.getApk()
        @JvmName("setApk")
        set(value) {
          _builder.setApk(value)
        }
      /**
       * <code>.com.stevesoltys.seedvault.proto.Snapshot.Apk apk = 7;</code>
       */
      public fun clearApk() {
        _builder.clearApk()
      }
      /**
       * <code>.com.stevesoltys.seedvault.proto.Snapshot.Apk apk = 7;</code>
       * @return Whether the apk field is set.
       */
      public fun hasApk(): kotlin.Boolean {
        return _builder.hasApk()
      }

      /**
       * <code>uint64 size = 8;</code>
       */
      public var size: kotlin.Long
        @JvmName("getSize")
        get() = _builder.getSize()
        @JvmName("setSize")
        set(value) {
          _builder.setSize(value)
        }
      /**
       * <code>uint64 size = 8;</code>
       */
      public fun clearSize() {
        _builder.clearSize()
      }
    }
  }
  @kotlin.jvm.JvmName("-initializeapk")
  public inline fun apk(block: com.stevesoltys.seedvault.proto.SnapshotKt.ApkKt.Dsl.() -> kotlin.Unit): com.stevesoltys.seedvault.proto.Snapshot.Apk =
    com.stevesoltys.seedvault.proto.SnapshotKt.ApkKt.Dsl._create(com.stevesoltys.seedvault.proto.Snapshot.Apk.newBuilder()).apply { block() }._build()
  public object ApkKt {
    @kotlin.OptIn(com.google.protobuf.kotlin.OnlyForUseByGeneratedProtoCode::class)
    @com.google.protobuf.kotlin.ProtoDslMarker
    public class Dsl private constructor(
      private val _builder: com.stevesoltys.seedvault.proto.Snapshot.Apk.Builder
    ) {
      public companion object {
        @kotlin.jvm.JvmSynthetic
        @kotlin.PublishedApi
        internal fun _create(builder: com.stevesoltys.seedvault.proto.Snapshot.Apk.Builder): Dsl = Dsl(builder)
      }

      @kotlin.jvm.JvmSynthetic
      @kotlin.PublishedApi
      internal fun _build(): com.stevesoltys.seedvault.proto.Snapshot.Apk = _builder.build()

      /**
       * <pre>
       **
       * Attention: Has default value of 0
       * </pre>
       *
       * <code>uint64 versionCode = 1;</code>
       */
      public var versionCode: kotlin.Long
        @JvmName("getVersionCode")
        get() = _builder.getVersionCode()
        @JvmName("setVersionCode")
        set(value) {
          _builder.setVersionCode(value)
        }
      /**
       * <pre>
       **
       * Attention: Has default value of 0
       * </pre>
       *
       * <code>uint64 versionCode = 1;</code>
       */
      public fun clearVersionCode() {
        _builder.clearVersionCode()
      }

      /**
       * <code>string installer = 2;</code>
       */
      public var installer: kotlin.String
        @JvmName("getInstaller")
        get() = _builder.getInstaller()
        @JvmName("setInstaller")
        set(value) {
          _builder.setInstaller(value)
        }
      /**
       * <code>string installer = 2;</code>
       */
      public fun clearInstaller() {
        _builder.clearInstaller()
      }

      /**
       * An uninstantiable, behaviorless type to represent the field in
       * generics.
       */
      @kotlin.OptIn(com.google.protobuf.kotlin.OnlyForUseByGeneratedProtoCode::class)
      public class SignaturesProxy private constructor() : com.google.protobuf.kotlin.DslProxy()
      /**
       * <code>repeated bytes signatures = 3;</code>
       */
       public val signatures: com.google.protobuf.kotlin.DslList<com.google.protobuf.ByteString, SignaturesProxy>
        @kotlin.jvm.JvmSynthetic
        get() = com.google.protobuf.kotlin.DslList(
          _builder.getSignaturesList()
        )
      /**
       * <code>repeated bytes signatures = 3;</code>
       * @param value The signatures to add.
       */
      @kotlin.jvm.JvmSynthetic
      @kotlin.jvm.JvmName("addSignatures")
      public fun com.google.protobuf.kotlin.DslList<com.google.protobuf.ByteString, SignaturesProxy>.add(value: com.google.protobuf.ByteString) {
        _builder.addSignatures(value)
      }/**
       * <code>repeated bytes signatures = 3;</code>
       * @param value The signatures to add.
       */
      @kotlin.jvm.JvmSynthetic
      @kotlin.jvm.JvmName("plusAssignSignatures")
      @Suppress("NOTHING_TO_INLINE")
      public inline operator fun com.google.protobuf.kotlin.DslList<com.google.protobuf.ByteString, SignaturesProxy>.plusAssign(value: com.google.protobuf.ByteString) {
        add(value)
      }/**
       * <code>repeated bytes signatures = 3;</code>
       * @param values The signatures to add.
       */
      @kotlin.jvm.JvmSynthetic
      @kotlin.jvm.JvmName("addAllSignatures")
      public fun com.google.protobuf.kotlin.DslList<com.google.protobuf.ByteString, SignaturesProxy>.addAll(values: kotlin.collections.Iterable<com.google.protobuf.ByteString>) {
        _builder.addAllSignatures(values)
      }/**
       * <code>repeated bytes signatures = 3;</code>
       * @param values The signatures to add.
       */
      @kotlin.jvm.JvmSynthetic
      @kotlin.jvm.JvmName("plusAssignAllSignatures")
      @Suppress("NOTHING_TO_INLINE")
      public inline operator fun com.google.protobuf.kotlin.DslList<com.google.protobuf.ByteString, SignaturesProxy>.plusAssign(values: kotlin.collections.Iterable<com.google.protobuf.ByteString>) {
        addAll(values)
      }/**
       * <code>repeated bytes signatures = 3;</code>
       * @param index The index to set the value at.
       * @param value The signatures to set.
       */
      @kotlin.jvm.JvmSynthetic
      @kotlin.jvm.JvmName("setSignatures")
      public operator fun com.google.protobuf.kotlin.DslList<com.google.protobuf.ByteString, SignaturesProxy>.set(index: kotlin.Int, value: com.google.protobuf.ByteString) {
        _builder.setSignatures(index, value)
      }/**
       * <code>repeated bytes signatures = 3;</code>
       */
      @kotlin.jvm.JvmSynthetic
      @kotlin.jvm.JvmName("clearSignatures")
      public fun com.google.protobuf.kotlin.DslList<com.google.protobuf.ByteString, SignaturesProxy>.clear() {
        _builder.clearSignatures()
      }
      /**
       * An uninstantiable, behaviorless type to represent the field in
       * generics.
       */
      @kotlin.OptIn(com.google.protobuf.kotlin.OnlyForUseByGeneratedProtoCode::class)
      public class SplitsProxy private constructor() : com.google.protobuf.kotlin.DslProxy()
      /**
       * <code>repeated .com.stevesoltys.seedvault.proto.Snapshot.Split splits = 4;</code>
       */
       public val splits: com.google.protobuf.kotlin.DslList<com.stevesoltys.seedvault.proto.Snapshot.Split, SplitsProxy>
        @kotlin.jvm.JvmSynthetic
        get() = com.google.protobuf.kotlin.DslList(
          _builder.getSplitsList()
        )
      /**
       * <code>repeated .com.stevesoltys.seedvault.proto.Snapshot.Split splits = 4;</code>
       * @param value The splits to add.
       */
      @kotlin.jvm.JvmSynthetic
      @kotlin.jvm.JvmName("addSplits")
      public fun com.google.protobuf.kotlin.DslList<com.stevesoltys.seedvault.proto.Snapshot.Split, SplitsProxy>.add(value: com.stevesoltys.seedvault.proto.Snapshot.Split) {
        _builder.addSplits(value)
      }
      /**
       * <code>repeated .com.stevesoltys.seedvault.proto.Snapshot.Split splits = 4;</code>
       * @param value The splits to add.
       */
      @kotlin.jvm.JvmSynthetic
      @kotlin.jvm.JvmName("plusAssignSplits")
      @Suppress("NOTHING_TO_INLINE")
      public inline operator fun com.google.protobuf.kotlin.DslList<com.stevesoltys.seedvault.proto.Snapshot.Split, SplitsProxy>.plusAssign(value: com.stevesoltys.seedvault.proto.Snapshot.Split) {
        add(value)
      }
      /**
       * <code>repeated .com.stevesoltys.seedvault.proto.Snapshot.Split splits = 4;</code>
       * @param values The splits to add.
       */
      @kotlin.jvm.JvmSynthetic
      @kotlin.jvm.JvmName("addAllSplits")
      public fun com.google.protobuf.kotlin.DslList<com.stevesoltys.seedvault.proto.Snapshot.Split, SplitsProxy>.addAll(values: kotlin.collections.Iterable<com.stevesoltys.seedvault.proto.Snapshot.Split>) {
        _builder.addAllSplits(values)
      }
      /**
       * <code>repeated .com.stevesoltys.seedvault.proto.Snapshot.Split splits = 4;</code>
       * @param values The splits to add.
       */
      @kotlin.jvm.JvmSynthetic
      @kotlin.jvm.JvmName("plusAssignAllSplits")
      @Suppress("NOTHING_TO_INLINE")
      public inline operator fun com.google.protobuf.kotlin.DslList<com.stevesoltys.seedvault.proto.Snapshot.Split, SplitsProxy>.plusAssign(values: kotlin.collections.Iterable<com.stevesoltys.seedvault.proto.Snapshot.Split>) {
        addAll(values)
      }
      /**
       * <code>repeated .com.stevesoltys.seedvault.proto.Snapshot.Split splits = 4;</code>
       * @param index The index to set the value at.
       * @param value The splits to set.
       */
      @kotlin.jvm.JvmSynthetic
      @kotlin.jvm.JvmName("setSplits")
      public operator fun com.google.protobuf.kotlin.DslList<com.stevesoltys.seedvault.proto.Snapshot.Split, SplitsProxy>.set(index: kotlin.Int, value: com.stevesoltys.seedvault.proto.Snapshot.Split) {
        _builder.setSplits(index, value)
      }
      /**
       * <code>repeated .com.stevesoltys.seedvault.proto.Snapshot.Split splits = 4;</code>
       */
      @kotlin.jvm.JvmSynthetic
      @kotlin.jvm.JvmName("clearSplits")
      public fun com.google.protobuf.kotlin.DslList<com.stevesoltys.seedvault.proto.Snapshot.Split, SplitsProxy>.clear() {
        _builder.clearSplits()
      }
    }
  }
  @kotlin.jvm.JvmName("-initializesplit")
  public inline fun split(block: com.stevesoltys.seedvault.proto.SnapshotKt.SplitKt.Dsl.() -> kotlin.Unit): com.stevesoltys.seedvault.proto.Snapshot.Split =
    com.stevesoltys.seedvault.proto.SnapshotKt.SplitKt.Dsl._create(com.stevesoltys.seedvault.proto.Snapshot.Split.newBuilder()).apply { block() }._build()
  public object SplitKt {
    @kotlin.OptIn(com.google.protobuf.kotlin.OnlyForUseByGeneratedProtoCode::class)
    @com.google.protobuf.kotlin.ProtoDslMarker
    public class Dsl private constructor(
      private val _builder: com.stevesoltys.seedvault.proto.Snapshot.Split.Builder
    ) {
      public companion object {
        @kotlin.jvm.JvmSynthetic
        @kotlin.PublishedApi
        internal fun _create(builder: com.stevesoltys.seedvault.proto.Snapshot.Split.Builder): Dsl = Dsl(builder)
      }

      @kotlin.jvm.JvmSynthetic
      @kotlin.PublishedApi
      internal fun _build(): com.stevesoltys.seedvault.proto.Snapshot.Split = _builder.build()

      /**
       * <code>string name = 1;</code>
       */
      public var name: kotlin.String
        @JvmName("getName")
        get() = _builder.getName()
        @JvmName("setName")
        set(value) {
          _builder.setName(value)
        }
      /**
       * <code>string name = 1;</code>
       */
      public fun clearName() {
        _builder.clearName()
      }

      /**
       * An uninstantiable, behaviorless type to represent the field in
       * generics.
       */
      @kotlin.OptIn(com.google.protobuf.kotlin.OnlyForUseByGeneratedProtoCode::class)
      public class ChunkIdsProxy private constructor() : com.google.protobuf.kotlin.DslProxy()
      /**
       * <code>repeated bytes chunkIds = 2;</code>
       */
       public val chunkIds: com.google.protobuf.kotlin.DslList<com.google.protobuf.ByteString, ChunkIdsProxy>
        @kotlin.jvm.JvmSynthetic
        get() = com.google.protobuf.kotlin.DslList(
          _builder.getChunkIdsList()
        )
      /**
       * <code>repeated bytes chunkIds = 2;</code>
       * @param value The chunkIds to add.
       */
      @kotlin.jvm.JvmSynthetic
      @kotlin.jvm.JvmName("addChunkIds")
      public fun com.google.protobuf.kotlin.DslList<com.google.protobuf.ByteString, ChunkIdsProxy>.add(value: com.google.protobuf.ByteString) {
        _builder.addChunkIds(value)
      }/**
       * <code>repeated bytes chunkIds = 2;</code>
       * @param value The chunkIds to add.
       */
      @kotlin.jvm.JvmSynthetic
      @kotlin.jvm.JvmName("plusAssignChunkIds")
      @Suppress("NOTHING_TO_INLINE")
      public inline operator fun com.google.protobuf.kotlin.DslList<com.google.protobuf.ByteString, ChunkIdsProxy>.plusAssign(value: com.google.protobuf.ByteString) {
        add(value)
      }/**
       * <code>repeated bytes chunkIds = 2;</code>
       * @param values The chunkIds to add.
       */
      @kotlin.jvm.JvmSynthetic
      @kotlin.jvm.JvmName("addAllChunkIds")
      public fun com.google.protobuf.kotlin.DslList<com.google.protobuf.ByteString, ChunkIdsProxy>.addAll(values: kotlin.collections.Iterable<com.google.protobuf.ByteString>) {
        _builder.addAllChunkIds(values)
      }/**
       * <code>repeated bytes chunkIds = 2;</code>
       * @param values The chunkIds to add.
       */
      @kotlin.jvm.JvmSynthetic
      @kotlin.jvm.JvmName("plusAssignAllChunkIds")
      @Suppress("NOTHING_TO_INLINE")
      public inline operator fun com.google.protobuf.kotlin.DslList<com.google.protobuf.ByteString, ChunkIdsProxy>.plusAssign(values: kotlin.collections.Iterable<com.google.protobuf.ByteString>) {
        addAll(values)
      }/**
       * <code>repeated bytes chunkIds = 2;</code>
       * @param index The index to set the value at.
       * @param value The chunkIds to set.
       */
      @kotlin.jvm.JvmSynthetic
      @kotlin.jvm.JvmName("setChunkIds")
      public operator fun com.google.protobuf.kotlin.DslList<com.google.protobuf.ByteString, ChunkIdsProxy>.set(index: kotlin.Int, value: com.google.protobuf.ByteString) {
        _builder.setChunkIds(index, value)
      }/**
       * <code>repeated bytes chunkIds = 2;</code>
       */
      @kotlin.jvm.JvmSynthetic
      @kotlin.jvm.JvmName("clearChunkIds")
      public fun com.google.protobuf.kotlin.DslList<com.google.protobuf.ByteString, ChunkIdsProxy>.clear() {
        _builder.clearChunkIds()
      }}
  }
  @kotlin.jvm.JvmName("-initializeblob")
  public inline fun blob(block: com.stevesoltys.seedvault.proto.SnapshotKt.BlobKt.Dsl.() -> kotlin.Unit): com.stevesoltys.seedvault.proto.Snapshot.Blob =
    com.stevesoltys.seedvault.proto.SnapshotKt.BlobKt.Dsl._create(com.stevesoltys.seedvault.proto.Snapshot.Blob.newBuilder()).apply { block() }._build()
  public object BlobKt {
    @kotlin.OptIn(com.google.protobuf.kotlin.OnlyForUseByGeneratedProtoCode::class)
    @com.google.protobuf.kotlin.ProtoDslMarker
    public class Dsl private constructor(
      private val _builder: com.stevesoltys.seedvault.proto.Snapshot.Blob.Builder
    ) {
      public companion object {
        @kotlin.jvm.JvmSynthetic
        @kotlin.PublishedApi
        internal fun _create(builder: com.stevesoltys.seedvault.proto.Snapshot.Blob.Builder): Dsl = Dsl(builder)
      }

      @kotlin.jvm.JvmSynthetic
      @kotlin.PublishedApi
      internal fun _build(): com.stevesoltys.seedvault.proto.Snapshot.Blob = _builder.build()

      /**
       * <code>bytes id = 1;</code>
       */
      public var id: com.google.protobuf.ByteString
        @JvmName("getId")
        get() = _builder.getId()
        @JvmName("setId")
        set(value) {
          _builder.setId(value)
        }
      /**
       * <code>bytes id = 1;</code>
       */
      public fun clearId() {
        _builder.clearId()
      }

      /**
       * <code>uint32 length = 2;</code>
       */
      public var length: kotlin.Int
        @JvmName("getLength")
        get() = _builder.getLength()
        @JvmName("setLength")
        set(value) {
          _builder.setLength(value)
        }
      /**
       * <code>uint32 length = 2;</code>
       */
      public fun clearLength() {
        _builder.clearLength()
      }

      /**
       * <code>uint32 uncompressedLength = 3;</code>
       */
      public var uncompressedLength: kotlin.Int
        @JvmName("getUncompressedLength")
        get() = _builder.getUncompressedLength()
        @JvmName("setUncompressedLength")
        set(value) {
          _builder.setUncompressedLength(value)
        }
      /**
       * <code>uint32 uncompressedLength = 3;</code>
       */
      public fun clearUncompressedLength() {
        _builder.clearUncompressedLength()
      }
    }
  }
}
public inline fun com.stevesoltys.seedvault.proto.Snapshot.copy(block: com.stevesoltys.seedvault.proto.SnapshotKt.Dsl.() -> kotlin.Unit): com.stevesoltys.seedvault.proto.Snapshot =
  com.stevesoltys.seedvault.proto.SnapshotKt.Dsl._create(this.toBuilder()).apply { block() }._build()

public inline fun com.stevesoltys.seedvault.proto.Snapshot.App.copy(block: com.stevesoltys.seedvault.proto.SnapshotKt.AppKt.Dsl.() -> kotlin.Unit): com.stevesoltys.seedvault.proto.Snapshot.App =
  com.stevesoltys.seedvault.proto.SnapshotKt.AppKt.Dsl._create(this.toBuilder()).apply { block() }._build()

public val com.stevesoltys.seedvault.proto.Snapshot.AppOrBuilder.apkOrNull: com.stevesoltys.seedvault.proto.Snapshot.Apk?
  get() = if (hasApk()) getApk() else null

public inline fun com.stevesoltys.seedvault.proto.Snapshot.Apk.copy(block: com.stevesoltys.seedvault.proto.SnapshotKt.ApkKt.Dsl.() -> kotlin.Unit): com.stevesoltys.seedvault.proto.Snapshot.Apk =
  com.stevesoltys.seedvault.proto.SnapshotKt.ApkKt.Dsl._create(this.toBuilder()).apply { block() }._build()

public inline fun com.stevesoltys.seedvault.proto.Snapshot.Split.copy(block: com.stevesoltys.seedvault.proto.SnapshotKt.SplitKt.Dsl.() -> kotlin.Unit): com.stevesoltys.seedvault.proto.Snapshot.Split =
  com.stevesoltys.seedvault.proto.SnapshotKt.SplitKt.Dsl._create(this.toBuilder()).apply { block() }._build()

public inline fun com.stevesoltys.seedvault.proto.Snapshot.Blob.copy(block: com.stevesoltys.seedvault.proto.SnapshotKt.BlobKt.Dsl.() -> kotlin.Unit): com.stevesoltys.seedvault.proto.Snapshot.Blob =
  com.stevesoltys.seedvault.proto.SnapshotKt.BlobKt.Dsl._create(this.toBuilder()).apply { block() }._build()

