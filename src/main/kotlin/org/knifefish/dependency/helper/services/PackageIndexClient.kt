package org.knifefish.dependency.helper.services

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.io.HttpRequests
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.knifefish.dependency.helper.model.*
import org.knifefish.dependency.helper.services.ecosystem.*
import java.io.IOException
import kotlin.math.min

class PackageIndexClient {

    private val json = Json { ignoreUnknownKeys = true }

    private open class RepositoryHttpException(
        val url: String,
        val statusCode: Int,
        responseMessage: String?,
    ) : IllegalStateException("HTTP $statusCode for $url${responseMessage?.let { ": $it" }.orEmpty()}")

    private class UnauthorizedException(url: String, responseMessage: String?) :
        RepositoryHttpException(url, 401, responseMessage)

    private class RateLimitedException(url: String, responseMessage: String?) :
        RepositoryHttpException(url, 429, responseMessage)

    private class NotFoundException(url: String, responseMessage: String?) :
        RepositoryHttpException(url, 404, responseMessage)

    @Serializable
    private data class RepositoryErrorResponse(
        val message: String? = null,
        val error: String? = null,
    ) {
        fun messageText(): String? = message ?: error
    }

    private fun HttpRequests.HttpStatusException.toRepositoryException(): RepositoryHttpException {
        val responseMessage = parseErrorMessage(message)
        return when (statusCode) {
            401, 403 ->
                UnauthorizedException(url, responseMessage)

            429 ->
                RateLimitedException(url, responseMessage)

            404 ->
                NotFoundException(url, responseMessage)

            else ->
                RepositoryHttpException(url, statusCode, responseMessage)
        }
    }

    private fun parseErrorMessage(response: String?): String? {
        if (response.isNullOrBlank()) return response
        return runCatching { json.decodeFromString<RepositoryErrorResponse>(response).messageText() }
            .getOrNull()
            ?: response
    }

    private fun get(url: String): String {
        return executeWithRetry(url) {
            request(url).readString()
        }
    }

    private fun <T> executeWithRetry(url: String, request: () -> T): T {
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt <= MAX_RETRIES) {
            try {
                return request()
            } catch (error: HttpRequests.HttpStatusException) {
                if (!error.isRetryableStatus() || attempt == MAX_RETRIES) {
                    throw error.toRepositoryException()
                }
                lastError = error
            } catch (error: IOException) {
                if (attempt == MAX_RETRIES) {
                    throw error
                }
                lastError = error
            }
            Thread.sleep(retryDelayMillis(attempt))
            attempt++
        }
        throw lastError ?: IllegalStateException("Package lookup failed for $url")
    }

    private fun request(url: String) = HttpRequests.request(url)
        .connectTimeout(CONNECT_TIMEOUT_MILLIS)
        .readTimeout(READ_TIMEOUT_MILLIS)
        .accept("application/json")
        .productNameAsUserAgent()
        .isReadResponseOnError(true)
        .tuner { connection ->
            connection.setRequestProperty("JB-IDE-Version", ApplicationInfo.getInstance().strictVersion)
        }

    private fun HttpRequests.HttpStatusException.isRetryableStatus(): Boolean {
        return statusCode == REQUEST_TIMEOUT_STATUS || statusCode in SERVER_ERROR_STATUS_RANGE
    }

    private fun retryDelayMillis(attempt: Int): Long {
        return min(1_000L shl attempt, 4_000L)
    }

    private fun mapFailure(repositoryUrl: String, error: Throwable): VersionInfo = when (error) {
        is UnauthorizedException -> VersionInfo(
            latestStable = null,
            latestAvailable = null,
            repositoryUrl = repositoryUrl,
            publishedAt = null,
            status = LookupStatus.UNAUTHORIZED,
            message = "Authentication required",
        )

        is RateLimitedException -> VersionInfo(
            latestStable = null,
            latestAvailable = null,
            repositoryUrl = repositoryUrl,
            publishedAt = null,
            status = LookupStatus.RATE_LIMITED,
            message = "Repository rate limit",
        )

        is NotFoundException -> VersionInfo(
            latestStable = null,
            latestAvailable = null,
            repositoryUrl = repositoryUrl,
            publishedAt = null,
            status = LookupStatus.NOT_FOUND,
            message = "Package not found",
        )

        is RepositoryHttpException -> {
            thisLogger().warn("Package lookup failed for $repositoryUrl: HTTP ${error.statusCode}", error)
            VersionInfo(
                latestStable = null,
                latestAvailable = null,
                repositoryUrl = repositoryUrl,
                publishedAt = null,
                status = LookupStatus.ERROR,
                message = error.message,
            )
        }

        is SerializationException -> {
            thisLogger().warn("Package lookup returned invalid JSON for $repositoryUrl", error)
            VersionInfo(
                latestStable = null,
                latestAvailable = null,
                repositoryUrl = repositoryUrl,
                publishedAt = null,
                status = LookupStatus.ERROR,
                message = "Invalid repository response",
            )
        }

        else -> {
            thisLogger().warn("Package lookup failed for $repositoryUrl", error)
            VersionInfo(
                latestStable = null,
                latestAvailable = null,
                repositoryUrl = repositoryUrl,
                publishedAt = null,
                status = LookupStatus.ERROR,
                message = error.message,
            )
        }
    }

    private fun ecosystemSupport(ecosystem: Ecosystem): PackageIndexEcosystemSupport? {
        val extensions = runCatching { PackageIndexEcosystemSupport.EP_NAME.extensionList }.getOrDefault(emptyList())
        return extensions.firstOrNull { it.ecosystem == ecosystem }
            ?: when (ecosystem) {
                Ecosystem.MAVEN -> MavenPackageIndexSupport()
                Ecosystem.GRADLE -> GradlePackageIndexSupport()
                Ecosystem.NPM -> NpmPackageIndexSupport()
            }
    }

    private fun supportContext(): PackageIndexContext = PackageIndexContext(
        get = ::get,
        json = json,
        mapFailure = ::mapFailure,
    )

    fun findLatestVersion(
        dependency: DependencyCoordinate,
        repositories: List<RepositorySpec>,
        policy: LatestVersionPolicy,
    ): VersionInfo {
        val support =
            ecosystemSupport(dependency.ecosystem) ?: return VersionInfo(null, null, null, null, LookupStatus.NOT_FOUND)
        val info = support.findLatestVersion(dependency, repositories, supportContext())
        return applyPolicy(info, policy)
    }

    fun search(ecosystem: Ecosystem, query: String, repositories: List<RepositorySpec>): List<PackageSearchResult> {
        if (!ecosystem.supportsPackageSearch) return emptyList()
        val support = ecosystemSupport(ecosystem) ?: return emptyList()
        return support.search(query, repositories, supportContext())
    }

    fun availableVersions(
        ecosystem: Ecosystem,
        group: String?,
        name: String,
        repositories: List<RepositorySpec>,
    ): List<String> {
        if (!ecosystem.supportsPackageSearch) return emptyList()
        val support = ecosystemSupport(ecosystem) ?: return emptyList()
        return support.availableVersions(group, name, repositories, supportContext())
    }

    private fun applyPolicy(info: VersionInfo, policy: LatestVersionPolicy): VersionInfo {
        val preferred = when (policy) {
            LatestVersionPolicy.RELEASE_ONLY -> info.latestStable ?: info.latestAvailable
            LatestVersionPolicy.INCLUDE_PRERELEASE -> info.latestAvailable ?: info.latestStable
        }
        return info.copy(latestStable = preferred, latestAvailable = info.latestAvailable ?: info.latestStable)
    }

    private companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 3_000
        private const val READ_TIMEOUT_MILLIS = 3_000
        private const val MAX_RETRIES = 3
        private const val REQUEST_TIMEOUT_STATUS = 408
        private val SERVER_ERROR_STATUS_RANGE = 500..599
    }
}
