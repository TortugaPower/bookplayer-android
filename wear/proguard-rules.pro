# BookPlayer Wear release (R8) rules.
#
# Deliberately minimal — see app/proguard-rules.pro. The :core datalayer payloads shared with the
# phone are protected by @SerializedName on every field (enforced by :core's
# SerializedNameCompletenessTest), so the two modules' independent R8 runs can't desync the wire.

# Readable release stack traces (Sentry + Play Console).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
