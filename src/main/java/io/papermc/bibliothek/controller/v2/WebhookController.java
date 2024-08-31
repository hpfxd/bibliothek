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
package io.papermc.bibliothek.controller.v2;

import am.ik.webhook.annotation.WebhookPayload;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.papermc.bibliothek.database.model.Build;
import io.papermc.bibliothek.database.model.Project;
import io.papermc.bibliothek.database.model.Version;
import io.papermc.bibliothek.database.repository.BuildCollection;
import io.papermc.bibliothek.database.repository.ProjectCollection;
import io.papermc.bibliothek.database.repository.VersionCollection;
import io.papermc.bibliothek.exception.ProjectNotFound;
import io.papermc.bibliothek.service.GithubArtifactDownloadService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@SuppressWarnings("checkstyle:FinalClass")
public class WebhookController {
  private static final Logger logger = LoggerFactory.getLogger(WebhookController.class);

  private final ObjectMapper json;
  private final GithubArtifactDownloadService downloadService;
  private final ProjectCollection projects;
  private final VersionCollection versions;
  private final BuildCollection builds;

  @Autowired
  private WebhookController(
    final ObjectMapper json,
    final GithubArtifactDownloadService downloadService,
    final ProjectCollection projects,
    final VersionCollection versions,
    final BuildCollection builds
  ) {
    this.json = json;
    this.downloadService = downloadService;
    this.projects = projects;
    this.versions = versions;
    this.builds = builds;
  }

  @Hidden
  @PostMapping(
    path = "/v2/projects/{project:[a-z]+}/versions/{version:" + Version.PATTERN + "}/webhook",
    consumes = MediaType.APPLICATION_JSON_VALUE,
    produces = MediaType.APPLICATION_JSON_VALUE
  )
  public ResponseEntity<?> createBuild(
    @Parameter(name = "project", description = "The project identifier.", example = "pandaspigot")
    @PathVariable("project")
    @Pattern(regexp = "[a-z]+") final String projectName,
    @Parameter(name = "version", description = "A version of the project.")
    @PathVariable("version")
    @Pattern(regexp = Version.PATTERN) final String versionName,

    @Parameter(name = "branch", description = "If the workflow run's head branch does not match this value, it will be ignored", example = "master")
    @RequestParam(value = "branch", required = false) final String branchName,
    @Parameter(name = "workflow", description = "If the workflow name does not match this value, it will be ignored", example = "Build")
    @RequestParam(value = "workflow", required = false) final String workflowName,
    @Parameter(name = "artifact", description = "The artifact to publish", example = "Server JAR")
    @RequestParam(value = "artifact", required = false) final String artifactName,
    @Parameter(name = "download", description = "The download to publish", example = "application", required = true)
    @RequestParam(value = "download") final String downloadName,

    @WebhookPayload @RequestBody final String rawPayload
  ) {
    final WorkflowRunWebhookRequest payload;
    try {
      payload = this.json.readValue(rawPayload, WorkflowRunWebhookRequest.class);
    } catch (JsonProcessingException e) {
      return ResponseEntity.badRequest().build();
    }

    if (payload.action() == WorkflowRunWebhookRequest.Action.COMPLETED) {
      if ((workflowName != null && !workflowName.equalsIgnoreCase(payload.workflow().name())) ||
          (branchName != null && !branchName.equalsIgnoreCase(payload.workflowRun().headBranch()))) {
        logger.info("Ignoring webhook publish request - Workflow: {}, Branch: {}", payload.workflow().name(), payload.workflowRun().headBranch());
        return ResponseEntity.noContent().build();
      }

      final Project project = this.projects.findByName(projectName).orElseThrow(ProjectNotFound::new);

      final Version version = this.versions.findByProjectAndName(project._id(), versionName)
        .orElseGet(() -> this.versions.save(new Version(null, project._id(), null, versionName, Instant.now())));

      this.downloadService.retrieveArtifacts(project, version, payload.workflowRun().runNumber(), artifactName, payload.workflowRun().artifactsUrl())
        .thenAccept(downloads -> {
          final Build build = this.builds.insert(new Build(
            null,
            project._id(),
            version._id(),
            payload.workflowRun().runNumber(),
            payload.workflowRun().runStartedAt(),
            List.of(payload.workflowRun().headCommit().asChange()),
            Map.of(downloadName, downloads.iterator().next()),
            null,
            false
          ));
          logger.info("Inserted build from webhook workflow run #{} - {}", payload.workflowRun().runNumber(), build);
        })
        .exceptionally(throwable -> {
          logger.error("Failed to retrieve artifacts for workflow run #{}", payload.workflowRun().runNumber(), throwable);
          return null;
        });
    }

    return ResponseEntity.noContent().build();
  }

  private record WorkflowRunWebhookRequest(
    Action action,
    Workflow workflow,
    @JsonProperty("workflow_run")
    WorkflowRun workflowRun
  ) {
    private enum Action {
      @JsonProperty("completed")
      COMPLETED,
      @JsonProperty("in_progress")
      IN_PROGRESS,
      @JsonProperty("requested")
      REQUESTED,
    }

    private record Workflow(
      String name
    ) {
    }

    private record WorkflowRun(
      @JsonProperty("artifacts_url")
      URI artifactsUrl,
      @JsonProperty("head_branch")
      String headBranch,
      @JsonProperty("head_commit")
      Commit headCommit,
      @JsonProperty("run_number")
      int runNumber,
      @JsonProperty("run_started_at")
      Instant runStartedAt
    ) {
      private record Commit(
        String id,
        String message
      ) {
        public Build.Change asChange() {
          return new Build.Change(this.id, this.message.lines().findFirst().orElse(null), this.message);
        }
      }
    }
  }
}
