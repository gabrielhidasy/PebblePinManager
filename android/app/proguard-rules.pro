# ProGuard / R8 rules for Pin Manager for Pebble (xyz.hidasy.pebblepinmanager)
#
# Built with R8 full mode + proguard-android-optimize.txt + resource shrinking.
# Keep rules are intentionally targeted so R8 aggressively strips unused code
# (especially unused vectors in androidx.compose.material:material-icons-extended).

# --- Crash deobfuscation (Google Play Console mapping.txt / embedded proguard.map) ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- PebbleKit Android 2 AIDL / Binder IPC & companion protocol models ---
# io.rebble.pebblekit2:client and :common do not ship consumer ProGuard rules.
# Preserve all AIDL interfaces, Stubs/Proxies, ContentProvider contracts, Bundle keys,
# and Parcelable/IPC models across the external companion app process boundary.
-keep class io.rebble.pebblekit2.** { *; }

# --- kotlinx.serialization (@Serializable) DataStore & JSON models ---
# Supplements kotlinx-serialization-core's consumer R8 rules so persisted models
# (SentPinRecord, NotificationRuleSettings, NotificationRegexRule, AppNotificationRuleConfig,
# RawNotificationRecord, SimulatedTimeline*, SimulatedPin*Dto, etc.) remain stable across
# R8 optimization passes and in-place app upgrades.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

-keep @kotlinx.serialization.Serializable class xyz.hidasy.pebblepinmanager.** {
    *;
}
-keep class xyz.hidasy.pebblepinmanager.**$$serializer {
    *;
}
-keepclassmembers class xyz.hidasy.pebblepinmanager.**$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# Preserve enum constant names for enums serialized to DataStore JSON via
# kotlinx.serialization or .name / valueOf() (EventSource, PinLayoutType,
# NotificationTextField, NotificationRuleAction, SimulatedTimelineActionType).
-keep enum xyz.hidasy.pebblepinmanager.domain.EventSource { *; }
-keep enum xyz.hidasy.pebblepinmanager.domain.PinLayoutType { *; }
-keep @kotlinx.serialization.Serializable enum xyz.hidasy.pebblepinmanager.** { *; }

# --- Jetpack Compose & AndroidX DataStore runtime ---
# Compose (androidx.compose.**) and Lifecycle (androidx.lifecycle.**) ship their own
# consumer R8 rules; do not add a blanket -keep on androidx.compose.** so unused
# material-icons-extended vectors are stripped cleanly.
# Keep enum values()/valueOf() for Compose rememberSaveable Bundle restoration (e.g. AppTab).
-keepclassmembers enum xyz.hidasy.pebblepinmanager.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Preserve protobuf fields used reflectively by AndroidX Preferences DataStore
# (mirrors androidx.datastore:datastore-preferences-core consumer rule).
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}

# Preserve WorkManager CoroutineWorker subclasses instantiated reflectively.
-keep class xyz.hidasy.pebblepinmanager.remote.RemoteFeedSyncWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
