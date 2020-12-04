/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
package org.wso2.lsp4intellij.client.languageserver;

import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.util.Consumer;
import com.intellij.util.ui.JBUI;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.wso2.lsp4intellij.IntellijLanguageClient;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;
import org.wso2.lsp4intellij.requests.Timeouts;
import org.wso2.lsp4intellij.utils.ApplicationUtils;
import org.wso2.lsp4intellij.utils.GUIUtils;

import javax.swing.*;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LSPServerStatusWidget implements StatusBarWidget {

    private static final Map<Project, List<String>> widgetIDs = new HashMap<>();
    private final Map<Timeouts, Pair<Integer, Integer>> timeouts = new HashMap<>();
    private final LanguageServerWrapper wrapper;
    private final String ext;
    private final Project project;
    private final String projectName;
    private final Map<ServerStatus, Icon> icons;
    private ServerStatus status = ServerStatus.STOPPED;

    private LSPServerStatusWidget(LanguageServerWrapper wrapper) {
        this.wrapper = wrapper;
        this.ext = wrapper.getServerDefinition().ext;
        this.project = wrapper.getProject();
        this.projectName = project.getName();
        this.icons = GUIUtils.getIconProviderFor(wrapper.getServerDefinition()).getStatusIcons();

        for (Timeouts t : Timeouts.values()) {
            timeouts.put(t, new MutablePair<>(0, 0));
        }
    }

    /**
     * Creates a widget given a LanguageServerWrapper and adds it to the status bar
     *
     * @param wrapper The wrapper
     * @return The widget
     */
    public static LSPServerStatusWidget createWidgetFor(LanguageServerWrapper wrapper) {
        LSPServerStatusWidget widget = new LSPServerStatusWidget(wrapper);
        Project project = wrapper.getProject();
        StatusBar statusBar = WindowManager.getInstance().getStatusBar(project);

        List<String> stringList = widgetIDs.computeIfAbsent(project, k -> new ArrayList<>());
        if (stringList.isEmpty()) {
            // initial "WidgetID"
            stringList.add("Position");
        }

        statusBar.addWidget(widget, "before " + stringList.get(0), ServiceManager.getService(IntellijLanguageClient.class));
        stringList.add(0, widget.ID());
        return widget;
    }

    private static void removeWidgetID(LSPServerStatusWidget widget) {
        Project project = widget.wrapper.getProject();
        widgetIDs.get(project).remove(widget.ID());
    }

    public void notifyResult(Timeouts timeout, Boolean success) {
        Pair<Integer, Integer> oldValue = timeouts.get(timeout);
        int failed = oldValue.getValue();
        int succeeded = oldValue.getKey();
        if (success) {
            succeeded++;
        } else {
            failed++;
        }
        timeouts.replace(timeout, new MutablePair<>(succeeded, failed));
    }

    // TODO: this method will be removed from the API in 2020.2
    @Override
    public IconPresentation getPresentation(@NotNull PlatformType type) {
        return new IconPresentation();
    }

    // this method will used starting from 2020.2 as the icon presentation API
    @Override
    public IconPresentation getPresentation() {
        return new IconPresentation();
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
    }

    /**
     * Sets the status of the server
     *
     * @param status The new status
     */
    public void setStatus(ServerStatus status) {
        this.status = status;
        updateWidget();
    }

    private void updateWidget() {
        WindowManager manager = WindowManager.getInstance();
        if (manager != null && project != null && !project.isDisposed()) {
            StatusBar statusBar = manager.getStatusBar(project);
            if (statusBar != null) {
                statusBar.updateWidget(ID());
            }
        }
    }

    @Override
    public void dispose() {
        WindowManager manager = WindowManager.getInstance();
        if (manager != null && project != null && !project.isDisposed()) {
            StatusBar statusBar = manager.getStatusBar(project);
            LSPServerStatusWidget.removeWidgetID(this);
            if (statusBar != null)
                ApplicationUtils.invokeLater(() -> statusBar.removeWidget(ID()));

        }
    }

    @NotNull
    @Override
    public String ID() {
        // ms: widget names are intellij application global -> reloading the configuration -> restarting connections + async -> can remove the wrong (the new generated instead of the old) widget -> hashcode
        return projectName != null && ext != null ? projectName + "_" + ext +"#"+ hashCode() : "anonymous"+"#"+ hashCode() ;
    }

    private class IconPresentation implements StatusBarWidget.IconPresentation {
        @Nullable
        @Override
        public Icon getIcon() {
            return icons.get(status);
        }

        @Nullable
        @Override
        public Consumer<MouseEvent> getClickConsumer() {
            return (MouseEvent t) -> {
                if (wrapper.isRestartable()) {
                    final JLabel label = new JLabel("Restarting the LSP Connection.");
                    StatusBar statusBar = WindowManager.getInstance().getStatusBar(project);
                    statusBar.fireNotificationPopup(label, JBUI.CurrentTheme.NewClassDialog.panelBackground());
                    wrapper.restart();
                    return;
                }

                StringBuilder connectedFiles = new StringBuilder("Connected files :");
                wrapper.getConnectedFiles().forEach(f -> connectedFiles.append(System.lineSeparator()).append(f));
                Messages.showInfoMessage(connectedFiles.toString(), "Connected Files");

                /*
                JBPopupFactory.ActionSelectionAid mnemonics = JBPopupFactory.ActionSelectionAid.MNEMONICS;
                Component component = t.getComponent();
                List<AnAction> actions = new ArrayList<>();
                if (wrapper.getStatus() == ServerStatus.INITIALIZED) {
                    actions.add(new ShowConnectedFiles());
                }

                String title = "Server actions";
                DataContext context = DataManager.getInstance().getDataContext(component);
                DefaultActionGroup group = new DefaultActionGroup(actions);
                ListPopup popup = JBPopupFactory.getInstance()
                        .createActionGroupPopup(title, group, context, mnemonics, true);
                Dimension dimension = popup.getContent().getPreferredSize();
                Point at = new Point(0, -dimension.height);
                popup.show(new RelativePoint(t.getComponent(), at));
                */
            };
        }

        /*
        class ShowConnectedFiles extends AnAction implements DumbAware {
            ShowConnectedFiles() {
                super("&Show Connected Files", "Show the files connected to the server", null);
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                StringBuilder connectedFiles = new StringBuilder("Connected files :");
                wrapper.getConnectedFiles().forEach(f -> connectedFiles.append(System.lineSeparator()).append(f));
                Messages.showInfoMessage(connectedFiles.toString(), "Connected Files");
            }
        }
        */

        @Override
        public String getTooltipText() {
            if (wrapper.getStatus() == ServerStatus.STOPPED) {
                return "Click to reconnect";
            }

            StringBuilder message = new StringBuilder();
            message.append("<html>");
            message.append("<b>").append(projectName).append(": Language server for ").append(ext).append("</b><br>");
            message.append("Timeouts (failed requests)<br>");
            timeouts.forEach((t, v) -> {
                final int timeouts = v.getRight();
                message.append(t.name(), 0, 1).append(t.name().substring(1).toLowerCase()).append(" ");
                int total = v.getLeft() + timeouts;
                if (total != 0) {
                    if (timeouts > 0) {
                        message.append("<font color=\"red\">");
                    }
                    message.append(timeouts).append("/").append(total).append(" (")
                            .append(100 * (double) timeouts / total).append("%)<br>");
                    if (timeouts > 0) {
                        message.append("</font>");
                    }
                } else {
                    message.append("0/0 (0%)<br>");
                }
            });
            message.append("</html>");

            return message.toString();
        }
    }
}
