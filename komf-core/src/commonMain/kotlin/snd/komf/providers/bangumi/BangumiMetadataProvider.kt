package snd.komf.providers.bangumi

import snd.komf.model.Image
import snd.komf.model.MatchQuery
import snd.komf.model.MediaType
import snd.komf.model.ProviderBookId
import snd.komf.model.ProviderBookMetadata
import snd.komf.model.ProviderSeriesId
import snd.komf.model.ProviderSeriesMetadata
import snd.komf.model.SeriesSearchResult
import snd.komf.providers.CoreProviders
import snd.komf.providers.MetadataProvider
import snd.komf.providers.bangumi.model.BangumiSubject
import snd.komf.providers.bangumi.model.SubjectSearchData
import snd.komf.providers.bangumi.model.SubjectType
import snd.komf.util.NameSimilarityMatcher

// Manga and Novel are both considered book in Bangumi
// For now, Novel just means "everything"
// In the future, if there's other search support, Bangumi also have Anime, Music, etc.
class BangumiMetadataProvider(
    private val client: BangumiClient,
    private val metadataMapper: BangumiMetadataMapper,
    private val nameMatcher: NameSimilarityMatcher,
    private val fetchSeriesCovers: Boolean,
    private val mediaType: MediaType,
) : MetadataProvider {
    init {
        if (mediaType == MediaType.COMIC) throw IllegalStateException("Comics media type is not supported")
    }

    override fun providerName() = CoreProviders.BANGUMI

    override suspend fun getSeriesMetadata(seriesId: ProviderSeriesId): ProviderSeriesMetadata {
        val series = client.getSubject(seriesId.value.toLong())
        val bookRelations = client.getSubjectRelations(series.id)
            .filter { it.type == SubjectType.BOOK }
            .filter { it.relation == "单行本" }

        val thumbnail = if (fetchSeriesCovers) client.getThumbnail(series) else null
        return metadataMapper.toSeriesMetadata(series, bookRelations, thumbnail)
    }

    override suspend fun getSeriesCover(seriesId: ProviderSeriesId): Image? {
        val series = client.getSubject(seriesId.value.toLong())
        return client.getThumbnail(series)
    }

    override suspend fun getBookMetadata(seriesId: ProviderSeriesId, bookId: ProviderBookId): ProviderBookMetadata {
        val book = client.getSubject(bookId.id.toLong())
        val thumbnail = if (fetchSeriesCovers) client.getThumbnail(book) else null
        return metadataMapper.toBookMetadata(book, thumbnail)
    }

    override suspend fun searchSeries(seriesName: String, limit: Int): Collection<SeriesSearchResult> {
        val foundSeries = mutableListOf<SubjectSearchData>()
        var searchOffset = 0
        while (foundSeries.size < limit) {
            val response  = client.searchSeries(seriesName, offset = searchOffset)
            val searchResults = response.data
            for (item in searchResults) {
                if (isSeries(item)) {
                    foundSeries.add(item)
                    if (foundSeries.size == limit) break
                }
            }

            searchOffset += searchResults.size
            if (response.total == null || searchOffset >= response.total) break
        }

        return foundSeries.asSequence()
            .map {
                metadataMapper.toSearchResult(it)
            }.toList()
    }

    override suspend fun matchSeriesMetadata(matchQuery: MatchQuery): ProviderSeriesMetadata? {
        var subject: BangumiSubject?
        var searchOffset = 0
        do {
            val response = client.searchSeries(matchQuery.seriesName, offset = searchOffset)
            val series = mutableListOf<SubjectSearchData>()
            for (item in response.data) {
                if (isSeries(item)) {
                    series.add(item)
                }
            }

            val matches = series.asSequence()
                .filter { nameMatcher.matches(matchQuery.seriesName, listOfNotNull(it.nameCn, it.name)) }
                .toList()
            subject = firstMatchingType(matches, this.mediaType)
            if (subject != null) break

            searchOffset += response.data.size
        } while (response.total != null && searchOffset < response.total)

        if (subject == null) return null

        val thumbnail = if (fetchSeriesCovers) client.getThumbnail(subject) else null
        val bookRelations = client.getSubjectRelations(subject.id)
            .filter { it.type == SubjectType.BOOK }
            .filter { it.relation == "单行本" }

        return metadataMapper.toSeriesMetadata(
            subject,
            bookRelations,
            thumbnail,
        )
    }

    private suspend fun firstMatchingType(matches: List<SubjectSearchData>, type: MediaType): BangumiSubject? {
        val matchPlatform = when (type) {
            MediaType.MANGA -> "漫画"
            MediaType.NOVEL -> "小说"
            MediaType.COMIC -> return null
        }

        for (match in matches) {
            val subject = client.getSubject(match.id)
            if (subject.platform == matchPlatform) return subject
        }
        return null
    }

    private suspend fun isSeries(searchResult: SubjectSearchData): Boolean {
        // Check 'series' field first, regular series with more than one volume has this field set to true.
        if (searchResult.series) return true

        // Check if this is a single volume series, which doesn't have "系列" relation to another book.
        return client.getSubjectRelations(searchResult.id)
            .none { it.relation == "系列" }
    }
}