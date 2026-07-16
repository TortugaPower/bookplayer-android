# BookPlayer release (R8) rules.
#
# Deliberately minimal: our Gson wire contract is protected by @SerializedName on every
# serialized field (enforced by SerializedNameCompletenessTest / ThemeSpecSerializedNameTest),
# so NO keep rules for our own models are needed — R8 may rename them freely. Library needs
# (Retrofit 2.11+, Gson, OkHttp, Media3, Room, RevenueCat, Sentry, AndroidX) ship their own
# consumer rules.

# Readable release stack traces (Sentry + Play Console): keep file/line info, collapse the
# original source-file attribute to a constant.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Sign in with Google (googleid): GoogleIdTokenCredential is materialized reflectively from the
# CredentialManager Bundle; the library does not ship consumer rules that cover it.
-keep class com.google.android.libraries.identity.googleid.** { *; }

# Compile-only GMS annotation referenced by play review-ktx bytecode; absent at runtime by design.
-dontwarn com.google.android.gms.common.annotation.NoNullnessRewrite

# If R8 reports missing classes on a future dependency bump, add the generated
# missing_rules.txt suggestions here individually — never a blanket -dontwarn **.
