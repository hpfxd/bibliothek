/*
 * This file is part of bibliothek, licensed under the MIT License.
 *
 * Copyright (c) 2019-2024 PaperMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.papermc.bibliothek.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mongodb.internal.HexUtils;
import io.papermc.bibliothek.configuration.AppConfiguration;
import io.papermc.bibliothek.database.model.Build;
import io.papermc.bibliothek.database.model.Project;
import io.papermc.bibliothek.database.model.Version;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;

@Service
public class GithubArtifactDownloadService {
  private final AppConfiguration appConfiguration;
  private final RestTemplate restTemplate;

  @Autowired
  public GithubArtifactDownloadService(
    final AppConfiguration appConfiguration,
    final RestTemplateBuilder restTemplateBuilder
  ) {
    this.appConfiguration = appConfiguration;
    this.restTemplate = restTemplateBuilder.build();
  }

  @Async
  public CompletableFuture<Collection<Build.Download>> retrieveArtifacts(
    final Project project,
    final Version version,
    int buildNumber,
    final String artifactName,
    final URI artifactsApiUri
  ) {
    final HttpHeaders apiRequestHeaders = new HttpHeaders();
    apiRequestHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));
    apiRequestHeaders.setBearerAuth(this.appConfiguration.getGithubToken());

    final ResponseEntity<ArtifactsResponse> apiResponse = this.restTemplate.exchange(artifactsApiUri,
      HttpMethod.GET,
      new HttpEntity<>(apiRequestHeaders),
      ArtifactsResponse.class);

    if (!apiResponse.getStatusCode().is2xxSuccessful()) {
      return CompletableFuture.failedFuture(new RuntimeException("Request to GitHub API failed; got " + apiResponse.getStatusCode().value()));
    }

    ArtifactsResponse.Artifact artifact = null;
    for (final ArtifactsResponse.Artifact candidate : apiResponse.getBody().artifacts()) {
      if (artifactName == null || candidate.name().equalsIgnoreCase(artifactName)) {
        artifact = candidate;
        break;
      }
    }

    if (artifact == null) {
      return CompletableFuture.failedFuture(new RuntimeException("No artifact to publish"));
    }

    try {
      final Path outputDirectory = this.appConfiguration.getStoragePath()
        .resolve(project.name())
        .resolve(version.name())
        .resolve(String.valueOf(buildNumber));

      Files.createDirectories(outputDirectory);

      final List<Build.Download> downloads = this.downloadArtifact(artifact, outputDirectory);

      return CompletableFuture.completedFuture(downloads);
    } catch (Exception e) {
      return CompletableFuture.failedFuture(new RuntimeException("Artifact reading failed for " + artifact.name(), e));
    }
  }

  private List<Build.Download> downloadArtifact(
    final ArtifactsResponse.Artifact artifact,
    final Path outputDirectory
  ) {
    final Path targetPath = outputDirectory.resolve(artifact.name());

    final MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }

    final RequestCallback requestCallback = request -> {
      request.getHeaders().setAccept(List.of(MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL));
      request.getHeaders().setBearerAuth(this.appConfiguration.getGithubToken());
    };

    final ResponseExtractor<Void> responseExtractor = response -> {
      if (!response.getStatusCode().is2xxSuccessful()) {
        throw new IOException("Failed to download artifact; got code " + response.getStatusCode());
      }

      try (final InputStream in = response.getBody();
           final OutputStream out = Files.newOutputStream(targetPath)) {

        final byte[] buffer = new byte[8192];

        int read;
        while ((read = in.read(buffer)) >= 0) {
          digest.update(buffer, 0, read);
          out.write(buffer, 0, read);
        }
      }

      return null;
    };

    this.restTemplate.execute(artifact.archiveDownloadUrl(),
      HttpMethod.GET,
      requestCallback,
      responseExtractor);

    return List.of(
      new Build.Download(
        artifact.name(),
        HexUtils.toHex(digest.digest())
      )
    );
  }

  private record ArtifactsResponse(
    List<Artifact> artifacts
  ) {
    private record Artifact(
      String name,
      @JsonProperty("archive_download_url")
      URI archiveDownloadUrl
    ) {
    }
  }
}
