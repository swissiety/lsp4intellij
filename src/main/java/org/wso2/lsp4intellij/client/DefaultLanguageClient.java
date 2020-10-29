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
package org.wso2.lsp4intellij.client;

import com.intellij.notification.*;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.ui.UIUtil;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageClient;
import org.jetbrains.annotations.NotNull;
import org.wso2.lsp4intellij.IntellijLanguageClient;
import org.wso2.lsp4intellij.editor.EditorEventManager;
import org.wso2.lsp4intellij.editor.EditorEventManagerBase;
import org.wso2.lsp4intellij.requests.WorkspaceEditHandler;
import org.wso2.lsp4intellij.utils.ApplicationUtils;
import org.wso2.lsp4intellij.utils.FileUtils;

import javax.swing.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

public class DefaultLanguageClient implements LanguageClient {

    @NotNull
    final private Logger LOG = Logger.getInstance(DefaultLanguageClient.class);
    @NotNull
    private final NotificationGroup STICKY_NOTIFICATION_GROUP =
            new NotificationGroup("lsp.message.request", NotificationDisplayType.STICKY_BALLOON, false);
    @NotNull
    final private Map<String, DynamicRegistrationMethods> registrations = new ConcurrentHashMap<>();
    @NotNull
    private final ClientContext context;
    protected boolean isModal = false;


    public DefaultLanguageClient(@NotNull ClientContext context) {
        this.context = context;
    }

    public InitializeParams getInitParams(String projectRootPath) {
        InitializeParams initParams = new InitializeParams();
        initParams.setRootUri(FileUtils.pathToUri(projectRootPath));
        initParams.setWorkspaceFolders(ServiceManager.getService(IntellijLanguageClient.class).getWorkspaceFolderList(context.getProject()));

        final List<SymbolKind> supportedSymbols = Arrays.asList(SymbolKind.Class, SymbolKind.Interface, SymbolKind.Method, SymbolKind.Field);

        //TODO update capabilities when implemented
        WorkspaceClientCapabilities workspaceClientCapabilities = new WorkspaceClientCapabilities();
        workspaceClientCapabilities.setApplyEdit(true);
        workspaceClientCapabilities.setDidChangeWatchedFiles(new DidChangeWatchedFilesCapabilities());
        workspaceClientCapabilities.setExecuteCommand(new ExecuteCommandCapabilities());
        workspaceClientCapabilities.setWorkspaceEdit(new WorkspaceEditCapabilities());
        workspaceClientCapabilities.setSymbol(new SymbolCapabilities(new SymbolKindCapabilities(supportedSymbols)));
        workspaceClientCapabilities.setWorkspaceFolders(true);
        workspaceClientCapabilities.setConfiguration(false);

        TextDocumentClientCapabilities textDocumentClientCapabilities = new TextDocumentClientCapabilities();
        textDocumentClientCapabilities.setPublishDiagnostics( new PublishDiagnosticsCapabilities(true, new DiagnosticsTagSupport(), false));
        textDocumentClientCapabilities.setCodeAction(new CodeActionCapabilities());
        textDocumentClientCapabilities.setCompletion(new CompletionCapabilities(new CompletionItemCapabilities(true)));
        textDocumentClientCapabilities.setDefinition(new DefinitionCapabilities());
        textDocumentClientCapabilities.setDocumentHighlight(new DocumentHighlightCapabilities());
        textDocumentClientCapabilities.setFormatting(new FormattingCapabilities());
        textDocumentClientCapabilities.setHover(new HoverCapabilities());
        textDocumentClientCapabilities.setOnTypeFormatting(new OnTypeFormattingCapabilities());
        textDocumentClientCapabilities.setRangeFormatting(new RangeFormattingCapabilities());
        textDocumentClientCapabilities.setReferences(new ReferencesCapabilities());
        textDocumentClientCapabilities.setRename(new RenameCapabilities());
        textDocumentClientCapabilities.setSemanticHighlightingCapabilities(new SemanticHighlightingCapabilities(false));
        textDocumentClientCapabilities.setSignatureHelp(new SignatureHelpCapabilities());
        textDocumentClientCapabilities.setSynchronization(new SynchronizationCapabilities(true, true, true));
        textDocumentClientCapabilities.setDocumentSymbol(new DocumentSymbolCapabilities(new SymbolKindCapabilities(supportedSymbols)));

        initParams.setCapabilities(
                new ClientCapabilities(workspaceClientCapabilities, textDocumentClientCapabilities, null));

        return initParams;
    }


    @Override
    public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams params) {
        boolean response = WorkspaceEditHandler.applyEdit(params.getEdit(), "LSP edits");
        return CompletableFuture.supplyAsync(() -> new ApplyWorkspaceEditResponse(response));
    }

    @Override
    public CompletableFuture<List<Object>> configuration(ConfigurationParams configurationParams) {
        return CompletableFuture.completedFuture(ServiceManager.getService(IntellijLanguageClient.class).getConfigParams(configurationParams));
    }

    @Override
    public CompletableFuture<List<WorkspaceFolder>> workspaceFolders() {
        List<WorkspaceFolder> folders = ServiceManager.getService(IntellijLanguageClient.class).getWorkspaceFolderList(context.getProject());
        return CompletableFuture.completedFuture(folders.isEmpty() ? null : folders);
    }

    @Override
    public CompletableFuture<Void> registerCapability(RegistrationParams params) {
        return CompletableFuture.runAsync(() -> params.getRegistrations().forEach(r -> {
            String id = r.getId();
            Optional<DynamicRegistrationMethods> method = DynamicRegistrationMethods.forName(r.getMethod());
            method.ifPresent(dynamicRegistrationMethods -> registrations.put(id, dynamicRegistrationMethods));

        }));
    }

    @Override
    public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
        return CompletableFuture.runAsync(() -> params.getUnregisterations().forEach((Unregistration r) -> {
            String id = r.getId();
            Optional<DynamicRegistrationMethods> method = DynamicRegistrationMethods.forName(r.getMethod());
            if (registrations.containsKey(id)) {
                registrations.remove(id);
            } else {
                Map<DynamicRegistrationMethods, String> inverted = new HashMap<>();
                for (Map.Entry<String, DynamicRegistrationMethods> entry : registrations.entrySet()) {
                    inverted.put(entry.getValue(), entry.getKey());
                }
                if (method.isPresent() && inverted.containsKey(method.get())) {
                    registrations.remove(inverted.get(method.get()));
                }
            }
        }));
    }

    @Override
    public void telemetryEvent(Object o) {
        LOG.info(o.toString());
    }


    @Override
    public void publishDiagnostics(PublishDiagnosticsParams publishDiagnosticsParams) {
        String uri = FileUtils.sanitizeURI(publishDiagnosticsParams.getUri());
        List<Diagnostic> diagnostics = publishDiagnosticsParams.getDiagnostics();
        EditorEventManager manager = EditorEventManagerBase.forUri(uri);
        if (manager != null) {
            manager.diagnostics(diagnostics);
        }
    }

    @Override
    public void showMessage(MessageParams messageParams) {
        String title = "Language Server message";
        String message = messageParams.getMessage();

        if (isModal) {
            ApplicationUtils.invokeLater(() -> {
                MessageType msgType = messageParams.getType();
                switch (msgType) {
                    case Warning:
                        Messages.showWarningDialog(message, title);
                        break;
                    case Error:
                        Messages.showErrorDialog(message, title);
                        break;
                    case Info:
                    case Log:
                        Messages.showInfoMessage(message, title);
                        break;
                    default:
                        LOG.warn("No message type for " + message);
                        break;
                }
            });

        } else {
            NotificationType type = getNotificationType(messageParams.getType());
            final Notification notification = new Notification(
                    "lsp", messageParams.getType().toString(), messageParams.getMessage(), type);
            notification.notify(context.getProject());
        }
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams showMessageRequestParams) {
        List<MessageActionItem> actions = showMessageRequestParams.getActions();
        String title = "Language Server " + showMessageRequestParams.getType().toString();
        String message = showMessageRequestParams.getMessage();
        MessageType msgType = showMessageRequestParams.getType();

        String[] options = new String[actions == null ? 0 : actions.size()];
        for (int i = 0, size = options.length; i < size; i++) {
            options[i] = actions.get(i).getTitle();
        }


        Integer exitCode;
        FutureTask<Integer> task;
        if (isModal) {
            Icon icon;
            if (msgType == MessageType.Error) {
                icon = UIUtil.getErrorIcon();
            } else if (msgType == MessageType.Warning) {
                icon = UIUtil.getWarningIcon();
            } else if (msgType == MessageType.Info) {
                icon = UIUtil.getInformationIcon();
            } else if (msgType == MessageType.Log) {
                icon = UIUtil.getInformationIcon();
            } else {
                icon = null;
                LOG.warn("No message type for " + message);
            }

            task = new FutureTask<>(
                    () -> Messages.showDialog(message, title, options, 0, icon));
            ApplicationManager.getApplication().invokeAndWait(task);

            try {
                exitCode = task.get();
            } catch (InterruptedException | ExecutionException e) {
                LOG.warn(e.getMessage());
                exitCode = -1;
            }

        } else {

            final Notification notification = STICKY_NOTIFICATION_GROUP.createNotification(title, null, message, getNotificationType(msgType));
            final CompletableFuture<Integer> integerCompletableFuture = new CompletableFuture<>();
            for (int i = 0, optionsSize = options.length; i < optionsSize; i++) {
                int finalI = i;
                notification.addAction(new NotificationAction(options[i]) {
                    @Override
                    public boolean isDumbAware() {
                        return true;
                    }

                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
                        integerCompletableFuture.complete(finalI);
                        notification.expire();
                    }
                });
            }
            notification.whenExpired(() -> {
                if (!integerCompletableFuture.isDone()) {
                    integerCompletableFuture.complete(-1);
                }
            });
            notification.notify(context.getProject());

            try {
                exitCode = integerCompletableFuture.get();
            } catch (InterruptedException | ExecutionException e) {
                LOG.warn(e.getMessage());
                exitCode = -1;
            }
        }
        return CompletableFuture.completedFuture(actions == null || exitCode < 0 ? null : actions.get(exitCode));
    }

    @NotNull
    protected NotificationType getNotificationType(@NotNull MessageType messageType) {
        NotificationType type;
        switch (messageType) {
            case Warning:
                type = NotificationType.WARNING;
                break;
            case Error:
                type = NotificationType.ERROR;
                break;
            case Info:
            case Log:
            default:
                type = NotificationType.INFORMATION;
                break;
        }
        return type;
    }


    @Override
    public void logMessage(MessageParams messageParams) {
        String message = messageParams.getMessage();
        MessageType msgType = messageParams.getType();

        if (msgType == MessageType.Error) {
            LOG.error(message);
        } else if (msgType == MessageType.Warning) {
            LOG.warn(message);
        } else if (msgType == MessageType.Info) {
            LOG.info(message);
        }else if (msgType == MessageType.Log) {
            LOG.debug(message);
        } else {
            LOG.warn("Unknown message type '" + msgType + "' for " + message);
        }
    }

    protected final @NotNull ClientContext getContext() {
        return context;
    }

    @Override
    public void semanticHighlighting(SemanticHighlightingParams params) {
        // Todo
    }
}
