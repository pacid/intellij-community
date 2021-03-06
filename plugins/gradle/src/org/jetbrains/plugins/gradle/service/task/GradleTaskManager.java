/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.plugins.gradle.service.task;

import com.intellij.openapi.externalSystem.model.ExternalSystemException;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener;
import com.intellij.openapi.externalSystem.task.AbstractExternalSystemTaskManager;
import com.intellij.openapi.externalSystem.task.ExternalSystemTaskManager;
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Consumer;
import com.intellij.util.Function;
import com.intellij.util.SystemProperties;
import com.intellij.util.containers.ContainerUtil;
import org.gradle.tooling.BuildLauncher;
import org.gradle.tooling.CancellationTokenSource;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.util.GradleVersion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.service.project.GradleExecutionHelper;
import org.jetbrains.plugins.gradle.service.project.GradleProjectResolver;
import org.jetbrains.plugins.gradle.service.project.GradleProjectResolverExtension;
import org.jetbrains.plugins.gradle.service.execution.UnsupportedCancellationToken;
import org.jetbrains.plugins.gradle.settings.GradleExecutionSettings;
import org.jetbrains.plugins.gradle.util.GradleConstants;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * @author Denis Zhdanov
 * @since 3/14/13 5:09 PM
 */
public class GradleTaskManager extends AbstractExternalSystemTaskManager<GradleExecutionSettings>
  implements ExternalSystemTaskManager<GradleExecutionSettings> {

  private final GradleExecutionHelper myHelper = new GradleExecutionHelper();

  private final Map<ExternalSystemTaskId, CancellationTokenSource> myCancellationMap = ContainerUtil.newConcurrentMap();

  public GradleTaskManager() {
  }

  @Override
  public void executeTasks(@NotNull final ExternalSystemTaskId id,
                           @NotNull final List<String> taskNames,
                           @NotNull String projectPath,
                           @Nullable final GradleExecutionSettings settings,
                           @NotNull final List<String> vmOptions,
                           @NotNull final List<String> scriptParameters,
                           @Nullable final String debuggerSetup,
                           @NotNull final ExternalSystemTaskNotificationListener listener) throws ExternalSystemException {

    // TODO add support for external process mode
    if (ExternalSystemApiUtil.isInProcessMode(GradleConstants.SYSTEM_ID)) {
      for (GradleTaskManagerExtension gradleTaskManagerExtension : GradleTaskManagerExtension.EP_NAME.getExtensions()) {
        if (gradleTaskManagerExtension.executeTasks(
          id, taskNames, projectPath, settings, vmOptions, scriptParameters, debuggerSetup, listener)) {
          return;
        }
      }
    }
    if(!scriptParameters.contains("--tests") && taskNames.contains("test")) {
      ContainerUtil.addAll(scriptParameters, "--tests", "*");
    }

    Function<ProjectConnection, Void> f = new Function<ProjectConnection, Void>() {
      @Override
      public Void fun(ProjectConnection connection) {

        final List<String> initScripts = ContainerUtil.newArrayList();
        final GradleProjectResolverExtension projectResolverChain = GradleProjectResolver.createProjectResolverChain(settings);
        for (GradleProjectResolverExtension resolverExtension = projectResolverChain;
             resolverExtension != null;
             resolverExtension = resolverExtension.getNext()) {
          final String resolverClassName = resolverExtension.getClass().getName();
          resolverExtension.enhanceTaskProcessing(taskNames, debuggerSetup, new Consumer<String>() {
            @Override
            public void consume(String script) {
              if (StringUtil.isNotEmpty(script)) {
                ContainerUtil.addAllNotNull(
                  initScripts,
                  "//-- Generated by " + resolverClassName,
                  script,
                  "//");
              }
            }
          });
        }

        if (!initScripts.isEmpty()) {
          try {
            final File tempFile = FileUtil.createTempFile("init", ".gradle");
            tempFile.deleteOnExit();
            FileUtil.writeToFile(tempFile, StringUtil.join(initScripts, SystemProperties.getLineSeparator()));
            ContainerUtil.addAll(scriptParameters, GradleConstants.INIT_SCRIPT_CMD_OPTION, tempFile.getAbsolutePath());
          }
          catch (IOException e) {
            throw new ExternalSystemException(e);
          }
        }

        GradleVersion gradleVersion = GradleExecutionHelper.getGradleVersion(connection);
        BuildLauncher launcher = myHelper.getBuildLauncher(id, connection, settings, listener, vmOptions, scriptParameters);
        launcher.forTasks(ArrayUtil.toStringArray(taskNames));

        if (gradleVersion != null && gradleVersion.compareTo(GradleVersion.version("2.1")) < 0) {
          myCancellationMap.put(id, new UnsupportedCancellationToken());
        } else {
          final CancellationTokenSource cancellationTokenSource = GradleConnector.newCancellationTokenSource();
          launcher.withCancellationToken(cancellationTokenSource.token());
          myCancellationMap.put(id, cancellationTokenSource);
        }
        try {
          launcher.run();
        }
        finally {
          myCancellationMap.remove(id);
        }
        return null;
      }
    };
    myHelper.execute(projectPath, settings, f);
  }

  @Override
  public boolean cancelTask(@NotNull ExternalSystemTaskId id, @NotNull ExternalSystemTaskNotificationListener listener)
    throws ExternalSystemException {

    // extension points are available only in IDE process
    if (ExternalSystemApiUtil.isInProcessMode(GradleConstants.SYSTEM_ID)) {
      for (GradleTaskManagerExtension gradleTaskManagerExtension : GradleTaskManagerExtension.EP_NAME.getExtensions()) {
        if (gradleTaskManagerExtension.cancelTask(id, listener)) return true;
      }
    }

    final CancellationTokenSource cancellationTokenSource = myCancellationMap.get(id);
    if (cancellationTokenSource != null) {
      cancellationTokenSource.cancel();
    }
    return true;
  }
}
