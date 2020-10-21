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
package org.wso2.lsp4intellij.client.languageserver.requestmanager;

import com.intellij.openapi.diagnostic.Logger;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.wso2.lsp4intellij.client.languageserver.ServerStatus;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Default implementation for LSP requests/notifications handling.
 */
public class DefaultRequestManager implements RequestManager {

    private Logger LOG = Logger.getInstance(DefaultRequestManager.class);

    private final LanguageServerWrapper wrapper;
    private final LanguageServer server;
    private final LanguageClient client;
    private final ServerCapabilities serverCapabilities;
    private final TextDocumentSyncOptions textDocumentOptions;
    private final WorkspaceService workspaceService;
    private final TextDocumentService textDocumentService;

    public DefaultRequestManager(LanguageServerWrapper wrapper, LanguageServer server, LanguageClient client,
                                 ServerCapabilities serverCapabilities) {

        this.wrapper = wrapper;
        this.server = server;
        this.client = client;
        this.serverCapabilities = serverCapabilities;

        textDocumentOptions = serverCapabilities.getTextDocumentSync().isRight() ?
                serverCapabilities.getTextDocumentSync().getRight() : null;
        workspaceService = server.getWorkspaceService();
        textDocumentService = server.getTextDocumentService();
    }

    public LanguageServerWrapper getWrapper() {
        return wrapper;
    }

    public LanguageClient getClient() {
        return client;
    }

    public LanguageServer getServer() {
        return server;
    }

    @Override
    public ServerCapabilities getServerCapabilities() {
        return serverCapabilities;
    }

    @Override
    public TextDocumentSyncOptions getTextDocumentOptions() {
        return textDocumentOptions;
    }

    // Client
    @Override
    public void showMessage(MessageParams messageParams) {
        client.showMessage(messageParams);
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams showMessageRequestParams) {
        return client.showMessageRequest(showMessageRequestParams);
    }

    @Override
    public void logMessage(MessageParams messageParams) {
        client.logMessage(messageParams);
    }

    @Override
    public void telemetryEvent(Object o) {
        client.telemetryEvent(o);
    }

    @Override
    public CompletableFuture<Void> registerCapability(RegistrationParams params) {
        return client.registerCapability(params);
    }

    @Override
    public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
        return client.unregisterCapability(params);
    }

    @Override
    public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams params) {
        return client.applyEdit(params);
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams publishDiagnosticsParams) {
        client.publishDiagnostics(publishDiagnosticsParams);
    }

    @Override
    public void semanticHighlighting(SemanticHighlightingParams params) {
        client.semanticHighlighting(params);
    }

    // Server

    // General
    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        if (checkStatus()) {
            try {
                return server.initialize(params);
            } catch (Exception e) {
                crashed(e);
                return null;
            }
        } else {
            return null;
        }
    }

    @Override
    public void initialized(InitializedParams params) {
        if (wrapper.getStatus() == ServerStatus.STARTED) {
            try {
                server.initialized(params);
            } catch (Exception e) {
                crashed(e);
            }
        }
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        if (checkStatus()) {
            try {
                return server.shutdown();
            } catch (Exception e) {
                crashed(e);
                return null;
            }
        }
        return null;
    }

    @Override
    public void exit() {
        if (checkStatus()) {
            try {
                server.exit();
            } catch (Exception e) {
                crashed(e);
            }
        }
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        if (checkStatus()) {
            try {
                return textDocumentService;
            } catch (Exception e) {
                crashed(e);
                return null;
            }
        }
        return null;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        if (checkStatus()) {
            try {
                return workspaceService;
            } catch (Exception e) {
                crashed(e);
                return null;
            }
        } else {
            return null;
        }
    }

    // Workspace service
    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        if (checkStatus()) {
            try {
                workspaceService.didChangeConfiguration(params);
            } catch (Exception e) {
                crashed(e);
            }
        }
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        if (checkStatus()) {
            try {
                workspaceService.didChangeWatchedFiles(params);
            } catch (Exception e) {
                crashed(e);
            }
        }
    }

    public CompletableFuture<List<? extends SymbolInformation>> symbol(WorkspaceSymbolParams params) {
        if (checkStatus()) {
            try {
                // this can be null!
                return serverCapabilities.getWorkspaceSymbolProvider() == Boolean.TRUE ? workspaceService.symbol(params) : null;
            } catch (Exception e) {
                crashed(e);
                return null;
            }
        } else
            return null;
    }

    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
        if (checkStatus()) {
            try {
                return serverCapabilities.getExecuteCommandProvider() != null ? workspaceService.executeCommand(params) : null;
            } catch (Exception e) {
                crashed(e);
                return null;
            }
        }
        return null;
    }

    // Text document service
    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        if (checkStatus()) {
            try {
                if (textDocumentOptions == null || textDocumentOptions.getOpenClose()) {
                    textDocumentService.didOpen(params);
                }
            } catch (Exception e) {
                crashed(e);
            }
        }
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        if (checkStatus()) {
            try {
                if (textDocumentOptions == null || textDocumentOptions.getChange() != null) {
                    textDocumentService.didChange(params);
                }
            } catch (Exception e) {
                crashed(e);
            }
        }
    }

    @Override
    public void willSave(WillSaveTextDocumentParams params) {
        if (checkStatus()) {
            try {
                if (textDocumentOptions != null && textDocumentOptions.getWillSave() == Boolean.TRUE ) {
                    textDocumentService.willSave(params);
                }
            } catch (Exception e) {
                crashed(e);
            }
        }
    }

    @Override
    public CompletableFuture<List<TextEdit>> willSaveWaitUntil(WillSaveTextDocumentParams params) {
        if (checkStatus()) {
            try {
                return textDocumentOptions != null && textDocumentOptions.getWillSaveWaitUntil() == Boolean.TRUE ?
                        textDocumentService.willSaveWaitUntil(params) : null;
            } catch (Exception e) {
                crashed(e);
                return null;
            }
        }
        return null;
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        if (checkStatus()) {
            try {
                if (textDocumentOptions != null && textDocumentOptions.getSave() != null) {
                    textDocumentService.didSave(params);
                }
            } catch (Exception e) {
                crashed(e);
            }
        }
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        if (checkStatus()) {
            try {
                if (textDocumentOptions != null && textDocumentOptions.getOpenClose() == Boolean.TRUE) {
                    textDocumentService.didClose(params);
                }
            } catch (Exception e) {
                crashed(e);
            }
        }
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        if (checkStatus()) {
            try {
                if (serverCapabilities.getCompletionProvider() != null) {
                    return textDocumentService.completion(params);
                }
            } catch (Exception e) {
                crashed(e);
            }
        }
        return null;
    }

    @Override
    public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem unresolved) {
        if (checkStatus()) {
            try {
                final CompletionOptions cProvider = serverCapabilities.getCompletionProvider();
                if (cProvider != null && cProvider.getResolveProvider() == Boolean.TRUE ) {
                    return textDocumentService.resolveCompletionItem(unresolved);
                }
            } catch (Exception e) {
                crashed(e);
            }
        }
        return null;
    }

    @Override
    public CompletableFuture<Hover> hover(TextDocumentPositionParams params) {
        return hover(new HoverParams(params.getTextDocument(), params.getPosition()));
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        if (checkStatus()) {
            try {
                if (serverCapabilities.getHoverProvider() != null) {
                    return textDocumentService.hover(params);
                }
            } catch (Exception e) {
                crashed(e);
            }
        }
        return null;
    }

    @Override
    public CompletableFuture<SignatureHelp> signatureHelp(TextDocumentPositionParams params) {
        return signatureHelp(new SignatureHelpParams(params.getTextDocument(), params.getPosition()));
    }

    @Override
    public CompletableFuture<SignatureHelp> signatureHelp(SignatureHelpParams params) {
        if (checkStatus()) {
            try {
                if (serverCapabilities.getSignatureHelpProvider() != null) {
                    return textDocumentService.signatureHelp(params);
                }
            } catch (Exception e) {
                crashed(e);
            }
        }
        return null;
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        if (checkStatus()) {
            try {
                if (serverCapabilities.getReferencesProvider() != null && serverCapabilities.getReferencesProvider()) {
                    return textDocumentService.references(params);
                }
            } catch (Exception e) {
                crashed(e);
            }
        }
        return null;
    }

    @Override
    public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(TextDocumentPositionParams params) {
        return documentHighlight(new DocumentHighlightParams(params.getTextDocument(), params.getPosition()));
    }

    @Override
    public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(DocumentHighlightParams params) {
        if (checkStatus()) {
            try {
                if (serverCapabilities.getDocumentHighlightProvider()) {
                    return textDocumentService.documentHighlight(params);
                }
            } catch (Exception e) {
                crashed(e);
            }
        }
        return null;
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
        if (checkStatus()) {
            try {
                if (serverCapabilities.getDocumentSymbolProvider()) {
                    return textDocumentService.documentSymbol(params);
                }
            } catch (Exception e) {
                crashed(e);
            }
        }
        return null;
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
        if (checkStatus()) {
            try {
                if (serverCapabilities.getDocumentFormattingProvider()) {
                    return textDocumentService.formatting(params);
                }
            } catch (Exception e) {
                crashed(e);
            }
        }
        return null;
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> rangeFormatting(DocumentRangeFormattingParams params) {
        if (checkStatus()) {
            try {
                if (serverCapabilities.getDocumentRangeFormattingProvider() != null) {
                    return textDocumentService.rangeFormatting(params);
                }
            } catch (Exception e) {
                crashed(e);
            }
        }
        return null;
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> onTypeFormatting(DocumentOnTypeFormattingParams params) {
        if (checkStatus()) {
            try {
                if (serverCapabilities.getDocumentOnTypeFormattingProvider() != null) {
                    return textDocumentService.onTypeFormatting(params);
                }
            } catch (Exception e) {
                crashed(e);
            }
        }
        return null;
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(TextDocumentPositionParams params) {
        return definition(new DefinitionParams(params.getTextDocument(), params.getPosition()));
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        if (checkStatus()) {
            try {
                if (serverCapabilities.getDefinitionProvider()) {
                    return textDocumentService.definition(params);
                }
            } catch (Exception e) {
                crashed(e);
            }
        }
        return null;
    }

    @Override
    public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
        if (checkStatus()) {
            try {
                Either<Boolean, CodeActionOptions> provider = serverCapabilities.getCodeActionProvider();
                if (provider != null && (provider.getLeft() == Boolean.TRUE || provider.getRight() != null)) {
                    return textDocumentService.codeAction(params);
                }
            } catch (Exception e) {
                crashed(e);
            }
        }
        return null;
    }

    @Override
    public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
        if (checkStatus()) {
            try {
                if (serverCapabilities.getCodeLensProvider() != null) {
                    return textDocumentService.codeLens(params);
                }
            } catch (Exception e) {
                crashed(e);
            }
        }
        return null;
    }

    @Override
    public CompletableFuture<CodeLens> resolveCodeLens(CodeLens unresolved) {
        if (checkStatus()) {
            try {
                final CodeLensOptions codeLensProvider = serverCapabilities.getCodeLensProvider();
                if (codeLensProvider != null && codeLensProvider.isResolveProvider()) {
                    return textDocumentService.resolveCodeLens(unresolved);
                }
            } catch (Exception e) {
                crashed(e);
            }
        }
        return null;
    }

    @Override
    public CompletableFuture<List<DocumentLink>> documentLink(DocumentLinkParams params) {
        if (checkStatus()) {
            try {
                if (serverCapabilities.getDocumentLinkProvider() != null) {
                    // TODO: [ms] implementation hint: EditorHyperlinkSupport
                    return textDocumentService.documentLink(params);
                }
            } catch (Exception e) {
                crashed(e);
            }
        }
        return null;
    }

    @Override
    public CompletableFuture<DocumentLink> documentLinkResolve(DocumentLink unresolved) {
        if (checkStatus()) {
            try {
                final DocumentLinkOptions documentLinkProvider = serverCapabilities.getDocumentLinkProvider();
                if (documentLinkProvider != null && documentLinkProvider.getResolveProvider()) {
                    return textDocumentService.documentLinkResolve(unresolved);
                }
            } catch (Exception e) {
                crashed(e);
            }
        }
        return null;
    }

    @Override
    public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
        // FIXME: rename request
        //        if (checkStatus()) {
        //            try {
        //                return (checkProvider((Either<Boolean, StaticRegistrationOptions>)serverCapabilities.getRenameProvider())) ?
        //                        textDocumentService.rename(params) :
        //                        null;
        //            } catch (Exception e) {
        //                crashed(e);
        //                return null;
        //            }
        //        }
        return null;
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> implementation(ImplementationParams params) {
        return null;
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> typeDefinition(TypeDefinitionParams params) {
        return null;
    }

    @Override
    public CompletableFuture<List<ColorInformation>> documentColor(DocumentColorParams params) {
        return null;
    }

    @Override
    public CompletableFuture<List<ColorPresentation>> colorPresentation(ColorPresentationParams params) {
        return null;
    }

    @Override
    public CompletableFuture<List<FoldingRange>> foldingRange(FoldingRangeRequestParams params) {
        return null;
    }

    public boolean checkStatus() {
        return wrapper.getStatus() == ServerStatus.INITIALIZED;
    }

    private void crashed(Exception e) {
        LOG.warn(e);
        wrapper.crashed(e);
    }

}
