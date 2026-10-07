package fr.plbls.graylogshipper.ide;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessOutputType;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import fr.plbls.graylogshipper.core.LogSession;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Attaches to every process started from a Run/Debug configuration and streams its console
 * output (stdout/stderr) to Graylog.
 */
public final class RunLogExecutionListener implements ExecutionListener {

    private final Project project;

    public RunLogExecutionListener(Project project) {
        this.project = project;
    }

    @Override
    public void processStarting(@NotNull String executorId, @NotNull ExecutionEnvironment env,
                                @NotNull ProcessHandler handler) {
        ShipperSettings settings = ShipperSettings.getInstance();
        if (!settings.getState().enabled) {
            return;
        }
        String runName = env.getRunProfile().getName();
        if (!settings.accepts(runName)) {
            return;
        }

        Map<String, Object> base = new LinkedHashMap<>();
        base.put("_ij_project", project.getName());
        base.put("_ij_run_config", runName);
        RunnerAndConfigurationSettings rc = env.getRunnerAndConfigurationSettings();
        if (rc != null) {
            base.put("_ij_run_type", rc.getType().getDisplayName());
        }
        base.put("_ij_executor", executorId);
        base.put("_ij_session", UUID.randomUUID().toString().substring(0, 8));

        ShipperService service = ShipperService.getInstance();
        LogSession session = service.openSession(base);
        if (settings.getState().lifecycleEvents) {
            session.emitEvent("▶ " + runName + " démarré (" + executorId + ")", 6, Map.of("_ij_event", "process_started"));
        }

        handler.addProcessListener(new ProcessListener() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                ShipperSettings.Data state = ShipperSettings.getInstance().getState();
                if (!state.enabled) {
                    return;
                }
                String stream;
                if (ProcessOutputType.isStderr(outputType)) {
                    stream = "stderr";
                } else if (ProcessOutputType.isStdout(outputType)) {
                    stream = "stdout";
                } else if (outputType == ProcessOutputTypes.SYSTEM && state.includeSystemOutput) {
                    stream = "system";
                } else {
                    return;
                }
                session.onText(event.getText(), stream);
            }

            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                service.closeSession(session);
                if (ShipperSettings.getInstance().getState().lifecycleEvents) {
                    int code = event.getExitCode();
                    session.emitEvent("■ " + runName + " terminé (code " + code + ")", code == 0 ? 6 : 3,
                            Map.of("_ij_event", "process_terminated", "_ij_exit_code", code));
                }
            }
        });
    }
}
