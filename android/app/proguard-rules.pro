# ── Stacktraces ───────────────────────────────────────────────────────────────
# Сохраняем имена файлов и номера строк для читаемых крэш-репортов
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Kotlin Coroutines ─────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.coroutines.** {
    volatile <fields>;
}

# ── DataStore ─────────────────────────────────────────────────────────────────
# Preferences DataStore does not require broad keep rules.

# Retain the APK signature boundary for installed-code Android acceptance while
# PRIVATE_DISTRIBUTION keeps the updater disabled. Only the singleton and this
# entry point are retained; its dependencies still undergo normal R8 shrinking.
# Names may change: the external, same-signer instrumentation uses the R8 mapping
# and parameter types. This adds no exported Android component or updater path.
-keep,allowobfuscation class com.wireturn.app.domain.UpdateSecurity {
    public static final com.wireturn.app.domain.UpdateSecurity INSTANCE;
    public final void verifySignature(java.io.File, int, java.util.List);
}

# ── GSON / Data Models ────────────────────────────────────────────────────────
# Mandatory rules for GSON to work with obfuscation
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses

# Specific data models used for serialization.
# We keep members to ensure GSON can access fields via reflection
# and that field names match JSON keys where @SerializedName is missing.
-keep class com.wireturn.app.data.Profile { *; }
-keep class com.wireturn.app.data.KernelConfig { *; }
-keep class com.wireturn.app.data.KernelConfig$* { *; }
-keep class com.wireturn.app.data.kernel.TurnableConfig { *; }
-keep class com.wireturn.app.data.kernel.TurnableRoute { *; }
-keep class com.wireturn.app.data.kernel.OlcrtcConfig { *; }
-keep class com.wireturn.app.data.kernel.WebdavConfig { *; }
-keep class com.wireturn.app.data.kernel.WebdavBackend { *; }
-keep class com.wireturn.app.data.kernel.FreeTurnConfig { *; }
-keep class com.wireturn.app.data.kernel.QwdttConfig { *; }
-keep class com.wireturn.app.data.WgConfig { *; }
-keep class com.wireturn.app.data.VlessConfig { *; }
-keep class com.wireturn.app.data.ClientConfig { *; }
-keep class com.wireturn.app.data.XraySettings { *; }
-keep class com.wireturn.app.data.XrayConfig { *; }
-keep class com.wireturn.app.data.VpnSettings { *; }
-keep class com.wireturn.app.data.AutoLaunchSettings { *; }
-keep class com.wireturn.app.data.KernelSnapshot { *; }

# Persisted/subscription models added after the original keep list. Gson field
# names are part of the backup and subscription contract, including nested pools.
-keep class com.wireturn.app.data.ConnectionGroup { *; }
-keep class com.wireturn.app.data.MasterDnsGroup { *; }
-keep class com.wireturn.app.data.Subscription { *; }
-keep class com.wireturn.app.data.SubscriptionImportReport { *; }
-keep class com.wireturn.app.data.SubscriptionImportIssue { *; }
-keep class com.wireturn.app.data.ProfileBundle { *; }
-keep class com.wireturn.app.data.SharedNetworkSettings { *; }
-keep class com.wireturn.app.data.NetworkDnsSettings { *; }
-keep class com.wireturn.app.data.NetworkRoutingSettings { *; }
-keep class com.wireturn.app.data.RoutingPolicy { *; }
-keep class com.wireturn.app.data.RoutingRule { *; }
-keep class com.wireturn.app.data.kernel.VpnConfig { *; }
-keep class com.wireturn.app.data.kernel.VpnOptions { *; }
-keep class com.wireturn.app.data.kernel.MasterDnsConfig { *; }
-keep class com.wireturn.app.data.kernel.MasterDnsPoolSelection { *; }
-keep class com.wireturn.app.domain.SplitDnsSettings { *; }
-keep class com.wireturn.app.domain.DnsRecoverySettings { *; }
-keep class com.wireturn.app.domain.SubscriptionRuleOverrides { *; }
-keep class com.wireturn.app.domain.SubscriptionPolicyParser$Resolver { *; }
-keep class com.wireturn.app.domain.MasterDnsResolverResult { *; }
-keep class com.wireturn.app.domain.MasterDnsSavedScan { *; }
-keep class com.wireturn.app.domain.MasterDnsPoolMeasurement { *; }
-keep class com.wireturn.app.domain.CfSavedAddresses { *; }
-keep class com.wireturn.app.domain.CfResult { *; }
# JSch chooses cryptographic implementations by their class names.
-keep class com.jcraft.jsch.** { *; }
# Optional desktop SSH-agent sockets and Kerberos are never selected: ServerSsh
# supplies an Android-bound TCP socket and only password/publickey authentication.
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.ietf.jgss.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**

# Keep enums and their members for GSON and reflection (valueOf)
-keep enum com.wireturn.app.data.** { *; }
-keepclassmembers enum com.wireturn.app.data.** { *; }
-keep class com.wireturn.app.domain.SavedProbeResult { *; }
-keep enum com.wireturn.app.domain.ProbeKind { *; }

# ── JNI / Native ──────────────────────────────────────────────────────────────
# Сохраняем класс и нативные методы для hev-socks5-tunnel
-keep class com.wireturn.app.HevSocks5Tunnel {
    native <methods>;
}
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── Tink (транзитивная зависимость security-crypto) ───────────────────────────
# Аннотационные библиотеки не включены в runtime, предупреждения безопасно подавить
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy

# JNI memfd bridge remains addressable by its native symbol names.
-keep class com.wireturn.app.kernel.MemoryFileDescriptor { *; }
-keep class com.wireturn.app.kernel.MemoryFileDescriptor$Companion { *; }
