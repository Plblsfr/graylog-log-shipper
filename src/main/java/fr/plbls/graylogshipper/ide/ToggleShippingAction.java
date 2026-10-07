package fr.plbls.graylogshipper.ide;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.project.DumbAware;
import org.jetbrains.annotations.NotNull;

/** Tools ▸ "Envoyer les logs Run/Debug vers Graylog" (checkbox). */
public final class ToggleShippingAction extends ToggleAction implements DumbAware {

    @Override
    public boolean isSelected(@NotNull AnActionEvent e) {
        return ShipperSettings.getInstance().getState().enabled;
    }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
        ShipperSettings.getInstance().getState().enabled = state;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
