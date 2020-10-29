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
package org.wso2.lsp4intellij.client.languageserver.wrapper;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.util.PlatformIcons;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Message;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage;
import org.eclipse.lsp4j.services.LanguageServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.wso2.lsp4intellij.client.DefaultLanguageClient;
import org.wso2.lsp4intellij.client.ServerWrapperBaseClientContext;
import org.wso2.lsp4intellij.client.languageserver.LSPServerStatusWidget;
import org.wso2.lsp4intellij.client.languageserver.ServerOptions;
import org.wso2.lsp4intellij.client.languageserver.ServerStatus;
import org.wso2.lsp4intellij.client.languageserver.requestmanager.DefaultRequestManager;
import org.wso2.lsp4intellij.client.languageserver.requestmanager.RequestManager;
import org.wso2.lsp4intellij.client.languageserver.serverdefinition.LanguageServerDefinition;
import org.wso2.lsp4intellij.editor.EditorEventManager;
import org.wso2.lsp4intellij.extensions.LSPExtensionManager;
import org.wso2.lsp4intellij.listeners.DocumentListenerImpl;
import org.wso2.lsp4intellij.listeners.EditorMouseListenerImpl;
import org.wso2.lsp4intellij.listeners.EditorMouseMotionListenerImpl;
import org.wso2.lsp4intellij.listeners.LSPCaretListenerImpl;
import org.wso2.lsp4intellij.requests.Timeouts;
import org.wso2.lsp4intellij.utils.FileUtils;
import org.wso2.lsp4intellij.utils.LSPException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.*;

import static org.wso2.lsp4intellij.client.languageserver.ServerStatus.*;
import static org.wso2.lsp4intellij.requests.Timeout.getTimeout;
import static org.wso2.lsp4intellij.requests.Timeouts.INIT;
import static org.wso2.lsp4intellij.requests.Timeouts.SHUTDOWN;
import static org.wso2.lsp4intellij.utils.ApplicationUtils.invokeLater;
import static org.wso2.lsp4intellij.utils.FileUtils.*;

/**
 * The implementation of a LanguageServerWrapper (specific to a serverDefinition and a project)
 */
public class LanguageServerWrapper {

    public LanguageServerDefinition serverDefinition;
    private final LSPExtensionManager extManager;
    private final Project project;
    private final HashSet<Editor> editorsWaitingToConnect = new HashSet<>();
    private final String projectRootPath;
    private final Map<String, EditorEventManager> connectedEditors = new ConcurrentHashMap<>();
    private final LSPServerStatusWidget statusWidget;
    private LanguageServer languageServer;
    private DefaultLanguageClient client;
    private RequestManager requestManager;
    private InitializeResult initializeResult = null;
    private Future<?> launcherFuture;
    private CompletableFuture<InitializeResult> initializeFuture;
    private int crashCount = 0;
    private volatile boolean alreadyShownTimeout = false;
    private volatile boolean alreadyShownCrash = false;
    private volatile ServerStatus status = STOPPED;
    private static final Map<Pair<String, String>, LanguageServerWrapper> uriToLanguageServerWrapper =
            new ConcurrentHashMap<>();
    private static final Logger LOG = Logger.getInstance(LanguageServerWrapper.class);

    public LanguageServerWrapper(@NotNull LanguageServerDefinition serverDefinition, @NotNull Project project) {
        this(serverDefinition, project, null);
    }

    public LanguageServerWrapper(@NotNull LanguageServerDefinition serverDefinition, @NotNull Project project,
                                 @Nullable LSPExtensionManager extManager) {
        this.serverDefinition = serverDefinition;
        this.project = project;
        // We need to keep the project rootPath in addition to the project instance, since we cannot get the project
        // base path if the project is disposed.
        this.projectRootPath = project.getBasePath();
        this.statusWidget = LSPServerStatusWidget.createWidgetFor(this);
        this.extManager = extManager;
    }

    public Map<String, EditorEventManager> getConnectedEditors() {
        return connectedEditors;
    }

    /**
     * @param uri     A file uri
     * @param project The related project
     * @return The wrapper for the given uri, or None
     */
    public static LanguageServerWrapper forUri(String uri, Project project) {
        return uriToLanguageServerWrapper.get(new MutablePair<>(uri, FileUtils.projectToUri(project)));
    }

    /**
     * @param editor An editor
     * @return The wrapper for the given editor, or None
     */
    public static LanguageServerWrapper forEditor(Editor editor) {
        return uriToLanguageServerWrapper.get(new MutablePair<>(editorToURIString(editor), editorToProjectFolderUri(editor)));
    }

    public LanguageServerDefinition getServerDefinition() {
        return serverDefinition;
    }

    public String getProjectRootPath() {
        return projectRootPath;
    }

    /**
     * @return if the server supports willSaveWaitUntil
     */
    public boolean isWillSaveWaitUntil() {
        return Optional.ofNullable(getServerCapabilities())
                .map(ServerCapabilities::getTextDocumentSync)
                .map(Either::getRight)
                .map(TextDocumentSyncOptions::getWillSaveWaitUntil)
                .orElse(false);
    }

    /**
     * Warning: this is a long running operation
     *
     * @return the languageServer capabilities, or null if initialization job didn't complete
     */
    @Nullable
    public ServerCapabilities getServerCapabilities() {
        return initializeResult != null ? initializeResult.getCapabilities() : null;
    }

    public void notifyResult(Timeouts timeouts, boolean success) {
        statusWidget.notifyResult(timeouts, success);
    }

    public void notifySuccess(Timeouts timeouts) {
        notifyResult(timeouts, true);
    }

    public void notifyFailure(Timeouts timeouts) {
        notifyResult(timeouts, false);
    }

    /**
     * Returns the EditorEventManager for a given uri
     *
     * @param uri the URI as a string
     * @return the EditorEventManager (or null)
     */
    public EditorEventManager getEditorManagerFor(String uri) {
        return connectedEditors.get(uri);
    }

    /**
     * @return The request manager for this wrapper
     */
    public RequestManager getRequestManager() {
        return requestManager;
    }

    /**
     * @return whether the underlying connection to language languageServer is still active
     */
    public boolean isActive() {
        return launcherFuture != null && !launcherFuture.isDone() && !launcherFuture.isCancelled()
                && !alreadyShownTimeout && !alreadyShownCrash;
    }

    /**
     * Connects an editor to the languageServer
     *
     * @param editor the editor
     */
    public void connect(Editor editor) {
        if (editor == null) {
            LOG.warn("editor is null for " + serverDefinition);
            return;
        }
        if (!FileUtils.isEditorSupported(editor)) {
            LOG.debug("Editor hosts a unsupported file type by the LS library.");
            return;
        }

        String uri = editorToURIString(editor);
        uriToLanguageServerWrapper.put(new MutablePair<>(uri, editorToProjectFolderUri(editor)), this);
        if (connectedEditors.containsKey(uri)) {
            return;
        }

        if (initializeFuture == null) {
            start();
            synchronized (editorsWaitingToConnect) {
                editorsWaitingToConnect.add(editor);
            }
        } else {
            // runnables are getting chained/queued and executed even when initializeFuture is already done
            initializeFuture.thenRun(() -> {
                if (connectedEditors.containsKey(uri)) {
                    return;
                }
                final ServerCapabilities capabilities = getServerCapabilities();
                try {
                    Either<TextDocumentSyncKind, TextDocumentSyncOptions> syncOptions = capabilities.getTextDocumentSync();
                    if (syncOptions != null) {
                        //Todo - Implement
                        //  SelectionListenerImpl selectionListener = new SelectionListenerImpl();
                        DocumentListenerImpl documentListener = new DocumentListenerImpl();
                        EditorMouseListenerImpl mouseListener = new EditorMouseListenerImpl();
                        EditorMouseMotionListenerImpl mouseMotionListener = new EditorMouseMotionListenerImpl();
                        LSPCaretListenerImpl caretListener = new LSPCaretListenerImpl();

                        ServerOptions serverOptions = new ServerOptions(capabilities);
                        EditorEventManager manager;
                        if (extManager != null) {
                            manager = extManager.getExtendedEditorEventManagerFor(editor, documentListener,
                                    mouseListener, mouseMotionListener, caretListener, requestManager, serverOptions,
                                    this);
                            if (manager == null) {
                                manager = new EditorEventManager(editor, documentListener, mouseListener,
                                        mouseMotionListener, caretListener,
                                        requestManager, serverOptions, this);
                            }
                        } else {
                            manager = new EditorEventManager(editor, documentListener, mouseListener,
                                    mouseMotionListener, caretListener,
                                    requestManager, serverOptions, this);
                        }
                        // selectionListener.setManager(manager);
                        documentListener.setManager(manager);
                        mouseListener.setManager(manager);
                        mouseMotionListener.setManager(manager);
                        caretListener.setManager(manager);
                        manager.registerListeners();
                        connectedEditors.put(uri, manager);
                        manager.documentOpened();
                        LOG.info("Created a manager for " + uri);
                        synchronized (editorsWaitingToConnect) {
                            editorsWaitingToConnect.remove(editor);
                        }
                    }
                } catch (Exception e) {
                    LOG.error(e);
                }
            });

        }
    }

    /* cleanup if underlying connection e.g. the socket failed */
    public void connectionFailed() {
        if (initializeFuture != null) {
            if(!initializeFuture.isDone()) {
                initializeFuture.cancel(true);
            }
            initializeFuture = null;
        }
        initializeResult = null;
        languageServer = null;
        connectedEditors.clear();
        setStatus(STOPPED);
    }

    /*
     * The shutdown request is sent from the client to the server. It asks the server to shut down, but to not exit \
     * (otherwise the response might not be delivered correctly to the client).
     * Only if the exit flag is true, particular server instance will exit.
     */
    public void stop(boolean exit) {
        try {
            if (initializeFuture != null) {
                if(!initializeFuture.isDone()) {
                    initializeFuture.cancel(true);
                }
                initializeFuture = null;
            }
            initializeResult = null;
            if (languageServer != null) {
                for (Map.Entry<String, EditorEventManager> ed : connectedEditors.entrySet()) {
                    disconnect(ed.getValue().editor);
                }

                CompletableFuture<Object> shutdown = languageServer.shutdown();
                shutdown.get(getTimeout(SHUTDOWN), TimeUnit.MILLISECONDS);
                notifySuccess(SHUTDOWN);
                if (exit) {
                    languageServer.exit();
                }
            }
        } catch (Exception e) {
            // most likely closed externally.
            notifyFailure(SHUTDOWN);
        } finally {
            if (launcherFuture != null) {
                launcherFuture.cancel(true);
                launcherFuture = null;
            }
            if (serverDefinition != null) {
                serverDefinition.stop(projectRootPath);
            }
            languageServer = null;
            setStatus(STOPPED);
        }
        LOG.info("Wrapper for "+ serverDefinition.ext +" stopped.");
    }

    /**
     * Checks if the wrapper is already connected to the document at the given path.
     *
     * @param fileUri file location
     * @return True if the given file is connected.
     */
    public boolean isConnectedTo(String fileUri) {
        return connectedEditors.containsKey(fileUri);
    }

    /**
     * @return the LanguageServer
     */
    @Nullable
    public LanguageServer getServer() {
        return languageServer;
    }

    /**
     * Starts the LanguageServer
     */
    synchronized public void start() {
        if (status == STOPPED && !alreadyShownCrash && !alreadyShownTimeout) {
            setStatus(STARTING);
            try {
                Pair<InputStream, OutputStream> streams = serverDefinition.start(projectRootPath);
                InputStream inputStream = streams.getKey();
                OutputStream outputStream = streams.getValue();
                ExecutorService executorService = Executors.newCachedThreadPool();
                MessageHandler messageHandler = new MessageHandler(serverDefinition.getServerListener(), () -> getStatus() != STOPPED);
                if (extManager != null && extManager.getExtendedServerInterface() != null) {
                    Class<? extends LanguageServer> remoteServerInterFace = extManager.getExtendedServerInterface();
                    client = extManager.getExtendedClientFor(new ServerWrapperBaseClientContext(this));

                    Launcher<? extends LanguageServer> launcher = Launcher
                            .createLauncher(client, remoteServerInterFace, inputStream, outputStream, executorService,
                                    messageHandler);
                    languageServer = launcher.getRemoteProxy();
                    launcherFuture = launcher.startListening();
                } else {
                    client = new DefaultLanguageClient(new ServerWrapperBaseClientContext(this));
                    Launcher<LanguageServer> launcher = Launcher
                            .createLauncher(client, LanguageServer.class, inputStream, outputStream, executorService,
                                    messageHandler);
                    languageServer = launcher.getRemoteProxy();
                    launcherFuture = launcher.startListening();
                }
                messageHandler.setLanguageServerWrapper(this);

                InitializeParams initParams = client.getInitParams(projectRootPath);
                initializeFuture = languageServer.initialize(initParams);
                initializeFuture.thenRun(() ->{
                    synchronized (editorsWaitingToConnect) {
                        for (Editor ed : editorsWaitingToConnect) {
                            connect(ed);
                        }
                    }
                });

                initializeResult = initializeFuture.get(( initializeFuture.isDone() ? 0 : getTimeout(INIT)), TimeUnit.MILLISECONDS);
                notifySuccess(INIT);

                LOG.info("Got initializeResult for " + serverDefinition + " ; " + projectRootPath);
                if (extManager != null) {
                    requestManager = extManager.getExtendedRequestManagerFor(this, languageServer, client, initializeResult.getCapabilities());
                    if (requestManager == null) {
                        requestManager = new DefaultRequestManager(this, languageServer, client, initializeResult.getCapabilities());
                    }
                } else {
                    requestManager = new DefaultRequestManager(this, languageServer, client, initializeResult.getCapabilities());
                }
                setStatus(STARTED);
                // send the initialized message since some language servers depends on this message
                requestManager.initialized(new InitializedParams());
                setStatus(INITIALIZED);

            } catch (LSPException | IOException e) {
                LOG.warn(e);
                invokeLater(() -> new Notification("LSP","LSP Connection Error", String.format("Can't start server due to %s", e.getMessage()) , NotificationType.WARNING).notify(project));
                setStatus(STOPPED);
            }catch (TimeoutException e) {
                notifyFailure(INIT);
                String msg = String.format("%s \n is not initialized after %d seconds",
                        serverDefinition.toString(), getTimeout(INIT) / 1000);
                LOG.info(msg, e);
                invokeLater(() -> {
                    if (!alreadyShownTimeout) {
                        invokeLater(() -> new Notification("LSP","LSP Initialization Error", msg , NotificationType.WARNING).notify(project));
                        alreadyShownTimeout = true;
                    }
                });
                stop(false);
                LOG.info("Capabilities are null for " + serverDefinition);
            } catch (Exception e) {
                LOG.warn(e);
                stop(false);
                LOG.warn("Capabilities are null for " + serverDefinition);
            }
        }
    }

    public void logMessage(Message message) {
        if (message instanceof ResponseMessage) {
            ResponseMessage responseMessage = (ResponseMessage) message;
            if (responseMessage.getError() != null && (responseMessage.getId()
                    .equals(Integer.toString(ResponseErrorCode.RequestCancelled.getValue())))) {
                LOG.error(new ResponseErrorException(responseMessage.getError()));
            }
        }
    }

    public Project getProject() {
        return project;
    }

    public ServerStatus getStatus() {
        return status;
    }

    private void setStatus(ServerStatus status) {
        this.status = status;
        statusWidget.setStatus(status);
    }

    public void crashed(Exception e) {
        crashCount++;
        if (crashCount <= 3) {
            reconnect();
        } else {
            invokeLater(() -> {
                if (alreadyShownCrash) {
                    reconnect();
                } else {
                    int response = Messages.showYesNoDialog(project, String.format(
                            "LanguageServer for definition %s, project %s keeps crashing due to \n%s\n"
                            , serverDefinition.toString(), project.getName(), e.getMessage()),
                            "Language Server Client Warning", "Keep Connected", "Disconnect", PlatformIcons.CHECK_ICON, new DialogWrapper.DoNotAskOption(){

                                @Override
                                public boolean isToBeShown() {
                                    return PropertiesComponent.getInstance(project).getBoolean("lsp.showcrashmessage", true);
                                }

                                @Override
                                public void setToBeShown(boolean toBeShown, int exitCode) {
                                    PropertiesComponent.getInstance(project).setValue("lsp.showcrashmessage", toBeShown, true);
                                }

                                @Override
                                public boolean canBeHidden() {
                                    return false;
                                }

                                @Override
                                public boolean shouldSaveOptionsOnCancel() {
                                    return false;
                                }

                                @Override
                                public @NotNull @NlsContexts.Checkbox String getDoNotShowMessage() {
                                    return "Remember choice and don't ask again?";
                                }
                            });
                    if (response == Messages.NO) {
                        int confirm = Messages.showYesNoDialog("All the language server based plugin features will be disabled.\n" +
                                "Do you wish to continue?", "", PlatformIcons.WARNING_INTRODUCTION_ICON);
                        if (confirm == Messages.YES) {
                            // Disconnects from the language server.
                            stop(true);
                        } else {
                            reconnect();
                        }
                    } else {
                        reconnect();
                    }
                }
                alreadyShownCrash = true;
                crashCount = 0;
            });
        }
    }

    private void reconnect() {
        // Need to copy by value since connected editors gets cleared during 'stop()' invocation.
        final Set<String> connected = new HashSet<>(connectedEditors.keySet());
        stop(true);
        start();
        for (String uri : connected) {
            connect(uri);
        }
    }

    public List<String> getConnectedFiles() {
        List<String> connected = new ArrayList<>();
        connectedEditors.keySet().forEach(s -> {
            try {
                connected.add(new URI(sanitizeURI(s)).toString());
            } catch (URISyntaxException e) {
                LOG.warn(e);
            }
        });
        return connected;
    }

    public void removeWidget() {
        statusWidget.dispose();
    }

    /**
     * Disconnects an editor from the LanguageServer
     *
     * @param editor The editor
     */
    public void disconnect(Editor editor) {
        disconnect(editorToURIString(editor), editorToProjectFolderUri(editor) );
    }

    /**
     * Disconnects an editor from the LanguageServer
     *
     * @param uri        The file uri
     * @param projectUri The project root uri
     */
    public void disconnect(String uri, String projectUri) {
        EditorEventManager manager = connectedEditors.remove(sanitizeURI(uri));
        if (manager != null) {
            manager.removeListeners();
            manager.documentClosed();
            uriToLanguageServerWrapper.remove(new ImmutablePair<>(sanitizeURI(uri), sanitizeURI(projectUri)));
        }
    }

    private void connect(String uri) {
        FileEditor[] fileEditors = FileEditorManager.getInstance(project)
                .getAllEditors(Objects.requireNonNull(FileUtils.URIToVFS(uri)));

        for (FileEditor ed : fileEditors) {
            if (ed instanceof TextEditor) {
                connect(((TextEditor) ed).getEditor());
                break;
            }
        }
    }

    /**
     * Is the language server in a state where it can be restartable. Normally language server is
     * restartable if it has timeout or has a startup error.
     */
    public boolean isRestartable() {
        return status == STOPPED;
    }

    /**
     * Reset language server wrapper state so it can be started again if it was failed earlier.
     */
    public void restart() {
        if (isRestartable()) {
            start();
            alreadyShownCrash = false;
            alreadyShownTimeout = false;
            reloadEditors(project);
        }
    }

    /**
     * Returns the extension manager associated with this language server wrapper.
     *
     * @return The result can be null if there is not extension manager defined.
     */
    @Nullable
    public final LSPExtensionManager getExtensionManager() {
        return extManager;
    }

}