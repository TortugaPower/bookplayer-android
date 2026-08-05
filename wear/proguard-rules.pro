# BookPlayer Wear release (R8) rules.
#
# Deliberately minimal — see app/proguard-rules.pro. The :core datalayer payloads shared with the
# phone are protected by @SerializedName on every field (enforced by :core's
# SerializedNameCompletenessTest), so the two modules' independent R8 runs can't desync the wire.

# Readable release stack traces (Sentry + Play Console).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Ongoing Activity (Play-mandated for media apps on Wear): OngoingActivity serializes its state
# into the media notification as a VersionedParcelable that the watch's SystemUI deserializes BY
# CLASS NAME in its own process. R8 renaming/removing any of this machinery makes the watch-face
# indicator silently vanish — Play REJECTED wear 100007 (1.1.2, the first minified build) for
# exactly this; unminified 1.1.1 passed. The libraries' own consumer rules keep only the data
# class + Parcelizer, which is not enough. Keep the whole surface (few KB).
-keep class androidx.wear.ongoing.** { *; }
-keep class androidx.versionedparcelable.** { *; }
