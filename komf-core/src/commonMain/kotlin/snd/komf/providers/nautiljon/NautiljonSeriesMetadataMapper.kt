package snd.komf.providers.nautiljon

import io.ktor.http.*
import snd.komf.model.Author
import snd.komf.model.AuthorRole
import snd.komf.model.BookMetadata
import snd.komf.model.BookRange
import snd.komf.model.Image
import snd.komf.model.ProviderBookId
import snd.komf.model.ProviderBookMetadata
import snd.komf.model.ProviderSeriesId
import snd.komf.model.ProviderSeriesMetadata
import snd.komf.model.Publisher
import snd.komf.model.PublisherType
import snd.komf.model.ReleaseDate
import snd.komf.model.SeriesBook
import snd.komf.model.SeriesMetadata
import snd.komf.model.SeriesSearchResult
import snd.komf.model.SeriesStatus
import snd.komf.model.SeriesTitle
import snd.komf.model.TitleType.NATIVE
import snd.komf.model.TitleType.ROMAJI
import snd.komf.model.WebLink
import snd.komf.providers.BookMetadataConfig
import snd.komf.providers.CoreProviders
import snd.komf.providers.MetadataConfigApplier
import snd.komf.providers.SeriesMetadataConfig
import snd.komf.providers.nautiljon.model.NautiljonSeries
import snd.komf.providers.nautiljon.model.NautiljonSeriesId
import snd.komf.providers.nautiljon.model.NautiljonVolume
import snd.komf.providers.nautiljon.model.NautiljonVolumeId
import snd.komf.providers.nautiljon.model.SearchResult

class NautiljonSeriesMetadataMapper(
    private val seriesMetadataConfig: SeriesMetadataConfig,
    private val bookMetadataConfig: BookMetadataConfig,
    private val authorRoles: Collection<AuthorRole>,
    private val artistRoles: Collection<AuthorRole>,
) {

    fun toSeriesMetadata(series: NautiljonSeries, thumbnail: Image? = null): ProviderSeriesMetadata {
        val status = when (series.status) {
            "En cours" -> SeriesStatus.ONGOING
            "En attente" -> SeriesStatus.ONGOING
            "Abandonné" -> SeriesStatus.ABANDONED
            "Terminé" -> SeriesStatus.ENDED
            else -> null
        }

        val authors = series.authorsStory.flatMap { author ->
            authorRoles.map { role -> Author(author, role) }
        } + series.authorsArt.flatMap { artist ->
            artistRoles.map { role -> Author(artist, role) }
        }

        val titles = listOfNotNull(
            SeriesTitle(series.title, null, null),
            series.romajiTitle?.let { SeriesTitle(it, ROMAJI, "ja-ro") },
            series.japaneseTitle?.let { SeriesTitle(it, NATIVE, "ja") },
        ) + series.alternativeTitles.map { SeriesTitle(it, null, null) }


        val originalPublisher = series.originalPublisher?.let { Publisher(it, PublisherType.ORIGINAL) }
        val frenchPublisher = series.frenchPublisher?.let { Publisher(it, PublisherType.LOCALIZED, "fr") }
        val publisher = if (seriesMetadataConfig.useOriginalPublisher) originalPublisher
        else frenchPublisher ?: originalPublisher
        val metadata = SeriesMetadata(
            status = status,
            titles = titles,
            summary = series.description?.ifBlank { null },
            publisher = publisher,
            alternativePublishers = setOfNotNull(originalPublisher, frenchPublisher) - setOfNotNull(publisher),
            genres = series.genres,
            tags = series.themes,
            authors = authors,
            thumbnail = thumbnail,
            totalBookCount = series.numberOfVolumes,
            ageRating = series.recommendedAge,
            releaseDate = ReleaseDate(series.startYear, null, null),
            links = listOf(WebLink("Nautiljon", seriesUrl(series.id))),
            score = series.score
        )
        val providerMetadata = ProviderSeriesMetadata(
            id = ProviderSeriesId(series.id.value),
            metadata = metadata,
            books = series.volumes.map {
                SeriesBook(
                    id = ProviderBookId(it.id.value),
                    number = it.number?.let { number -> BookRange(number.toDouble(), number.toDouble()) },
                    edition = it.edition,
                    type = it.type,
                    name = it.name
                )
            }
        )
        return MetadataConfigApplier.apply(providerMetadata, seriesMetadataConfig)
    }

    fun toBookMetadata(volume: NautiljonVolume, thumbnail: Image? = null): ProviderBookMetadata {
        val authors = volume.authorsStory.flatMap { author ->
            authorRoles.map { role -> Author(author, role) }
        } + volume.authorsArt.flatMap { artist ->
            artistRoles.map { role -> Author(artist, role) }
        }

        val metadata = BookMetadata(
            summary = volume.description?.ifBlank { null },
            number = volume.number.let { number -> BookRange(number.toDouble(), number.toDouble()) },
            releaseDate = if (seriesMetadataConfig.useOriginalPublisher) volume.originalReleaseDate else volume.frenchReleaseDate,
            authors = authors,
            links = listOf(WebLink("Nautiljon", bookUrl(volume.seriesId, volume.id))),

            startChapter = null,
            endChapter = null,

            thumbnail = thumbnail
        )
        val providerMetadata = ProviderBookMetadata(
            id = ProviderBookId(volume.id.value),
            metadata = metadata
        )
        return MetadataConfigApplier.apply(providerMetadata, bookMetadataConfig)
    }

    fun toSeriesSearchResult(result: SearchResult): SeriesSearchResult {
        return SeriesSearchResult(
            url = seriesUrl(result.id),
            imageUrl = result.imageUrl,
            title = result.title,
            provider = CoreProviders.NAUTILJON,
            resultId = result.id.value
        )
    }

    private fun seriesUrl(seriesId: NautiljonSeriesId) =
        nautiljonBaseUrl + "/mangas/${seriesId.value.encodeURLPath()}.html"

    private fun bookUrl(seriesId: NautiljonSeriesId, volumeId: NautiljonVolumeId) = nautiljonBaseUrl +
            "/mangas/${seriesId.value.encodeURLPath()}/volume-${volumeId.value.encodeURLPath()}.html"
}
