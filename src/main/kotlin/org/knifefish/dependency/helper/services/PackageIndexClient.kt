package org.knifefish.dependency.helper.services

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.knifefish.dependency.helper.model.*
import org.knifefish.dependency.helper.services.ecosystem.*
import org.knifefish.dependency.helper.util.VersionComparator
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class PackageIndexClient {

    private val httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val descendingVersionComparator = Comparator<String> { left, right -> VersionComparator.compare(left, right) }.reversed()

    fun findLatestVersion(
        dependency: DependencyCoordinate,
        repositories: List<RepositorySpec>,
        policy: LatestVersionPolicy,
    ): VersionInfo {
        val support = ecosystemSupport(dependency.ecosystem) ?: return VersionInfo(null, null, null, null, LookupStatus.NOT_FOUND)
        val info = support.findLatestVersion(dependency, repositories, supportContext())
        return applyPolicy(info, policy)
    }

    fun search(ecosystem: Ecosystem, query: String, repositories: List<RepositorySpec>): List<PackageSearchResult> {
        val support = ecosystemSupport(ecosystem) ?: return emptyList()
        return support.search(query, repositories, supportContext())
    }

    fun availableVersions(
        ecosystem: Ecosystem,
        group: String?,
        name: String,
        repositories: List<RepositorySpec>,
    ): List<String> {
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

    private fun get(url: String): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json, text/plain, */*")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return when (response.statusCode()) {
            in 200..299 -> response.body()
            401, 403 -> throw UnauthorizedException(url)
            429 -> throw RateLimitedException(url)
            else -> throw IllegalStateException("HTTP ${response.statusCode()} for $url")
        }
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

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun encodeNpmPackage(value: String): String =
        value.split("/").joinToString("/") { encode(it) }

    private fun content(element: JsonElement?): String? = runCatching { element?.jsonPrimitive?.content }.getOrNull()

    private fun defaultRepository(ecosystem: Ecosystem): RepositorySpec = when (ecosystem) {
        Ecosystem.MAVEN, Ecosystem.GRADLE -> RepositorySpec(ecosystem, "https://repo1.maven.org/maven2/", "default", true)
        Ecosystem.NPM -> RepositorySpec(ecosystem, "https://registry.npmjs.org/", "default", true)
        Ecosystem.RUST -> RepositorySpec(ecosystem, "https://crates.io/", "default", true)
    }

    private class UnauthorizedException(url: String) : IllegalStateException(url)
    private class RateLimitedException(url: String) : IllegalStateException(url)

    private fun ecosystemSupport(ecosystem: Ecosystem): PackageIndexEcosystemSupport? {
        val extensions = runCatching { PackageIndexEcosystemSupport.EP_NAME.extensionList }.getOrDefault(emptyList())
        return extensions.firstOrNull { it.ecosystem == ecosystem }
            ?: when (ecosystem) {
                Ecosystem.MAVEN -> MavenPackageIndexSupport()
                Ecosystem.GRADLE -> GradlePackageIndexSupport()
                Ecosystem.NPM -> NpmPackageIndexSupport()
                Ecosystem.RUST -> RustPackageIndexSupport()
            }
    }

    private fun supportContext(): PackageIndexContext = PackageIndexContext(
        json = json,
        descendingVersionComparator = descendingVersionComparator,
        get = ::get,
        mapFailure = ::mapFailure,
        defaultRepository = ::defaultRepository,
        encode = ::encode,
        encodeNpmPackage = ::encodeNpmPackage,
        content = ::content,
    )
}
