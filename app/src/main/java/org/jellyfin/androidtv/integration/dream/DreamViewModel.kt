package org.jellyfin.androidtv.integration.dream

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.integration.dream.model.DreamContent
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.lyricsFlow
import org.jellyfin.playback.jellyfin.queue.baseItemFlow
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageFormat
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

@SuppressLint("StaticFieldLeak")
class DreamViewModel(
	private val api: ApiClient,
	private val imageLoader: ImageLoader,
	private val context: Context,
	playbackManager: PlaybackManager,
	private val userPreferences: UserPreferences,
) : ViewModel() {
	private val QueueEntry.nowPlayingFlow
		get() = combine(baseItemFlow, lyricsFlow) { baseItem, lyrics ->
			baseItem?.let {
				DreamContent.NowPlaying(
					item = baseItem,
					lyrics = lyrics,
				)
			}
		}

	@OptIn(ExperimentalCoroutinesApi::class)
	private val _mediaContent = playbackManager.queue.entry
		.flatMapLatest { entry -> entry?.nowPlayingFlow ?: emptyFlow() }
		.distinctUntilChanged()
		.stateIn(
			viewModelScope,
			SharingStarted.WhileSubscribed(),
			null,
		)

	private val _libraryContent = flow {
		// Load first library item after 2 seconds
		// to force the logo at the start of the screensaver
		emit(null)
		delay(2.seconds)

		while (true) {
			val next = getRandomLibraryShowcase()
			if (next != null) {
				emit(next)
				delay(30.seconds)
			} else {
				delay(3.seconds)
			}
		}
	}
		.distinctUntilChanged()
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

	val content = combine(_mediaContent, _libraryContent) { mediaContent, libraryContent ->
		mediaContent ?: libraryContent ?: DreamContent.Logo
	}.stateIn(
		scope = viewModelScope,
		started = SharingStarted.WhileSubscribed(),
		initialValue = _mediaContent.value ?: _libraryContent.value ?: DreamContent.Logo,
	)

	private suspend fun getRandomLibraryShowcase(): DreamContent.LibraryShowcase? {
		val requireParentalRating = userPreferences[UserPreferences.screensaverAgeRatingRequired]
		val maxParentalRating = userPreferences[UserPreferences.screensaverAgeRatingMax]

		try {
			val response by api.itemsApi.getItems(
				includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
				recursive = true,
				sortBy = listOf(ItemSortBy.RANDOM),
				limit = 5,
				imageTypes = listOf(ImageType.BACKDROP),
				maxOfficialRating = if (maxParentalRating == -1) null else maxParentalRating.toString(),
				hasParentalRating = if (requireParentalRating) true else null,
			)

			val item = response.items?.firstOrNull { item ->
				!item.backdropImageTags.isNullOrEmpty()
			} ?: return null

			Timber.i("Loading random library showcase item ${item.id}")

			val backdropTag = item.backdropImageTags!!.randomOrNull()
				?: item.imageTags?.get(ImageType.BACKDROP)

			val logoTag = item.imageTags?.get(ImageType.LOGO)

			val backdropUrl = api.imageApi.getItemImageUrl(
				itemId = item.id,
				imageType = ImageType.BACKDROP,
				tag = backdropTag,
				format = ImageFormat.WEBP,
			)

			val logoUrl = api.imageApi.getItemImageUrl(
				itemId = item.id,
				imageType = ImageType.LOGO,
				tag = logoTag,
				format = ImageFormat.WEBP,
			)

			val (logo, backdrop) = withContext(Dispatchers.IO) {
				val logoDeferred = async {
					imageLoader.execute(
						request = ImageRequest.Builder(context).data(logoUrl).build()
					).drawable?.toBitmap()
				}

				val backdropDeferred = async {
					imageLoader.execute(
						request = ImageRequest.Builder(context).data(backdropUrl).build()
					).drawable?.toBitmap()
				}

				awaitAll(logoDeferred, backdropDeferred)
			}

			if (backdrop == null) {
				return null
			}

			return DreamContent.LibraryShowcase(item, backdrop, logo)
		} catch (err: ApiClientException) {
			Timber.e(err)
			return null
		}
	}
}
