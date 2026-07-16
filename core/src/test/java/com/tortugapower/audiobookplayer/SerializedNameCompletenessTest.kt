package com.tortugapower.audiobookplayer

import com.google.gson.annotations.SerializedName
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.datalayer.WatchAuthPayload
import com.tortugapower.audiobookplayer.datalayer.WatchChapter
import com.tortugapower.audiobookplayer.datalayer.WatchCommand
import com.tortugapower.audiobookplayer.datalayer.WatchCommandType
import com.tortugapower.audiobookplayer.datalayer.WatchItem
import com.tortugapower.audiobookplayer.datalayer.WatchLibraryState
import com.tortugapower.audiobookplayer.datalayer.WatchNowPlaying
import com.tortugapower.audiobookplayer.datalayer.WatchPlaybackState
import com.tortugapower.audiobookplayer.datalayer.WatchTheme
import com.tortugapower.audiobookplayer.model.ArtworkResponse
import com.tortugapower.audiobookplayer.model.ContentsResponse
import com.tortugapower.audiobookplayer.model.DeleteAccountResponse
import com.tortugapower.audiobookplayer.model.EmailVerificationCheckRequest
import com.tortugapower.audiobookplayer.model.EmailVerificationCheckResponse
import com.tortugapower.audiobookplayer.model.EmailVerificationSendRequest
import com.tortugapower.audiobookplayer.model.EmailVerificationSendResponse
import com.tortugapower.audiobookplayer.model.ExternalSetResponse
import com.tortugapower.audiobookplayer.model.IdentifiersResponse
import com.tortugapower.audiobookplayer.model.ItemConflict
import com.tortugapower.audiobookplayer.model.MatchUuidsResponse
import com.tortugapower.audiobookplayer.model.PasskeyAssertionResponse
import com.tortugapower.audiobookplayer.model.PasskeyCredentialDescriptor
import com.tortugapower.audiobookplayer.model.PasskeyInfo
import com.tortugapower.audiobookplayer.model.PasskeyListResponse
import com.tortugapower.audiobookplayer.model.PasskeyLoginResponse
import com.tortugapower.audiobookplayer.model.PasskeyRegistrationOptions
import com.tortugapower.audiobookplayer.model.PasskeyRegistrationOptionsRequest
import com.tortugapower.audiobookplayer.model.PasskeyRegistrationVerifyRequest
import com.tortugapower.audiobookplayer.model.PasskeyResponse
import com.tortugapower.audiobookplayer.model.PasskeySignInOptionsRequest
import com.tortugapower.audiobookplayer.model.PasskeySignInOptionsResponse
import com.tortugapower.audiobookplayer.model.PasskeyVerifyRequest
import com.tortugapower.audiobookplayer.model.SyncableExternalResource
import com.tortugapower.audiobookplayer.model.SyncableItem
import com.tortugapower.audiobookplayer.model.UploadItemContent
import com.tortugapower.audiobookplayer.model.UploadItemResponse
import com.tortugapower.audiobookplayer.model.GoogleLoginRequest
import com.tortugapower.audiobookplayer.model.GoogleLoginResponse
import com.tortugapower.audiobookplayer.network.GitHubContributor
import com.tortugapower.audiobookplayer.network.GraphQLRequest
import com.tortugapower.audiobookplayer.network.GraphQLResponse
import com.tortugapower.audiobookplayer.network.HardcoverAuthor
import com.tortugapower.audiobookplayer.network.HardcoverBook
import com.tortugapower.audiobookplayer.network.HardcoverContribution
import com.tortugapower.audiobookplayer.network.HardcoverImage
import com.tortugapower.audiobookplayer.network.PreferenceEntryDto
import com.tortugapower.audiobookplayer.network.PreferencesResponse
import com.tortugapower.audiobookplayer.network.services.AudiobookshelfAudioFile
import com.tortugapower.audiobookplayer.network.services.AudiobookshelfFileMetadata
import com.tortugapower.audiobookplayer.network.services.AudiobookshelfItem
import com.tortugapower.audiobookplayer.network.services.AudiobookshelfItemsResponse
import com.tortugapower.audiobookplayer.network.services.AudiobookshelfLibrariesResponse
import com.tortugapower.audiobookplayer.network.services.AudiobookshelfLibrary
import com.tortugapower.audiobookplayer.network.services.AudiobookshelfLoginRequest
import com.tortugapower.audiobookplayer.network.services.AudiobookshelfLoginResponse
import com.tortugapower.audiobookplayer.network.services.AudiobookshelfMedia
import com.tortugapower.audiobookplayer.network.services.AudiobookshelfMetadata
import com.tortugapower.audiobookplayer.network.services.AudiobookshelfProgressRequest
import com.tortugapower.audiobookplayer.network.services.AudiobookshelfServerSettings
import com.tortugapower.audiobookplayer.network.services.AudiobookshelfUser
import com.tortugapower.audiobookplayer.network.services.JellyfinArtist
import com.tortugapower.audiobookplayer.network.services.JellyfinAuthRequest
import com.tortugapower.audiobookplayer.network.services.JellyfinAuthResponse
import com.tortugapower.audiobookplayer.network.services.JellyfinItem
import com.tortugapower.audiobookplayer.network.services.JellyfinItemsResponse
import com.tortugapower.audiobookplayer.network.services.JellyfinSystemInfo
import com.tortugapower.audiobookplayer.network.services.JellyfinUser
import com.tortugapower.audiobookplayer.network.services.JellyfinUserDataRequest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * R8 guard: every class Gson serializes by reflection must carry `@SerializedName` on every
 * serializable field, because release builds minify and rename un-kept field names. A missing
 * annotation is invisible in unminified builds (the field name happens to match the wire) and
 * silently corrupts that one field under R8 — this test turns that into a build-time failure.
 *
 * When adding a Gson-crossing DTO, add it to [gsonClasses]; when a DTO family is removed, drop it.
 * `Map`/`List<String>`/`JsonObject`-typed Gson usage doesn't need to be listed (no custom fields).
 */
class SerializedNameCompletenessTest {

    private val gsonClasses: List<Class<*>> = listOf(
        // model/SyncModels.kt
        ContentsResponse::class.java,
        SyncableItem::class.java,
        SyncableExternalResource::class.java,
        UploadItemResponse::class.java,
        UploadItemContent::class.java,
        ArtworkResponse::class.java,
        ExternalSetResponse::class.java,
        IdentifiersResponse::class.java,
        MatchUuidsResponse::class.java,
        ItemConflict::class.java,
        // model/AuthModels.kt
        EmailVerificationSendRequest::class.java,
        EmailVerificationSendResponse::class.java,
        EmailVerificationCheckRequest::class.java,
        EmailVerificationCheckResponse::class.java,
        PasskeyRegistrationOptionsRequest::class.java,
        PasskeyCredentialDescriptor::class.java,
        PasskeyRegistrationOptions::class.java,
        PasskeyResponse::class.java,
        PasskeyRegistrationVerifyRequest::class.java,
        PasskeyAssertionResponse::class.java,
        PasskeyVerifyRequest::class.java,
        PasskeySignInOptionsRequest::class.java,
        PasskeySignInOptionsResponse::class.java,
        PasskeyLoginResponse::class.java,
        GoogleLoginRequest::class.java,
        GoogleLoginResponse::class.java,
        DeleteAccountResponse::class.java,
        PasskeyInfo::class.java,
        PasskeyListResponse::class.java,
        // network/PreferencesApi.kt
        PreferencesResponse::class.java,
        PreferenceEntryDto::class.java,
        // network/HardcoverApi.kt
        GraphQLRequest::class.java,
        GraphQLResponse::class.java,
        HardcoverAuthor::class.java,
        HardcoverContribution::class.java,
        HardcoverImage::class.java,
        HardcoverBook::class.java,
        // network/GitHubApi.kt
        GitHubContributor::class.java,
        // network/services/AudiobookshelfApi.kt
        AudiobookshelfProgressRequest::class.java,
        AudiobookshelfLoginRequest::class.java,
        AudiobookshelfLoginResponse::class.java,
        AudiobookshelfUser::class.java,
        AudiobookshelfServerSettings::class.java,
        AudiobookshelfLibrariesResponse::class.java,
        AudiobookshelfLibrary::class.java,
        AudiobookshelfItemsResponse::class.java,
        AudiobookshelfItem::class.java,
        AudiobookshelfMedia::class.java,
        AudiobookshelfAudioFile::class.java,
        AudiobookshelfFileMetadata::class.java,
        AudiobookshelfMetadata::class.java,
        // network/services/JellyfinApi.kt
        JellyfinUserDataRequest::class.java,
        JellyfinSystemInfo::class.java,
        JellyfinAuthRequest::class.java,
        JellyfinAuthResponse::class.java,
        JellyfinUser::class.java,
        JellyfinItemsResponse::class.java,
        JellyfinItem::class.java,
        JellyfinArtist::class.java,
        // datalayer/ — crosses the phone↔watch boundary (two independent R8 runs)
        WatchAuthPayload::class.java,
        WatchItem::class.java,
        WatchChapter::class.java,
        WatchNowPlaying::class.java,
        WatchLibraryState::class.java,
        WatchPlaybackState::class.java,
        WatchCommand::class.java,
        WatchTheme::class.java,
    )

    /** Enums Gson serializes by constant name; each constant needs `@SerializedName` too. */
    private val gsonEnums: List<Class<out Enum<*>>> = listOf(
        AccountTier::class.java,
        WatchCommandType::class.java,
    )

    @Test
    fun `every field of every Gson-crossing class is annotated with SerializedName`() {
        val missing = mutableListOf<String>()
        for (clazz in gsonClasses) {
            for (field in clazz.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                if (Modifier.isTransient(field.modifiers)) continue
                if (field.isSynthetic) continue
                if (field.getAnnotation(SerializedName::class.java) == null) {
                    missing += "${clazz.simpleName}.${field.name}"
                }
            }
        }
        assertTrue(
            "Fields missing @SerializedName (R8 renames un-kept field names," +
                " silently breaking Gson in release builds): $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `every constant of every Gson-crossing enum is annotated with SerializedName`() {
        val missing = mutableListOf<String>()
        for (clazz in gsonEnums) {
            for (constant in clazz.enumConstants!!) {
                val field = clazz.getField((constant as Enum<*>).name)
                if (field.getAnnotation(SerializedName::class.java) == null) {
                    missing += "${clazz.simpleName}.${constant.name}"
                }
            }
        }
        assertTrue("Enum constants missing @SerializedName: $missing", missing.isEmpty())
    }
}
